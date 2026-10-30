# -*- coding: utf-8 -*-
"""wb/report.py —— 报告输出模块（契约 4.4，子代理 C）

职责：把已经跑完的测试数据落盘成 运行目录 / CSV / JSON / Markdown / 原始日志。
不探测设备、不调用 adb、顶层不做任何 IO，可在无手机环境直接 import。

对外 API（签名固定，见 tools/_contract.md 4.4）：
    make_run_dir(base_dir, items, tag="") -> str
    write_csv(path, records) -> None
    write_json(path, payload) -> None
    write_markdown(path, payload) -> None
    write_raw(path, text) -> None
    build_payload(*, meta, devices, records, results, screen=None, notes=None) -> dict
    fmt_bps(rate, unit) -> str

约定：
  * CSV 列顺序固定，空值写空字符串（绝不写 None）。
  * CSV 编码 utf-8-sig + CRLF，Excel 双击不乱码。
  * JSON utf-8 / indent=2 / ensure_ascii=False。
  * Markdown 六节固定：结论摘要 / 测试参数 / 设备信息 / 测试结果 / 亮灭屏对比 / 异常与提示。
"""

from __future__ import annotations

import csv
import datetime as _dt
import json
import os
import re
import unicodedata

SCRIPT_VERSION = "1.0.0"
TOOL_NAME = "WiFi 吞吐量测试工具"

# 契约 4.4 固定列顺序，禁止调整
CSV_HEADER = [
    "时间", "测试", "类型", "线程", "起", "止", "传输量", "单位", "速率",
    "速率单位", "重传或数据报", "抖动ms", "丢包", "总包", "丢包率", "方向",
]

# 契约 2 / 3.3 的固定枚举，仅用于显示
TEST_NAMES = {
    "tcp_up": "WiFi 吞吐量（TCP 正向）",
    "tcp_down": "WiFi 吞吐量（TCP 反向 -R）",
    "udp_up": "UDP 抖动丢包（正向）",
    "udp_down": "UDP 抖动丢包（反向 -R）",
    "long_run": "长稳吞吐测试",
    "screen_on": "亮屏吞吐",
    "screen_off": "灭屏吞吐",
}

ITEM_NAMES = {
    "wifi_tput": "WiFi 吞吐量测试",
    "hotspot_tput": "热点吞吐量测试",
    "screen_cmp": "亮灭屏对比测试",
    "long_run": "长稳吞吐测试",
    "udp_qos": "UDP 抖动丢包测试",
    "link_snapshot": "链路质量快照",
    "pc_tunnel": "PC 参与的隧道测试",
}

_EMPTY = "--"

# ---------------------------------------------------------------- 基础工具

def _f(v):
    """尽量转 float，失败返回 None（不抛异常）。"""
    if v is None or v == "" or isinstance(v, bool):
        return None
    try:
        x = float(v)
    except (TypeError, ValueError):
        return None
    if x != x or x in (float("inf"), float("-inf")):
        return None
    return x


def _i(v):
    """尽量转 int，失败返回 None。"""
    x = _f(v)
    if x is None:
        return None
    return int(round(x))


def _num(v, nd=3):
    """数字转文本：整数不带小数点，浮点去掉尾部 0。None/坏值 -> ""。"""
    if isinstance(v, bool):
        return "1" if v else "0"
    x = _f(v)
    if x is None:
        if isinstance(v, str):
            return v
        return ""
    if abs(x - round(x)) < 1e-9:
        return str(int(round(x)))
    return ("%." + str(nd) + "f") % x


def _f2(v):
    """区间起止：固定 2 位小数。"""
    x = _f(v)
    return "" if x is None else "%.2f" % x


def _s(v):
    """任何值 -> CSV 单元格文本；None -> 空字符串（契约要求不写 None）。"""
    if v is None:
        return ""
    if isinstance(v, str):
        return v
    if isinstance(v, bool):
        return "1" if v else "0"
    if isinstance(v, (int, float)):
        return _num(v)
    return str(v)


def _t(v, default=_EMPTY):
    """Markdown 单元格文本：未知值显示 --。"""
    if v is None:
        return default
    if isinstance(v, str):
        return v if v.strip() else default
    if isinstance(v, bool):
        return "是" if v else "否"
    if isinstance(v, (int, float)):
        s = _num(v)
        return s if s != "" else default
    return str(v)


def _w(s):
    """东亚宽字符按 2 列计算的显示宽度。"""
    n = 0
    for ch in str(s):
        n += 2 if unicodedata.east_asian_width(ch) in ("W", "F") else 1
    return n


def _cell(v):
    """Markdown 单元格转义：竖线与换行会破坏表格。"""
    s = _t(v)
    return s.replace("|", "\\|").replace("\r", " ").replace("\n", " ")


def _md_table(headers, rows):
    """渲染 Markdown 表格，按显示宽度对齐（纯文本视图也好看）。"""
    headers = [str(h) for h in headers]
    rows = [[_cell(c) for c in r] for r in rows]
    cols = len(headers)
    for r in rows:
        while len(r) < cols:
            r.append("")
    widths = []
    for j in range(cols):
        wid = max([_w(headers[j].replace("**", ""))] + [_w(r[j].replace("**", "")) for r in rows])
        widths.append(max(wid, 3))

    def _line(cells):
        out = []
        for c, wid in zip(cells, widths):
            pad = wid - _w(c.replace("**", ""))
            out.append(c + " " * max(pad, 0))
        return "| " + " | ".join(out) + " |"

    lines = [_line(headers), "| " + " | ".join("-" * wid for wid in widths) + " |"]
    lines.extend(_line(r) for r in rows)
    return "\n".join(lines)


# ---------------------------------------------------------------- fmt_bps

_FACTORS = {
    "bits/sec": 1e-6, "bit/sec": 1e-6, "bps": 1e-6, "bits/s": 1e-6,
    "kbits/sec": 1e-3, "kbit/sec": 1e-3, "kbps": 1e-3, "kbits/s": 1e-3,
    "mbits/sec": 1.0, "mbit/sec": 1.0, "mbps": 1.0, "mbits/s": 1.0, "mbit/s": 1.0,
    "gbits/sec": 1e3, "gbit/sec": 1e3, "gbps": 1e3, "gbits/s": 1e3, "gbit/s": 1e3,
    "tbits/sec": 1e6, "tbit/sec": 1e6, "tbps": 1e6,
}


def _unit_factor(unit):
    """把 iperf3 的速率单位换算成 Mbits/sec 的系数。未知单位按 Mbits/sec 处理。"""
    key = str(unit or "Mbits/sec").strip().lower().replace(" ", "")
    return _FACTORS.get(key, 1.0)


def _mbps(rate, unit):
    """速率换算成 Mbits/sec 的浮点数。缺值返回 None。"""
    x = _f(rate)
    if x is None:
        return None
    return x * _unit_factor(unit)


def fmt_bps(rate, unit="Mbits/sec"):
    """把 bits/sec / Kbits/sec / Mbits/sec / Gbits/sec 统一成 Mbits/sec，保留 1 位。"""
    x = _f(rate)
    if x is None:
        return _EMPTY
    factor = _unit_factor(unit)
    return "%.1f Mbits/sec" % (x * factor)


# ---------------------------------------------------------------- make_run_dir

_SAFE = re.compile(r"[^0-9A-Za-z_+\-]+")


def _item_slug(items):
    """把测试项列表压成目录名片段：wifi_tput+udp_qos。"""
    out = []
    for it in items or []:
        if isinstance(it, dict):
            it = it.get("id") or it.get("test_id") or it.get("item") or ""
        s = _SAFE.sub("_", str(it).strip()).strip("_")
        if s and s not in out:
            out.append(s)
    return "+".join(out)


def make_run_dir(base_dir, items, tag=""):
    """建 <base>\\YYYYmmdd_HHMMSS_<items简写>，返回绝对路径。重名自动加 _2。"""
    base_dir = os.path.abspath(str(base_dir))
    os.makedirs(base_dir, exist_ok=True)

    stamp = _dt.datetime.now().strftime("%Y%m%d_%H%M%S")
    slug = _item_slug(items) or "run"
    if len(slug) > 80:
        slug = slug[:80].rstrip("_+")
    tail = _SAFE.sub("_", str(tag or "").strip()).strip("_")
    name = stamp + "_" + slug + (("_" + tail) if tail else "")
    if len(name) > 120:
        name = name[:120].rstrip("_")

    path = os.path.join(base_dir, name)
    n = 2
    while os.path.exists(path):
        path = os.path.join(base_dir, "%s_%d" % (name, n))
        n += 1
    os.makedirs(path)
    return os.path.abspath(path)


# ---------------------------------------------------------------- write_csv

def _csv_row(rec):
    """Record(dict, 契约 3.1) -> 16 列文本。"""
    if not isinstance(rec, dict):
        return [""] * len(CSV_HEADER)
    retr = _s(rec.get("retr"))
    total = _s(rec.get("total"))
    # 契约 3.1：UDP sender 的第 11 字段是 Total Datagrams，落在 total 上
    retr_or_dgram = retr if retr != "" else total
    return [
        _s(rec.get("t")),
        _s(rec.get("test")),
        _s(rec.get("kind")),
        _s(rec.get("stream")),
        _f2(rec.get("start")),
        _f2(rec.get("end")),
        _num(rec.get("transfer"), 3),
        _s(rec.get("unit")),
        _num(rec.get("rate"), 3),
        _s(rec.get("rate_unit")),
        retr_or_dgram,
        _num(rec.get("jitter"), 3),
        _s(rec.get("lost")),
        total,
        _num(rec.get("lost_pct"), 3),
        _s(rec.get("role")),
    ]


def write_csv(path, records):
    """写 records.csv：utf-8-sig（带 BOM）+ CRLF，Excel 双击不乱码。"""
    path = os.path.abspath(str(path))
    parent = os.path.dirname(path)
    if parent:
        os.makedirs(parent, exist_ok=True)
    with open(path, "w", encoding="utf-8-sig", newline="") as fp:
        writer = csv.writer(fp, lineterminator="\r\n")
        writer.writerow(CSV_HEADER)
        for rec in records or []:
            writer.writerow(_csv_row(rec))


# ---------------------------------------------------------------- write_json

def _json_default(o):
    if isinstance(o, (bytes, bytearray)):
        return o.decode("utf-8", "replace")
    if isinstance(o, (set, tuple)):
        return list(o)
    if isinstance(o, _dt.datetime):
        return o.strftime("%Y-%m-%d %H:%M:%S")
    return str(o)


def write_json(path, payload):
    """写 result.json：utf-8，indent=2，ensure_ascii=False（中文原样）。"""
    path = os.path.abspath(str(path))
    parent = os.path.dirname(path)
    if parent:
        os.makedirs(parent, exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as fp:
        json.dump(payload or {}, fp, ensure_ascii=False, indent=2, default=_json_default)
        fp.write("\n")


# ---------------------------------------------------------------- write_raw

def write_raw(path, text):
    """写 iperf3 原始输出，utf-8 无 BOM，一个字符都不改写。"""
    path = os.path.abspath(str(path))
    parent = os.path.dirname(path)
    if parent:
        os.makedirs(parent, exist_ok=True)
    if text is None:
        text = ""
    if not isinstance(text, str):
        text = str(text)
    with open(path, "w", encoding="utf-8", newline="") as fp:
        fp.write(text)


# ---------------------------------------------------------------- 统计与组装

def _rate_of(obj):
    """把 {'rate','unit'} / 纯数字 / None 归一成 {'rate':float|None,'unit':str}。"""
    if isinstance(obj, dict):
        unit = obj.get("unit") or obj.get("rate_unit") or "Mbits/sec"
        return {"rate": _f(obj.get("rate")), "unit": str(unit or "Mbits/sec")}
    x = _f(obj)
    if x is None:
        return None
    return {"rate": x, "unit": "Mbits/sec"}


def _stats(recs):
    """逐秒 Record 汇总：平均/最低/最高速率、抖动、丢包、重传合计。"""
    st = {
        "n": len(recs), "avg_rate": None, "min_rate": None, "max_rate": None,
        "unit": "", "jitter": None, "lost": None, "total": None,
        "lost_pct": None, "retr_sum": None, "transfer_sum": None,
        "transfer_unit": "", "stall_n": None,
    }
    if not recs:
        return st
    sum_rows = [r for r in recs if str(r.get("stream") or "").upper() == "SUM"]
    if not sum_rows:
        # 单流（-P 1）时 iperf3 不打 [SUM]，全程汇总行会伪装成普通行混进逐秒数据，
        # 导致「统计」把 sender 与 receiver 两条汇总相加。按区间长度把汇总行挑出来。
        sum_rows = _pick_full_span(recs)
    per_rows = [r for r in recs if r not in sum_rows]
    tail = sum_rows[-1] if sum_rows else recs[-1]

    # avg/min/max 三个速率下面都换算成了 Mbits/sec，单位必须跟着改，
    # 否则后续 fmt_bps(值, 原单位) 会把 500 Kbits/sec 再换算一次
    st["unit"] = "Mbits/sec"
    st["transfer_unit"] = str(tail.get("unit") or "")

    # 多流时逐秒行是单流速率、SUM 行是合计速率，混在一起算最低/最高会出现
    # 「最高 179 而平均 229」这种自相矛盾的数。有 SUM 行就只用 SUM 行。
    span_rows = [r for r in sum_rows if r not in _pick_full_span(recs)] or per_rows or recs
    # 逐秒行的单位不一定是 Mbits/sec：完全停滞的那一秒 iperf3 打的是 "0.00 bits/sec"。
    # 直接取数值会把 500 Kbits/sec 当成 500 Mbits/sec，所以先统一换算。
    rates = [x for x in (_mbps(r.get("rate"), r.get("rate_unit")) for r in span_rows) if x is not None]
    if rates:
        st["min_rate"] = min(rates)
        st["max_rate"] = max(rates)
        # 换算后为 0 的秒数 = 这一秒一个包都没过去。长稳测试里这个信号比平均值重要，
        # 只写「平均 208、最低 0」会让人以为数据抖动，实际是中途卡死了。
        st["stall_n"] = sum(1 for v in rates if v <= 0)
    avg = _mbps(tail.get("rate"), tail.get("rate_unit"))
    if avg is not None:
        st["avg_rate"] = avg
    elif rates:
        st["avg_rate"] = sum(rates) / len(rates)
    if st["avg_rate"] is not None:
        # 平均取自全程汇总行，最低/最高取自逐秒行，两者口径不同会让区间不包住均值，
        # 看起来像「平均 229、区间 265~265」这种矛盾。把均值并入区间。
        lo = st["min_rate"] if st["min_rate"] is not None else st["avg_rate"]
        hi = st["max_rate"] if st["max_rate"] is not None else st["avg_rate"]
        st["min_rate"] = min(lo, st["avg_rate"])
        st["max_rate"] = max(hi, st["avg_rate"])

    jit = _f(tail.get("jitter"))
    if jit is None:
        js = [x for x in (_f(r.get("jitter")) for r in recs) if x is not None]
        jit = (sum(js) / len(js)) if js else None
    st["jitter"] = jit

    lost = _i(tail.get("lost"))
    total = _i(tail.get("total"))
    lost_pct = _f(tail.get("lost_pct"))
    if lost is None:
        for r in reversed(recs):
            v = _i(r.get("lost"))
            if v is not None:
                lost = v
                total = _i(r.get("total"))
                lost_pct = _f(r.get("lost_pct"))
                break
    st["lost"] = lost
    st["total"] = total
    if lost_pct is None and lost is not None and total:
        lost_pct = lost * 100.0 / total
    st["lost_pct"] = lost_pct

    # 重传：iperf3 的 SUM 行是累计值，逐秒行是区间值，两者相加会重复计数。
    # 有 SUM 行就用 SUM 行（多方向 SUM 取最大值，避免发送/接收双份），否则累加逐秒行。
    retrs = [x for x in (_i(r.get("retr")) for r in sum_rows) if x is not None]
    if retrs:
        st["retr_sum"] = max(retrs)
    else:
        retrs = [x for x in (_i(r.get("retr")) for r in per_rows) if x is not None]
        st["retr_sum"] = sum(retrs) if retrs else None

    # 传输量：逐秒行相加才是本项总量。若有全程汇总行，直接取它（sender/receiver 两条取大值，
    # 不能相加，否则同一个 320MB 会被算成 640MB）。
    if sum_rows:
        trans = [x for x in (_f(r.get("transfer")) for r in sum_rows) if x is not None]
        st["transfer_sum"] = max(trans) if trans else None
    else:
        trans = [x for x in (_f(r.get("transfer")) for r in per_rows) if x is not None]
        st["transfer_sum"] = sum(trans) if trans else None
    return st


def _pick_full_span(recs):
    """从逐秒记录里挑出「全程汇总行」：区间长度明显大于常规采样间隔的那些。"""
    spans = []
    for r in recs:
        a, b = _f(r.get("start")), _f(r.get("end"))
        spans.append((b - a) if (a is not None and b is not None) else None)
    good = [x for x in spans if x is not None and x > 0]
    if len(recs) < 2 or not good:
        return []
    longest = max(good)
    if longest <= min(good) * 1.5:
        return []
    return [r for r, s in zip(recs, spans) if s is not None and s >= longest * 0.9]


def _build_test(res, index, records):
    """一条 results 元素 -> payload['tests'] 里的一项。"""
    res = res if isinstance(res, dict) else {}
    test_id = str(res.get("test_id") or res.get("test") or ("test%d" % (index + 1)))
    recs = res.get("records")
    if not isinstance(recs, list):
        recs = [r for r in records if str((r or {}).get("test") or "") == test_id]

    kind = str(res.get("kind") or ("UDP" if test_id.startswith("udp") else "TCP")).upper()
    params = dict(res.get("params") or {})
    params.setdefault("udp", kind == "UDP")
    params.setdefault("reverse", test_id.endswith("down"))

    summ = res.get("summary") if isinstance(res.get("summary"), dict) else {}
    sender = _rate_of(summ.get("sender")) or _rate_of(res.get("sender"))
    receiver = _rate_of(summ.get("receiver")) or _rate_of(res.get("receiver"))
    st = _stats(recs)
    if sender is None and st["avg_rate"] is not None:
        sender = {"rate": st["avg_rate"], "unit": st["unit"] or "Mbits/sec"}
    if receiver is None and st["avg_rate"] is not None:
        receiver = {"rate": st["avg_rate"], "unit": st["unit"] or "Mbits/sec"}

    return {
        "test_id": test_id,
        "name": str(res.get("name") or TEST_NAMES.get(test_id) or test_id),
        "kind": kind,
        "params": params,
        "sender": sender or {"rate": None, "unit": "Mbits/sec"},
        "receiver": receiver or {"rate": None, "unit": "Mbits/sec"},
        "records_n": len(recs),
        "stats": st,
        "ok": bool(res.get("ok", res.get("error") is None)),
        "error": str(res.get("error") or ""),
        "records": recs,
    }


def _link_mbps(devices, serial, up=True):
    """取某台手机的 WiFi 协商速率（Mbps）。找不到返回 None。"""
    for dev in devices:
        if serial and dev.get("serial") and str(dev.get("serial")) != str(serial):
            continue
        wifi = dev.get("wifi") or {}
        tx = _f(wifi.get("link_tx_mbps"))
        rx = _f(wifi.get("link_rx_mbps"))
        v = tx if up else rx
        if not v:
            v = tx or rx
        if v:
            return v
    return None


def _pick_serial(meta, test, devices, key):
    for src in (test.get("params") or {}, meta):
        v = src.get(key)
        if v:
            return str(v)
    return ""


def _verdict_tcp(test, meta, devices):
    up = test["test_id"] != "tcp_down"
    sender = test["sender"] or {}
    receiver = test["receiver"] or {}
    client = sender if up else receiver
    server = receiver if up else sender
    client_txt = fmt_bps(client.get("rate"), client.get("unit"))
    server_txt = fmt_bps(server.get("rate"), server.get("unit"))
    if client_txt == _EMPTY and server_txt == _EMPTY:
        return ""

    dir_cn = "上行（TX）" if up else "下行（RX，-R 反向）"
    verb = "接收" if up else "发送"
    line = "客户端%s %s，服务端%s %s" % (dir_cn, client_txt, verb, server_txt)

    serial = _pick_serial(meta, test, devices, "client_serial")
    link = _link_mbps(devices, serial, up)
    rate = _f(client.get("rate"))
    if link and rate is not None:
        unit_factor = _FACTORS.get(str(client.get("unit") or "Mbits/sec").strip().lower().replace(" ", ""), 1.0)
        pct = rate * unit_factor / link * 100.0
        if pct > 150:
            # 实测速率超过协商速率 1.5 倍，说明数据没走空口（同一台手机 loopback、
            # 或两端连的是同一个 AP 的以太回程），这时算百分比只会误导人
            line += "，超过协商速率 %s Mbps 的 %.0f%%，说明这段数据没走空口，比值无参考意义" % (_num(link), pct)
        else:
            line += "，达协商速率 %s Mbps 的 %.0f%%" % (_num(link), pct)
    return line + "。"


def _verdict_udp(test):
    st = test.get("stats") or {}
    sender = test["sender"] or {}
    receiver = test["receiver"] or {}
    rate_txt = fmt_bps(sender.get("rate"), sender.get("unit"))
    if rate_txt == _EMPTY:
        rate_txt = fmt_bps(receiver.get("rate"), receiver.get("unit"))
    if rate_txt == _EMPTY:
        return ""
    params = test.get("params") or {}
    limit = str(params.get("udp_bw") or "").strip()
    # 正向 / 反向各一行，不写方向两条结论看起来一模一样
    direction = "反向 -R" if str(test.get("test_id") or "").endswith("down") else "正向"
    head = "UDP %s · 限速 %s，实测 %s" % (direction, limit, rate_txt) \
        if limit else "UDP %s · 实测 %s" % (direction, rate_txt)
    jit = _f(st.get("jitter"))
    if jit is not None:
        head += "，抖动 %.3f ms" % jit
    lost = st.get("lost")
    total = st.get("total")
    if lost is not None and total:
        pct = st.get("lost_pct")
        if pct is None:
            pct = lost * 100.0 / total
        head += "，丢包 %d/%d（%.3f%%）" % (lost, total, pct)
    return head + "。"


def _verdict_long(test, meta):
    st = test.get("stats") or {}
    sender = test["sender"] or {}
    rate_txt = fmt_bps(sender.get("rate"), sender.get("unit"))
    if rate_txt == _EMPTY:
        return ""
    dur = (test.get("params") or {}).get("duration") or meta.get("duration")
    head = "长稳测试 %s 秒，平均 %s" % (_num(dur) or "?", rate_txt)
    if st.get("min_rate") is not None and st.get("max_rate") is not None:
        unit = sender.get("unit") or "Mbits/sec"
        head += "，最低 %s，最高 %s" % (fmt_bps(st["min_rate"], unit), fmt_bps(st["max_rate"], unit))
    if st.get("retr_sum") is not None:
        head += "，重传合计 %d" % st["retr_sum"]
    stall = _i(st.get("stall_n"))
    if stall:
        # 「最低 0.0 Mbits/sec」不解释就看不懂；说明是整秒停顿而不是采样误差
        head += "；其中 %d 秒完全停滞（0 bit/s），不是采样误差" % stall
    return head + "。"


def _build_screen_compare(tests, screen):
    """亮灭屏对比：从 tests 里的 screen_on / screen_off 取均值。"""
    out = {"available": False, "on": None, "off": None, "delta_pct": None, "verdict": ""}
    # 同一 test_id 可能出现多条（重跑/失败重试）。挑「成功且速率非零」的那条，
    # 否则会把失败重试的 0.0 当成亮屏/灭屏均值。
    by_id = {}
    rank = {}
    for t in tests:
        tid = t["test_id"]
        rec = (1 if t.get("ok") else 0,
               1 if _f((t.get("receiver") or {}).get("rate")) else 0,
               int(t.get("records_n") or 0))
        if tid not in by_id or rec > rank[tid]:
            by_id[tid] = t
            rank[tid] = rec

    def _rate(test):
        # 亮灭屏取「对端接收」速率（runner 用的是 _fmt_rate(res,'receiver')），
        # 取不到再依次退回 sender、逐秒均值，避免单向为 0 时误判。
        for slot in ("receiver", "sender"):
            slot_d = test.get(slot) or {}
            r = _f(slot_d.get("rate"))
            if r:
                return r, str(slot_d.get("unit") or "Mbits/sec")
        st = test.get("stats") or {}
        r = _f(st.get("avg_rate"))
        if r:
            return r, str(st.get("unit") or "Mbits/sec")
        return None, ""

    def _avg(test):
        if not test:
            return None
        r, unit = _rate(test)
        if r is None:
            return None
        return {"avg_rate": r, "unit": unit, "records_n": test.get("records_n", 0),
                "test_id": test["test_id"]}

    def _pick(x, tid):
        if x is None:
            return None
        v = _rate_of(x)
        if not v or not v.get("rate"):
            return None
        return {"avg_rate": v["rate"], "unit": v["unit"], "records_n": 0, "test_id": tid}

    def _mean(xs):
        # 0.0 是合法速率（该轮确实没流量），不能当缺失丢掉，否则均值被抬高。
        vals = [v for v in (_f(x) for x in (xs or [])) if v is not None]
        if not vals:
            return None
        return {"avg_rate": sum(vals) / len(vals), "unit": "Mbits/sec", "records_n": len(vals)}

    # runner.py 算好的 screen_compare 优先：它与控制台打印的数字必须一致，
    # 不能让报告自己另算一套。screen 缺失时才从 tests 反推。
    on = None
    off = None
    if isinstance(screen, dict):
        # runner.py 的 screen_compare 形状：{screen_on:[...], screen_off:[...],
        # on_avg, off_avg, delta_pct, seg_s, rounds}；也兼容 {on:{rate,unit}, off:{...}}
        on = _pick(screen.get("on"), "screen_on") or _pick(screen.get("on_avg"), "screen_on")
        if on is None:
            on = _mean(screen.get("screen_on"))
            if on:
                on["test_id"] = "screen_on"
        off = _pick(screen.get("off"), "screen_off") or _pick(screen.get("off_avg"), "screen_off")
        if off is None:
            off = _mean(screen.get("screen_off"))
            if off:
                off["test_id"] = "screen_off"
    if on is None:
        on = _avg(by_id.get("screen_on"))
    if off is None:
        off = _avg(by_id.get("screen_off"))
    if not on or not off:
        return out

    out["available"] = True
    out["on"] = on
    out["off"] = off
    a = on["avg_rate"] * _FACTORS.get(str(on["unit"]).strip().lower().replace(" ", ""), 1.0)
    b = off["avg_rate"] * _FACTORS.get(str(off["unit"]).strip().lower().replace(" ", ""), 1.0)
    if a > 0:
        delta = (b - a) * 100.0 / a
        out["delta_pct"] = delta
        on_txt = "%.1f Mbits/sec" % a
        off_txt = "%.1f Mbits/sec" % b
        if abs(delta) < 3:
            body = "灭屏后吞吐变化 %.1f%%（%s → %s），两者基本一致" % (delta, on_txt, off_txt)
        elif delta > 0:
            body = "灭屏后吞吐提升 %.1f%%（%s → %s），亮屏对链路存在可见影响" % (delta, on_txt, off_txt)
        else:
            body = "灭屏后吞吐下降 %.1f%%（%s → %s），亮屏并未成为瓶颈" % (-delta, on_txt, off_txt)
        out["verdict"] = body + "。"
    return out


def _norm_notes(notes):
    out = []
    for n in notes or []:
        if isinstance(n, dict):
            n = n.get("text") or n.get("msg") or n.get("message") or str(n)
        s = str(n).strip()
        if s and s not in out:
            out.append(s)
    return out


_ALIAS = {
    "脚本版本": "script_version", "版本": "script_version", "version": "script_version",
    "开始时间": "started_at", "结束时间": "finished_at",
    "时长": "duration", "时长参数": "duration", "duration_s": "duration", "sec": "duration",
    "流数": "streams", "并发流数": "streams",
    "反向": "reverse", "是否反向": "reverse",
    "iperf版本": "iperf_version", "iperf": "iperf_version",
    "PC环境": "pc_env", "pc": "pc_env",
    "测试项": "items", "运行目录": "run_dir", "端口": "port", "服务端口": "port",
}


def _norm_meta(meta_in):
    """补齐 meta 必备项：脚本版本 / 开始结束时间 / 时长 / 流数 / 反向 / PC 环境 / iperf 版本。"""
    now = _dt.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    m = {}
    for k, v in (meta_in or {}).items():
        m[_ALIAS.get(str(k), str(k))] = v
    # 调用方（cli.save_all）把时长/流数/方向放在 meta["params"] 里，这里摊平
    flat = m.get("params") if isinstance(m.get("params"), dict) else {}
    for k in ("duration", "streams", "reverse", "udp_bw", "port"):
        if m.get(k) in (None, "") and flat.get(k) not in (None, ""):
            m[k] = flat[k]

    m["tool"] = m.get("tool") or TOOL_NAME
    m["script_version"] = m.get("script_version") or SCRIPT_VERSION
    m["started_at"] = m.get("started_at") or now
    m["finished_at"] = m.get("finished_at") or m["started_at"]
    m["duration"] = m.get("duration")
    m["streams"] = m.get("streams")
    m["reverse"] = m.get("reverse")
    m["udp_bw"] = m.get("udp_bw") or ""
    m["items"] = m.get("items") or []
    m["pc_env"] = m.get("pc_env") if isinstance(m.get("pc_env"), dict) else {}
    m["iperf_version"] = m.get("iperf_version") or ""
    m["run_dir"] = m.get("run_dir") or ""
    m["port"] = m.get("port") or ""
    m["serial_client"] = m.get("serial_client") or m.get("client_serial") or ""
    m["serial_server"] = m.get("serial_server") or m.get("server_serial") or ""
    return m


def _pc_env_text(env):
    if not isinstance(env, dict) or not env:
        return _EMPTY
    parts = []
    if env.get("os"):
        parts.append(str(env["os"]))
    parts.append("无线网卡 " + ("有" if env.get("has_wireless") else "无"))
    if env.get("python"):
        parts.append("Python " + str(env["python"]))
    if env.get("admin") is not None:
        parts.append("管理员 " + ("是" if env.get("admin") else "否"))
    return " · ".join(parts) if parts else _EMPTY


def build_payload(*, meta, devices, records, results, screen=None, notes=None):
    """组装 JSON / Markdown 用的总结构（契约 4.4）。"""
    meta_n = _norm_meta(meta)
    devices = [d for d in (devices or []) if isinstance(d, dict)]
    if not meta_n.get("iperf_version"):
        for dev in devices:
            ver = (dev.get("iperf") or {}).get("version")
            if ver:
                meta_n["iperf_version"] = str(ver)
                break
    records = [r for r in (records or []) if isinstance(r, dict)]
    results = list(results or [])

    tests = [_build_test(r, i, records) for i, r in enumerate(results)]

    notes_out = _norm_notes(notes)

    # 自动提示
    pc = meta_n.get("pc_env") or {}
    if pc and pc.get("has_wireless") is False:
        notes_out.append("PC 端未检测到无线网卡，PC 参与的隧道测试（pc_tunnel）不可用。")
    for dev in devices:
        wifi = dev.get("wifi") or {}
        if wifi.get("connected") is False:
            notes_out.append("设备 %s 的 WiFi 未连接，SSID/频段/RSSI/协商速率等指标不可用。"
                             % _t(dev.get("serial"), "?"))
    for t in tests:
        if not t.get("ok") and t.get("error"):
            notes_out.append("[错误] 测试 %s 未成功：%s" % (t["test_id"], t["error"]))
        elif t.get("records_n") == 0:
            notes_out.append("测试 %s 未产生任何逐秒数据行。" % t["test_id"])
        st = t.get("stats") or {}
        pct = _f(st.get("lost_pct"))
        if pct is not None and pct > 1.0:
            notes_out.append("测试 %s 丢包率 %.3f%% 超过 1%%，链路可能存在干扰。"
                             % (t["test_id"], pct))
    notes_out = _norm_notes(notes_out)

    # 结论摘要
    verdicts = []
    for t in tests:
        tid = t["test_id"]
        if tid in ("screen_on", "screen_off"):
            continue
        if t["kind"] == "UDP":
            line = _verdict_udp(t)
        elif tid == "long_run":
            line = _verdict_long(t, meta_n)
        else:
            line = _verdict_tcp(t, meta_n, devices)
        if line:
            verdicts.append(line)

    screen_cmp = _build_screen_compare(tests, screen)
    if screen_cmp["available"]:
        verdicts.append("亮屏均值 %s，灭屏均值 %s。"
                        % (fmt_bps(screen_cmp["on"]["avg_rate"], screen_cmp["on"]["unit"]),
                           fmt_bps(screen_cmp["off"]["avg_rate"], screen_cmp["off"]["unit"])))

    failed = [t for t in tests if not t.get("ok")]
    tcp_pcts = []
    for t in tests:
        if t["kind"] != "TCP" or t["test_id"].startswith("screen_"):
            continue
        up = t["test_id"] != "tcp_down"
        client = t["sender"] if up else t["receiver"]
        rate = _f((client or {}).get("rate"))
        if rate is None:
            continue
        serial = _pick_serial(meta_n, t, devices, "client_serial")
        link = _link_mbps(devices, serial, up)
        if link:
            factor = _FACTORS.get(str(client.get("unit") or "Mbits/sec").strip().lower().replace(" ", ""), 1.0)
            pct = rate * factor / link * 100.0
            # 超过协商速率 1.5 倍说明这段流量没走空口（loopback / 同 AP 以太回程），
            # 这种样本不能参与「达到协商速率多少」的结论计算
            if 0 < pct <= 150:
                tcp_pcts.append(pct)

    # 「几项测试」按测试项算，不按段数算：亮灭屏对比会产出 4 段，
    # 写「本次 8 项测试全部完成」会让人以为跑了 8 个测试项。
    # 勾选但没产出结果的（如 pc_tunnel 起不来）单独提示，不能算进「全部完成」。
    asked = len(meta_n.get("items") or []) or len(tests)
    item_n = max(1, min(asked, len(tests)))
    scope = "%d 个测试项（共 %d 段）" % (item_n, len(tests)) if len(tests) > item_n else "%d 个测试项" % item_n
    if asked > item_n:
        scope += "（另有 %d 个测试项未产生结果）" % (asked - item_n)

    if not tests:
        conclusion = "结论：本次没有产生任何测试结果。"
    elif failed:
        conclusion = "结论：本次 %s，其中 %d 段未成功，详见「六、异常与提示」。" % (scope, len(failed))
    elif tcp_pcts:
        worst = min(tcp_pcts)
        best = max(tcp_pcts)
        # 上下行差距大时只报最差值会与逐项结论打架，把区间写出来更实在
        rng = "%.0f%%~%.0f%%" % (worst, best) if best - worst >= 5 else "%.0f%%" % worst
        if worst >= 70:
            conclusion = "结论：本次 %s，吞吐达到协商速率的 %s，链路状态良好。" % (scope, rng)
        elif worst >= 40:
            conclusion = "结论：本次 %s，吞吐达协商速率的 %s，存在优化空间。" % (scope, rng)
        else:
            conclusion = "结论：本次 %s，吞吐仅达协商速率的 %s，建议检查信道干扰、距离与后台占用。" % (scope, rng)
    else:
        conclusion = "结论：本次 %s，全部完成。" % scope

    summary = {
        "conclusion": conclusion,
        "verdicts": verdicts,
        "test_count": len(tests),
        "record_count": len(records),
        "failed_count": len(failed),
        # 勾了却没产出结果的测试项也算没跑完，否则 ok=true 会让人以为全都测了
        "ok": bool(tests) and not failed and asked <= item_n,
        "item_count": item_n,
        "asked_count": asked,
        "best_tcp_pct": max(tcp_pcts) if tcp_pcts else None,
    }

    return {
        "meta": meta_n,
        "devices": devices,
        "summary": summary,
        "tests": tests,
        "screen_compare": screen_cmp,
        "notes": notes_out,
        "records": records,
    }


# ---------------------------------------------------------------- Markdown

def _params_line(test, meta):
    p = test.get("params") or {}
    bits = []
    dur = p.get("duration") or meta.get("duration")
    if dur:
        bits.append("时长 %s 秒" % _num(dur))
    streams = p.get("streams") or meta.get("streams")
    if streams:
        bits.append("%s 流" % _num(streams))
    bits.append("反向（-R）" if p.get("reverse") else "正向")
    bits.append(test.get("kind") or "")
    if p.get("udp") and p.get("udp_bw"):
        bits.append("UDP 限速 %s" % p["udp_bw"])
    if p.get("port"):
        bits.append("端口 %s" % _num(p["port"]))
    return " · ".join([b for b in bits if b])


def _detail_row(rec):
    span = ""
    if _f(rec.get("start")) is not None and _f(rec.get("end")) is not None:
        span = "%s-%s" % (_f2(rec.get("start")), _f2(rec.get("end")))
    return [
        span or _t(rec.get("stream")),
        _num(rec.get("transfer"), 3),
        _t(rec.get("unit")),
        _num(rec.get("rate"), 3),
        _t(rec.get("rate_unit")),
        _t(rec.get("retr")) if _s(rec.get("retr")) != "" else _t(rec.get("total")),
        _num(rec.get("jitter"), 3),
        _t(rec.get("lost")),
        _t(rec.get("total")),
        _num(rec.get("lost_pct"), 3),
    ]


_DETAIL_HEAD = ["区间", "传输量", "单位", "速率", "速率单位", "重传/数据报", "抖动ms", "丢包", "总包", "丢包率"]


def _render_test_section(idx, test, meta):
    out = []
    out.append("### 4.%d %s · `%s`" % (idx, test["name"], test["test_id"]))
    out.append("")
    out.append("参数：%s" % _params_line(test, meta))
    out.append("")
    sender = test.get("sender") or {}
    receiver = test.get("receiver") or {}
    out.append("发送端 %s　·　接收端 %s"
               % (fmt_bps(sender.get("rate"), sender.get("unit")),
                  fmt_bps(receiver.get("rate"), receiver.get("unit"))))
    out.append("")

    recs = test.get("records") or []
    st = test.get("stats") or {}
    if not recs:
        out.append("> 本轮没有逐秒数据行，只有汇总结果。")
        out.append("")
        if test.get("error"):
            out.append("> [错误] %s" % test["error"])
            out.append("")
        return out

    sum_rows = [r for r in recs if str(r.get("stream") or "").upper() == "SUM"]
    multi_sum = len(sum_rows) > 1
    rows = []
    for r in recs[:10]:
        row = _detail_row(r)
        if multi_sum and str(r.get("stream") or "").upper() == "SUM":
            role = {"sender": "发送端", "receiver": "接收端"}.get(str(r.get("role") or ""), "")
            if role:
                row[0] = "%s · %s" % (row[0], role)
        rows.append(row)
    unit = st.get("unit") or sender.get("unit") or "Mbits/sec"
    stat_row = [
        "**统计（共 %d 行）**" % st.get("n", len(recs)),
        _num(st.get("transfer_sum"), 3),
        _t(st.get("transfer_unit")),
        "**%s**" % (_num(st.get("avg_rate"), 3) or _EMPTY),
        _t(unit),
        _t(st.get("retr_sum")) if st.get("retr_sum") is not None else _t(st.get("total")),
        ("%.3f" % st["jitter"]) if st.get("jitter") is not None else "",
        _t(st.get("lost")),
        _t(st.get("total")),
        ("%.3f" % st["lost_pct"]) if st.get("lost_pct") is not None else "",
    ]
    rows.append(stat_row)
    out.append(_md_table(_DETAIL_HEAD, rows))
    out.append("")
    if len(recs) > 10:
        out.append("> 逐秒明细共 %d 行，此处仅列前 10 行；完整数据见 `逐秒明细.csv`。" % len(recs))
    bits = []
    if st.get("avg_rate") is not None:
        bits.append("平均 %s" % fmt_bps(st["avg_rate"], unit))
    if st.get("min_rate") is not None:
        bits.append("最低 %s" % fmt_bps(st["min_rate"], unit))
    if st.get("max_rate") is not None:
        bits.append("最高 %s" % fmt_bps(st["max_rate"], unit))
    if st.get("retr_sum") is not None:
        bits.append("重传合计 %d" % st["retr_sum"])
    if bits:
        out.append("> 统计：%s。" % "，".join(bits))
    out.append("")
    return out


def _device_rows(devices):
    rows = []
    rows.append(["型号", *[_cell(d.get("model")) for d in devices]])
    rows.append(["品牌", *[_cell(d.get("brand")) for d in devices]])
    rows.append(["序列号", *[_cell(d.get("serial")) for d in devices]])
    rows.append(["系统版本", *[_cell(("Android " + str(d.get("android"))) if d.get("android") else None)
                           for d in devices]])
    rows.append(["SDK", *[_cell(d.get("sdk")) for d in devices]])
    rows.append(["ABI", *[_cell(d.get("abi")) for d in devices]])
    rows.append(["屏幕", *[_cell(d.get("screen")) for d in devices]])

    def _batt(d):
        b = d.get("battery") or {}
        if not b:
            return None
        lv = b.get("level")
        parts = []
        if lv is not None:
            parts.append("%s%%" % _num(lv))
        if b.get("status"):
            parts.append(str(b["status"]))
        if b.get("temp_c") is not None:
            parts.append("%s℃" % _num(b.get("temp_c"), 1))
        return " · ".join(parts) or None

    rows.append(["电量", *[_cell(_batt(d)) for d in devices]])
    rows.append(["内存", *[_cell(("%s MB" % _num(d.get("ram_mb"))) if d.get("ram_mb") else None)
                        for d in devices]])
    rows.append(["存储可用", *[_cell(("%s GB" % _num(d.get("storage_free_gb"), 1))
                               if d.get("storage_free_gb") is not None else None) for d in devices]])
    rows.append(["CPU 核心", *[_cell(d.get("cores")) for d in devices]])
    rows.append(["WiFi 开关", *[_cell((d.get("wifi") or {}).get("enabled")) for d in devices]])
    rows.append(["WiFi 连接", *[_cell((d.get("wifi") or {}).get("connected")) for d in devices]])
    rows.append(["SSID", *[_cell((d.get("wifi") or {}).get("ssid")) for d in devices]])
    rows.append(["BSSID", *[_cell((d.get("wifi") or {}).get("bssid")) for d in devices]])
    rows.append(["频段", *[_cell((d.get("wifi") or {}).get("band")) for d in devices]])
    rows.append(["频率", *[_cell(("%s MHz" % _num((d.get("wifi") or {}).get("freq_mhz")))
                             if (d.get("wifi") or {}).get("freq_mhz") else None) for d in devices]])
    rows.append(["RSSI", *[_cell(("%s dBm" % _num((d.get("wifi") or {}).get("rssi")))
                             if (d.get("wifi") or {}).get("rssi") is not None else None)
                           for d in devices]])

    def _link(d):
        w = d.get("wifi") or {}
        tx, rx = w.get("link_tx_mbps"), w.get("link_rx_mbps")
        if tx is None and rx is None:
            return None
        return "TX %s / RX %s Mbits/sec" % (_num(tx) or _EMPTY, _num(rx) or _EMPTY)

    rows.append(["协商速率", *[_cell(_link(d)) for d in devices]])
    rows.append(["802.11", *[_cell((d.get("wifi") or {}).get("standard")) for d in devices]])

    def _ip(d):
        addrs = d.get("addrs") or []
        parts = []
        for a in addrs:
            if isinstance(a, dict) and a.get("ip"):
                parts.append("%s %s/%s" % (a.get("iface") or "?", a["ip"], _num(a.get("prefix")) or "?"))
        return " · ".join(parts) or None

    rows.append(["IP 地址", *[_cell(_ip(d)) for d in devices]])

    def _gw(d):
        r = d.get("default_route") or {}
        if not r.get("gw"):
            return None
        return "%s (%s)" % (r.get("gw"), r.get("iface") or "?")

    rows.append(["默认网关", *[_cell(_gw(d)) for d in devices]])

    def _hot(d):
        h = d.get("hotspot") or {}
        if not h.get("on"):
            return "关闭"
        parts = ["开启"]
        if h.get("ssid"):
            parts.append("SSID=%s" % h["ssid"])
        if h.get("ip"):
            parts.append("IP=%s" % h["ip"])
        parts.append("客户端 %s" % _num(h.get("clients")))
        return " · ".join(parts)

    rows.append(["热点", *[_cell(_hot(d)) for d in devices]])

    def _iperf(d):
        ip = d.get("iperf") or {}
        if not ip.get("ok"):
            return ip.get("version") or "未安装"
        return "%s（%s）" % (ip.get("version") or _EMPTY, ip.get("path") or _EMPTY)

    rows.append(["iperf3", *[_cell(_iperf(d)) for d in devices]])
    return rows


def render_markdown(payload):
    """把 payload 渲染成完整 Markdown 文本（write_markdown 的内核，便于单测）。"""
    payload = payload if isinstance(payload, dict) else {}
    meta = payload.get("meta") or {}
    devices = [d for d in (payload.get("devices") or []) if isinstance(d, dict)]
    tests = [t for t in (payload.get("tests") or []) if isinstance(t, dict)]
    summary = payload.get("summary") or {}
    screen_cmp = payload.get("screen_compare") or {}
    notes = _norm_notes(payload.get("notes"))

    out = []
    out.append("# WiFi 吞吐量测试报告")
    out.append("")
    head = ["生成时间 %s" % _t(meta.get("finished_at") or meta.get("started_at"), "-"),
            "脚本版本 %s" % _t(meta.get("script_version"), "-")]
    if meta.get("run_dir"):
        head.append("运行目录 `%s`" % os.path.basename(str(meta["run_dir"])))
    out.append("> " + "　·　".join(head))
    out.append("")

    # 一、结论摘要
    out.append("## 一、结论摘要")
    out.append("")
    verdicts = [v for v in (summary.get("verdicts") or []) if v]
    if verdicts:
        for v in verdicts:
            out.append("- " + v)
    else:
        out.append("- 本次没有产生可判定的测试结果。")
    if summary.get("conclusion"):
        out.append("- **%s**" % summary["conclusion"])
    out.append("")

    # 二、测试参数
    out.append("## 二、测试参数")
    out.append("")
    items = meta.get("items") or []
    if items:
        item_txt = "、".join("%s（%s）" % (ITEM_NAMES.get(str(i), str(i)), str(i)) for i in items)
    else:
        seen = []
        for t in tests:
            if t.get("test_id") not in seen:
                seen.append(t.get("test_id"))
        item_txt = "、".join([str(x) for x in seen if x]) or _EMPTY
    rows = [
        ["测试项", _cell(item_txt)],
        ["开始时间", _cell(_t(meta.get("started_at")))],
        ["结束时间", _cell(_t(meta.get("finished_at")))],
        ["时长参数", _cell(("每轮 %s 秒" % _num(meta.get("duration"))) if meta.get("duration") else None)],
        ["并发流数", _cell(_num(meta.get("streams")) if meta.get("streams") else None)],
        ["方向", _cell("反向（-R，服务端→客户端）" if meta.get("reverse") else "正向（客户端→服务端）")],
    ]
    if meta.get("udp_bw"):
        rows.append(["UDP 限速", _cell(meta.get("udp_bw"))])
    if meta.get("port"):
        rows.append(["服务端口", _cell(_num(meta.get("port")))])
    rows.append(["iperf3 版本", _cell(meta.get("iperf_version"))])
    rows.append(["PC 环境", _cell(_pc_env_text(meta.get("pc_env")))])
    out.append(_md_table(["参数", "值"], rows))
    out.append("")

    # 三、设备信息
    out.append("## 三、设备信息")
    out.append("")
    if devices:
        heads = ["项目"]
        for i, d in enumerate(devices):
            name = _t(d.get("model"), "未知型号")
            serial = _t(d.get("serial"), "未知序列号")
            heads.append("设备 %d · %s · %s" % (i + 1, name, serial))
        out.append(_md_table(heads, _device_rows(devices)))
    else:
        out.append("> 本次未采集到设备信息。")
    out.append("")

    # 四、测试结果
    out.append("## 四、测试结果")
    out.append("")
    if tests:
        for i, t in enumerate(tests, 1):
            out.extend(_render_test_section(i, t, meta))
    else:
        out.append("> 本次未产生测试结果。")
        out.append("")

    # 五、亮灭屏对比
    out.append("## 五、亮灭屏对比")
    out.append("")
    if screen_cmp.get("available"):
        on = screen_cmp.get("on") or {}
        off = screen_cmp.get("off") or {}
        delta = screen_cmp.get("delta_pct")
        delta_txt = ("%+.1f%%（灭屏相对亮屏）" % delta) if delta is not None else _EMPTY
        out.append(_md_table(["项目", "吞吐量"], [
            ["亮屏均值", _cell(fmt_bps(on.get("avg_rate"), on.get("unit")))],
            ["灭屏均值", _cell(fmt_bps(off.get("avg_rate"), off.get("unit")))],
            ["变化百分比", _cell(delta_txt)],
        ]))
        out.append("")
        if screen_cmp.get("verdict"):
            out.append("**%s**" % screen_cmp["verdict"])
        out.append("")
    else:
        out.append("> 本次未执行亮灭屏对比测试（缺少 screen_on / screen_off 数据）。")
        out.append("")

    # 六、异常与提示
    out.append("## 六、异常与提示")
    out.append("")
    if notes:
        for n in notes:
            out.append("- " + n)
    else:
        out.append("- 无异常，测试全程未发现需要处理的问题。")
    out.append("")

    return "\n".join(out)


def write_markdown(path, payload):
    """写 report.md：utf-8 无 BOM，六节固定结构。"""
    path = os.path.abspath(str(path))
    parent = os.path.dirname(path)
    if parent:
        os.makedirs(parent, exist_ok=True)
    text = render_markdown(payload)
    with open(path, "w", encoding="utf-8", newline="\n") as fp:
        fp.write(text)

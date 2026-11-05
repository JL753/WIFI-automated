# -*- coding: utf-8 -*-
"""通过 USB（adb）把手机上的历史记录拉回电脑。

手机端每次测试都会把历史镜像一份到
/sdcard/Android/data/com.wifitput.android/files/history（一条记录一个 JSON），
那是应用自己的外部目录，adb 不需要 root 就能读，这条路就是整个 USB 导出的基础。

这里只做「找 adb → 拉文件 → 解析」三件事，落库和出报告由 context.Ctx 负责，
界面只管调用。所有失败一律抛 UsbError，消息是能直接贴进对话框的中文。
"""

import json
import os
import re
import shutil
import subprocess
import tempfile
import uuid

PACKAGE = "com.wifitput.android"
REMOTE_DIR = "/sdcard/Android/data/%s/files/history" % PACKAGE

# PATH 里找不到 adb 时按这个绝对路径兜底（这台机器的实际安装位置）
FALLBACK_ADB = r"C:\Program Files\ADB\adb.exe"

# adb 自己也有卡住的时候，探测和拉取都要有上限
SHORT_TIMEOUT = 30
PULL_TIMEOUT = 120

# 服务端历史里没有结果包，只能从日志还原。日志里的数据行是 App 的
# Record.pretty() 输出（不是 iperf3 原始行），形如：
#   #5   0-1s           14.8 MBytes       124 Mb/s
#   SUM  0-30.16s        376 MBytes       105 Mb/s
_ROW_RE = re.compile(
    r"^(?P<stream>\S+)\s+"
    r"(?P<start>\d+(?:\.\d+)?)-(?P<end>\d+(?:\.\d+)?)s\s+"
    r"(?P<transfer>\d+(?:\.\d+)?)\s+(?P<unit>GBytes|MBytes|KBytes|Bytes)\s+"
    r"(?P<rate>\d+(?:\.\d+)?)\s+(?P<ru>b/s|Kb/s|Mb/s|Gb/s|Tb/s)"
    r"(?:\s+(?P<rest>.*))?$"
)
_ROW_JITTER = re.compile(r"jitter\s+(\d+(?:\.\d+)?)\s*ms")
_ROW_LOST = re.compile(r"lost\s+(\d+)(?:/(\d+))?(?:\s*\(([\d.]+)%\))")
_ROW_RETR = re.compile(r"retr\s+(\d+)")

# Record.shortUnit() 把速率单位缩写过，还原成 record 里的全称
_SHORT_UNIT = {"b/s": "bits/sec", "Kb/s": "Kbits/sec", "Mb/s": "Mbits/sec",
               "Gb/s": "Gbits/sec", "Tb/s": "Tbits/sec"}
# 结果包的 receiver.unit 统一用 Mbits/sec，所以按这个表换算
_UNIT_TO_MBPS = {"bits/sec": 1e-6, "Kbits/sec": 1e-3, "Mbits/sec": 1.0,
                 "Gbits/sec": 1e3, "Tbits/sec": 1e6}

_LOG_CLOCK = re.compile(r"^(\d{1,2}:\d{2}:\d{2})\s*\|\s*(.*)$")
_PORT_SUMMARY = re.compile(r"端口\s*(\d+)")
_PORT_LOCAL = re.compile(r"port\s+(\d+)\s+connected")


class UsbError(RuntimeError):
    """USB 导出链路上的可预期错误，消息直接给用户看。"""


def find_adb():
    """返回 adb 可执行文件路径。优先 PATH，其次固定安装位置。"""
    found = shutil.which("adb")
    if found:
        return found
    if os.path.isfile(FALLBACK_ADB):
        return FALLBACK_ADB
    raise UsbError("没找到 adb，请先安装 Android 平台工具并加进 PATH")


def _probe(args, timeout=SHORT_TIMEOUT):
    """跑一条 adb 命令，返回 (是否成功, stdout, stderr)，不抛异常。

    注意 adb 把不少正常信息写到 stderr（如 "1 file pulled"），
    所以判定成败只看返回码，不看哪一路有字。
    """
    try:
        proc = subprocess.run(args, capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        raise UsbError("adb 命令超时（%d 秒）：%s" % (timeout, " ".join(args)))
    except OSError as exc:
        raise UsbError("adb 启动失败：%s" % exc)
    return proc.returncode == 0, _text(proc.stdout), _text(proc.stderr)


def _run(args, timeout=SHORT_TIMEOUT):
    """跑一条必须成功的 adb 命令，失败抛 UsbError，消息里带上 adb 原始输出。"""
    ok, out, err = _probe(args, timeout=timeout)
    if not ok:
        detail = (err or out or "（没有任何输出）").strip()
        raise UsbError("adb 出错：%s" % detail)
    return out, err


def _text(raw):
    """adb 的输出编码在 Windows 上不一定是什么，坏字节一律替换掉。"""
    if raw is None:
        return ""
    if isinstance(raw, bytes):
        return raw.decode("utf-8", "replace")
    return str(raw)


def adb_version(adb):
    """返回 adb version 的第一行，用来在日志里记一笔。"""
    out, err = _run([adb, "version"])
    line = (out or err or "").strip().splitlines()
    if not line:
        raise UsbError("adb version 没有任何输出：%s" % (err or out))
    return line[0].strip()


def list_devices(adb):
    """返回 [{"serial", "state"}]，包含 offline/unauthorized 的设备。

    上层要用 state 区分「没插」「没授权」「可用」，所以这里不替它过滤。
    """
    out, _err = _run([adb, "devices"])
    items = []
    for line in out.splitlines():
        line = line.strip()
        if not line or line.startswith("List of devices"):
            continue
        # 正常行是「序列号+制表符+状态」，个别情况带附加描述，多取的分片拼回状态
        parts = line.split("\t") if "\t" in line else line.split()
        if len(parts) < 2:
            continue
        serial = parts[0].strip()
        state = " ".join(p.strip() for p in parts[1:]).strip()
        if serial:
            items.append({"serial": serial, "state": state})
    return items


def default_serial(devices):
    """挑一个默认设备：只有一个可用就用它，多个取第一个可用的，没有返回空串。"""
    if not devices:
        return ""
    ready = [d for d in devices if str(d.get("state") or "") == "device"]
    if ready:
        return str(ready[0].get("serial") or "")
    return ""


def pull_history(adb, serial, dest_dir):
    """把手机上的历史目录整个拉到 dest_dir，返回落点与文件数。

    两段式：先 pull 到一个纯 ASCII 的临时目录，再用 Python 拷进 dest_dir。
    原因是 adb 在 Windows 上无法在含非 ASCII 字符的目标路径下建目录，
    实测报 "Illegal byte sequence"（返回码 1，"pull: building file list..." 后中断）；
    而本工具的根目录本身就叫「PC端工具」，所以 dest_dir 一定带中文，
    只能让 adb 往纯 ASCII 路径写，再用 os/shutil（走宽字符接口，不受这个限制）搬过来。
    探测那一步只读手机、不落本地目录，不受影响，保持原样。
    """
    if not serial:
        raise UsbError("没有可用的手机，请先用 USB 连上并打开 USB 调试")
    # 先探测：目录不存在时 ls 会失败，这时给一句能照做的提示，而不是甩 adb 的报错
    ok, out, _err = _probe([adb, "-s", serial, "shell", "ls", REMOTE_DIR])
    if not ok or not (out or "").strip():
        raise UsbError("手机上还没有历史记录，先跑一次测试再来导出。"
                       "（手机端查看位置：%s）" % REMOTE_DIR)

    try:
        os.makedirs(dest_dir, exist_ok=True)
    except OSError as exc:
        raise UsbError("创建导出目录失败：%s（%s）" % (dest_dir, exc))

    tmp_dir = make_ascii_temp_dir()
    try:
        # adb pull 成功时会把 "1 file pulled" 打到 stderr，返回码才是判据
        out2, pull_err = _run([adb, "-s", serial, "pull", REMOTE_DIR, tmp_dir],
                              timeout=PULL_TIMEOUT)
        output = (pull_err or out2 or "").strip()
        # adb 在目标目录已存在时会再套一层 history，两处都看一遍再拷
        pulled = 0
        for src in json_files(_pick_dest(tmp_dir)):
            try:
                shutil.copy2(src, os.path.join(dest_dir, os.path.basename(src)))
            except OSError as exc:
                raise UsbError("拷贝导出文件失败：%s（%s）" % (src, exc))
            pulled += 1
    finally:
        shutil.rmtree(tmp_dir, ignore_errors=True)
    return {"dest": dest_dir, "pulled": pulled, "output": output}


def _is_ascii_path(path):
    """路径能不能编成 ASCII —— adb 在 Windows 上只认这种目标目录。"""
    try:
        str(path).encode("ascii")
    except UnicodeEncodeError:
        return False
    return True


def _temp_roots():
    """候选临时根目录，去重后按尝试顺序返回。

    顺序：tempfile 认定的目录 → TEMP → TMP → 系统盘 Windows 目录下的 Temp。
    前三个都可能被设成中文路径（比如用户名是中文时），所以要留最后那个兜底。
    """
    windir = os.environ.get("windir") or os.environ.get("SystemRoot")
    if not windir:
        windir = (os.environ.get("SystemDrive") or "C:") + os.sep + "Windows"
    candidates = [tempfile.gettempdir(), os.environ.get("TEMP"),
                  os.environ.get("TMP"), os.path.join(windir, "Temp")]
    roots, seen = [], set()
    for item in candidates:
        if not item:
            continue
        root = os.path.abspath(item)
        key = os.path.normcase(root)
        if key in seen:
            continue
        seen.add(key)
        roots.append(root)
    return roots


def _can_nest(path):
    """目录里能不能再建子目录 —— adb pull 要在落点下面建 history。"""
    probe = os.path.join(path, "_w")
    try:
        os.makedirs(probe, exist_ok=True)
    except OSError:
        return False
    shutil.rmtree(probe, ignore_errors=True)
    return True


def make_ascii_temp_dir():
    """建一个路径全 ASCII、且真能往下建子目录的临时目录，给 adb pull 当落点。

    逐个候选根目录试，每个根目录先试 tempfile.mkdtemp()，不行再退化成自己
    os.makedirs 的唯一目录：Windows 上 mkdtemp 建出来的目录会带一个只授权
    当前用户的 DACL，某些受限制的运行环境（实测本机沙箱）连自己都写不进去，
    adb 子进程更是直接 "Permission denied"，所以建完还要真建一次子目录验证。
    纯 ASCII 的检查同样必要：tempfile.gettempdir() 在用户名含中文时返回的就是
    中文路径，adb 那边会 "Illegal byte sequence"。
    """
    tried = []
    for root in _temp_roots():
        tried.append(root)
        if not _is_ascii_path(root):
            continue
        try:
            os.makedirs(root, exist_ok=True)
        except OSError:
            continue
        try:
            path = tempfile.mkdtemp(prefix="wifitput_usb_", dir=root)
        except OSError:
            path = ""
        if path and _is_ascii_path(path) and _can_nest(path):
            return path
        if path:
            shutil.rmtree(path, ignore_errors=True)
        # mkdtemp 的目录权限受限时，自己建一个普通目录顶上
        path = os.path.join(root, "wifitput_usb_%s" % uuid.uuid4().hex[:12])
        try:
            os.makedirs(path, exist_ok=True)
        except OSError:
            continue
        if _is_ascii_path(path) and _can_nest(path):
            return path
        shutil.rmtree(path, ignore_errors=True)
    raise UsbError("找不到纯 ASCII 的临时目录来中转 adb 拉取，已试过：%s。"
                   "请把 TEMP 环境变量指到一个纯英文路径后重试。"
                   % "；".join(tried))


def _pick_dest(dest_dir):
    """dest_dir 和 dest_dir/history 哪个装着 .json 就返回哪个。"""
    if json_files(dest_dir):
        return dest_dir
    nested = os.path.join(dest_dir, "history")
    if json_files(nested):
        return nested
    # 一个 json 都没有时仍要返回一个可读的目录，优先嵌套那层（pull 的常态结果）
    return nested if os.path.isdir(nested) else dest_dir


def json_files(directory):
    """目录下所有 .json 文件的完整路径（不递归，历史就是平铺的）。"""
    if not os.path.isdir(directory):
        return []
    out = []
    for name in sorted(os.listdir(directory)):
        if not name.lower().endswith(".json"):
            continue
        p = os.path.join(directory, name)
        if os.path.isfile(p):
            out.append(p)
    return out


def parse_records(paths):
    """读一批历史 JSON，坏文件跳过并记下原因。

    单条记录的 bundle 字段是「结果包 JSON 的字符串」，不是对象，
    这里顺手解析成 dict；解析不了就留 None，由 pick_bundles 归到跳过项。
    服务端记录没有 bundle，但 summary / log 里留着人看的文本，也一并取出来。
    """
    records = []
    for path in paths:
        item = {"path": path, "id": "", "title": "", "mode": "", "role": "",
                "started_at": "", "finished_at": "", "bundle": None, "error": "",
                "summary": "", "log": ""}
        base_id = os.path.splitext(os.path.basename(path))[0]
        item["id"] = base_id
        try:
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f)
        except (OSError, ValueError) as exc:
            item["error"] = "读取失败：%s" % exc
            records.append(item)
            continue
        if not isinstance(data, dict):
            item["error"] = "不是 JSON 对象"
            records.append(item)
            continue

        # 文件名才是 id 的权威来源，文件可能被手工改过
        item["id"] = str(data.get("id") or base_id)
        item["title"] = str(data.get("title") or "")
        item["mode"] = str(data.get("mode") or "")
        item["role"] = str(data.get("role") or "")
        item["started_at"] = str(data.get("started_at") or "")
        item["finished_at"] = str(data.get("finished_at") or "")
        item["summary"] = str(data.get("summary") or "")
        item["log"] = str(data.get("log") or "")

        raw = data.get("bundle")
        if isinstance(raw, dict):
            item["bundle"] = raw
        elif isinstance(raw, str):
            if raw.strip():
                try:
                    parsed = json.loads(raw)
                except ValueError as exc:
                    item["error"] = "结果包解析失败：%s" % exc
                else:
                    if isinstance(parsed, dict):
                        item["bundle"] = parsed
                    else:
                        item["error"] = "结果包不是 JSON 对象"
            # 空串是正常情况：服务端记录本来就没有结果包
        elif raw is not None:
            item["error"] = "结果包字段类型不支持：%s" % type(raw).__name__
        records.append(item)
    return records


def _log_rows(item):
    """扫日志，返回 (逐行 record 列表, 全程汇总行的 record)。"""
    rows, full = [], None
    longest = 0.0
    for raw in (item.get("log") or "").splitlines():
        line = raw.strip()
        if not line:
            continue
        mm = _LOG_CLOCK.match(line)
        clock, body = (mm.group(1), mm.group(2).strip()) if mm else ("", line)
        # 去掉 App 打的 "-- " / "OK " 前缀，剩下的才是 Record.pretty() 的文本
        if body[:2] in ("--", "OK"):
            body = body[2:].strip()
        mo = _ROW_RE.match(body)
        if not mo:
            continue
        ru = _SHORT_UNIT.get(mo.group("ru"), "")
        if not ru:
            continue
        rest = mo.group("rest") or ""
        lost = total = lost_pct = None
        ml = _ROW_LOST.search(rest)
        if ml:
            lost = int(ml.group(1))
            total = int(ml.group(2)) if ml.group(2) else None
            lost_pct = float(ml.group(3)) if ml.group(3) else None
        mj = _ROW_JITTER.search(rest)
        mr = _ROW_RETR.search(rest)
        start = float(mo.group("start"))
        end = float(mo.group("end"))
        rec_row = {
            "t": clock,
            "elapsed": round(end, 2),
            "test": "",
            "kind": "",
            "stream": mo.group("stream"),
            "start": start,
            "end": end,
            "transfer": float(mo.group("transfer")),
            "unit": mo.group("unit"),
            "rate": float(mo.group("rate")),
            "rate_unit": ru,
            "retr": int(mr.group(1)) if mr else None,
            "jitter": float(mj.group(1)) if mj else None,
            "lost": lost,
            "total": total,
            "lost_pct": lost_pct,
            "role": "receiver",
            "raw": body,
        }
        rows.append(rec_row)
        # 区间明显长于常规逐秒行的那条就是全程汇总行（含 summary 里重复的那行）
        if (end - start) > longest:
            longest = end - start
            full = rec_row
    if full is not None and len(rows) > 1 and longest <= 1.5:
        full = None          # 只有 1 秒的数据，不存在「全程」这种事
    return rows, full


def _port_of(item):
    """从 summary / 日志里抓服务端监听端口，抓不到返回 None。"""
    for text, pat in ((item.get("summary") or "", _PORT_SUMMARY),
                      (item.get("log") or "", _PORT_LOCAL),
                      (item.get("log") or "", _PORT_SUMMARY)):
        mo = pat.search(text)
        if mo:
            return int(mo.group(1))
    return None


def synth_bundle(item):
    """把一条服务端历史还原成最小结果包；日志里没有数据行就返回 None。

    服务端只看得见接收侧，所以只填 receiver，不编 sender。devices 也不编，
    报告里就不会出现「达协商速率 X%」那句 —— 服务端记录本来也没有这个前提。
    """
    rows, full = _log_rows(item)
    if not rows:
        return None

    # UDP 才带 jitter / lost，这两个字段出现就说明是 UDP
    is_udp = any(r["jitter"] is not None or r["lost"] is not None for r in rows)
    test_id = "udp_up" if is_udp else "tcp_up"
    kind = "UDP" if is_udp else "TCP"
    mode = str(item.get("mode") or "").strip().lower()
    item_id = "hotspot_tput" if mode == "hotspot" else "wifi_tput"

    for r in rows:
        r["test"] = test_id
        r["kind"] = kind

    # 全程汇总行留在 records 里不动：单流时 iperf3 不打 [SUM]，报告靠「区间最长」
    # 把它认成汇总行（_pick_full_span），排掉它反而会让平均值退化成最后一秒的数。
    tail = full or rows[-1]
    mbps = tail["rate"] * _UNIT_TO_MBPS.get(tail["rate_unit"], 1.0)
    port = _port_of(item)

    params = {"mode": mode}
    if port is not None:
        params["port"] = port

    name = "%s 正向（服务端接收侧）" % kind

    meta = {"items": [item_id]}
    if mode:
        meta["mode"] = mode
    if port is not None:
        meta["port"] = port

    return {
        "schema": "wifitput.result/1",
        "run": {
            "id": item.get("id") or "",
            "mode": mode,
            "started_at": item.get("started_at") or "",
            "finished_at": item.get("finished_at") or "",
        },
        "meta": meta,
        "devices": [],
        "results": [{
            "test_id": test_id,
            "name": name,
            "kind": kind,
            "params": params,
            # sender 显式给 None，而不是整个键省掉：省掉的话报告渲染会拿接收侧
            # 平均速率回落到发送侧，结论里就凭空多出一个「客户端上行 105 Mbits/sec」。
            "sender": {"rate": None, "unit": "Mbits/sec"},
            "receiver": {"rate": round(mbps, 3), "unit": "Mbits/sec"},
            "records": rows,
        }],
        "records": rows,
        "screen": None,
        "notes": [
            "本报告由 PC 端从手机服务端历史记录还原：手机当时只跑服务端，"
            "没有留下完整结果包，逐秒数据由日志里的汇总行重新解析得到。",
            "服务端只测得到接收侧速率，客户端发送侧速率缺失，也不含设备信息。",
        ],
        "raw": {"srv": item.get("log") or ""},
    }


def pick_bundles(records):
    """把记录分成（可导入, 跳过）。

    客户端记录的 bundle 是完整结果包，直接用；服务端记录的 bundle 是空串，
    退而从 summary / log 还原一个最小包。还原不出来（比如那次没收到流量）
    就仍然归跳过 —— 宁可少一条，也不能凭空造数据。
    """
    ready, skipped = [], []
    for rec in records or []:
        if not isinstance(rec, dict):
            skipped.append(rec)
            continue
        bundle = rec.get("bundle")
        if isinstance(bundle, dict) and bundle:
            ready.append(rec)
            continue
        made = synth_bundle(rec)
        if made:
            rec["bundle"] = made
            ready.append(rec)
        else:
            skipped.append(rec)
    return ready, skipped

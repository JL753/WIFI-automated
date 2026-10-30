# -*- coding: utf-8 -*-
"""测试编排：把「选中的测试项 + 参数」跑成一组可交付的结果。

分工：本文件只管流程与节奏；设备读写走 adbkit/devinfo，iperf3 走 iperf，落盘走 report。
"""
from __future__ import annotations

import os
import sys
import time
import threading
import datetime

from . import adbkit as ad
from . import devinfo as di
from . import iperf as ip
from . import report as rp

VERSION = "1.0"

# 测试项定义：(id, 中文名, 需要设备数, 一句话说明)
TEST_DEFS = [
    ("wifi_tput", "WiFi 吞吐量测试", 2, "两台手机连同一个 WiFi，测 TCP 上行与下行"),
    ("hotspot_tput", "热点吞吐量测试", 2, "一台手机开热点，另一台连上去测"),
    ("screen_cmp", "亮灭屏对比测试", 2, "亮屏 N 秒 / 灭屏 N 秒交替，对比吞吐差异"),
    ("long_run", "长稳吞吐测试", 2, "单次长跑，看速率抖动与重传累积"),
    ("udp_qos", "UDP 抖动丢包测试", 2, "限速 UDP，看 jitter 与丢包率"),
    ("link_snapshot", "链路质量快照", 1, "只采集不测速，出环境基线"),
    ("pc_tunnel", "PC 参与的隧道测试", 2, "PC 当一端走 adb 隧道（测的是 USB 带宽）"),
]

TEST_NAME = dict((k, n) for k, n, _, _ in TEST_DEFS)
TEST_NEED = dict((k, c) for k, _, c, _ in TEST_DEFS)

UNIT_SHORT = {"Bytes": "B", "KBytes": "KB", "MBytes": "MB", "GBytes": "GB"}


# ---------------------------------------------------------------- 界面

class UI(object):
    """控制台输出 + 运行中手动打点。"""

    def __init__(self):
        self.marks = []          # [(epoch, 名称)]
        self.quit_requested = False
        self._stop = False
        self._thr = None

    def rule(self, title=""):
        print("")
        print("=" * 66)
        if title:
            print(" " + title)
            print("=" * 66)

    def step(self, i, n, text):
        print("[%d/%d] %s ..." % (i, n, text))

    def ok(self, text):
        print("      OK  " + text)

    def warn(self, text):
        print("      !!  " + text)

    def info(self, text):
        print("      --  " + text)

    def err(self, text):
        print("      XX  " + text)

    def line(self, text):
        print(text)

    def flush(self):
        try:
            sys.stdout.flush()
        except Exception:
            pass

    def wait(self, seconds):
        """可被手动打点打断的等待。"""
        end = time.time() + seconds
        while time.time() < end and not self.quit_requested:
            time.sleep(0.1)

    # ---- 手动打点：msvcrt 只在真 Windows 控制台下可用
    def start_marker(self):
        try:
            import msvcrt
        except Exception:
            return False

        def loop():
            while not self._stop:
                try:
                    if msvcrt.kbhit():
                        ch = msvcrt.getwch()
                        low = ch.lower()
                        if low == "l":
                            self.marks.append((time.time(), "亮屏"))
                            self.info("打点：亮屏")
                        elif low == "d":
                            self.marks.append((time.time(), "灭屏"))
                            self.info("打点：灭屏")
                        elif low == "m":
                            self.marks.append((time.time(), "标记"))
                            self.info("打点：标记")
                        elif low == "q":
                            self.quit_requested = True
                            self.info("已请求提前结束当前测试")
                except Exception:
                    pass
                time.sleep(0.05)

        self._thr = threading.Thread(target=loop)
        self._thr.daemon = True
        self._thr.start()
        return True

    def stop_marker(self):
        self._stop = True
        self._thr = None


# ---------------------------------------------------------------- 记录显示

def fmt_record(rec):
    """把一条 Record 排成一行给人看的流式输出。"""
    stream = rec.get("stream") or ""
    if stream == "SUM":
        head = "SUM "
    else:
        head = stream
    try:
        iv = "%d-%ds" % (int(rec.get("start") or 0), int(rec.get("end") or 0))
    except Exception:
        iv = ""
    unit = UNIT_SHORT.get(rec.get("unit") or "", rec.get("unit") or "")
    try:
        transfer = "%7.1f %s" % (float(rec.get("transfer") or 0), unit)
    except Exception:
        transfer = ""
    ru = rec.get("rate_unit") or "Mbits/sec"
    # 直接沿用 iperf3 给的单位，绝不硬编码 Mb/s：
    # 千兆链路上 iperf3 打的是 Gbits/sec，写成 Mb/s 会把 15.6 Gb/s 显示成 15.6 Mb/s
    rb = {"bits/sec": "b/s", "Kbits/sec": "Kb/s", "Mbits/sec": "Mb/s",
          "Gbits/sec": "Gb/s", "Tbits/sec": "Tb/s"}.get(ru, ru)
    try:
        rate = "%7.1f %s" % (float(rec.get("rate") or 0), rb)
    except Exception:
        rate = ""

    extra = ""
    if rec.get("kind") == "UDP":
        j = rec.get("jitter")
        lost = rec.get("lost")
        total = rec.get("total")
        if j not in ("", None):
            extra += "  jitter %s ms" % j
        if lost not in ("", None) and total not in ("", None):
            pct = rec.get("lost_pct")
            extra += "  lost %s/%s" % (lost, total)
            if pct not in ("", None):
                extra += " (%s%%)" % pct
    else:
        if rec.get("retr") not in ("", None):
            extra += "  retr %s" % rec.get("retr")
    if rec.get("role"):
        extra += "  " + str(rec["role"])

    return "%s | %-4s %-9s %s  %s%s" % (
        rec.get("t", ""), head, iv, transfer, rate, extra)


def _rate_unit(res, role):
    s = (res.get("summary") or {}).get(role) or {}
    if s.get("rate") in (None, ""):
        return None, ""
    return float(s.get("rate")), (s.get("unit") or "Mbits/sec")


def _rate_mbps(res, role):
    """把 sender/receiver 汇总速率统一换算成 Mbits/sec 的数值，取不到返回 None。"""
    rate, unit = _rate_unit(res, role)
    if rate is None:
        return None
    out = rp.fmt_bps(rate, unit)
    try:
        return float(out.split()[0])
    except Exception:
        return None


def _fmt_rate(res, role):
    """给报告/统计用的统一口径：一律 Mbits/sec。"""
    x = _rate_mbps(res, role)
    return "" if x is None else "%.1f Mbits/sec" % x


def _fmt_rate_native(res, role):
    """给人看的口径：保留 iperf3 原本的单位，千兆链路上就写 Gbits/sec。"""
    rate, unit = _rate_unit(res, role)
    if rate is None:
        return ""
    if unit in ("Gbits/sec", "Tbits/sec"):
        return "%.1f %s" % (rate, unit)
    return "%.1f Mbits/sec" % _rate_mbps(res, role)


# ---------------------------------------------------------------- 屏控

def get_screen(serial):
    try:
        out = ad.shell(serial, "dumpsys power | grep -m1 mWakefulness=", timeout=15)
        if "Asleep" in out or "Dozing" in out:
            return "Asleep"
        if "Awake" in out:
            return "Awake"
    except Exception:
        pass
    return "Unknown"


def set_screen(serial, on):
    """把手机屏幕置为亮/灭，并回读确认。"""
    try:
        if on:
            ad.shell(serial, "input keyevent 224", timeout=15)
            time.sleep(0.6)
            ad.shell(serial, "wm dismiss-keyguard", timeout=15)
        else:
            ad.shell(serial, "input keyevent 26", timeout=15)
    except Exception:
        return "Unknown"
    time.sleep(1.0)
    return get_screen(serial)


# ---------------------------------------------------------------- 主流程

class Ctx(object):
    def __init__(self, ui, opts, out_dir):
        self.ui = ui
        self.opts = opts
        self.out_dir = out_dir
        self.records = []
        self.results = []
        self.raws = {}
        self.notes = []
        self.screen_compare = None
        self.port = int(opts.get("port") or 5201)
        self.srv_serial = opts.get("server")
        self.cli_serial = opts.get("client")
        self.server_ip = ""
        self.started_at = datetime.datetime.now()

    def note(self, text):
        self.notes.append(text)
        self.ui.warn(text)


def iface_addrs(info, names=("wlan0", "wlan1")):
    """按网卡名汇总地址，数据优先取 adb shell ifconfig 的解析结果。

    返回 [(iface, ip, prefix, source)]，source 为 "ifconfig" 或 "ip"。
    """
    ifcfg = [a for a in (info.get("ifcfg") or []) if a.get("ip")]
    addrs = [a for a in (info.get("addrs") or []) if a.get("ip")]
    out = []
    for name in names:
        hit = [a for a in ifcfg if a.get("iface") == name]
        src = "ifconfig"
        if not hit:
            hit = [a for a in addrs if a.get("iface") == name]
            src = "ip"
        if hit:
            a = hit[0]
            out.append((name, a.get("ip") or "", a.get("prefix") or 0, src))
        else:
            out.append((name, "", 0, ""))
    return out


def report_server_addrs(ui, info, serial):
    """打印服务端手机的 wlan0 / wlan1 地址（用户要求用 ifconfig 口径）。"""
    ui.info("服务端 %s 的网卡地址（adb shell ifconfig）：" % serial)
    for name, ip, prefix, src in iface_addrs(info):
        if ip:
            tail = "" if src == "ifconfig" else "（ifconfig 无此项，取自 ip addr）"
            ui.info("    %-6s %s/%d%s" % (name, ip, prefix, tail))
        else:
            ui.info("    %-6s 无 IPv4 地址" % name)


def pick_server_ip(ctx, srv_info, cli_info):
    """从服务端设备的地址里挑一个客户端能连上的 IP。

    优先：热点地址（wlan1 且有 IP）> wlan0 地址 > 第一个非 lo 地址。
    """
    hs = srv_info.get("hotspot") or {}
    if hs.get("on") and hs.get("ip"):
        return hs["ip"], "热点地址"
    addrs = [a for a in (srv_info.get("addrs") or []) if a.get("ip") and a["ip"] != "127.0.0.1"]
    for a in addrs:
        if a.get("iface") == "wlan0":
            return a["ip"], "WiFi 地址"
    if addrs:
        return addrs[0]["ip"], addrs[0].get("iface") or "地址"
    return "", ""


def check_reach(ctx, cli_serial, ip):
    try:
        rc, out = ad.shell_rc(cli_serial, "ping -c 2 -W 2 %s" % ip, timeout=20)
    except Exception as exc:
        return False, "ping 执行失败：%s" % exc
    if "0% packet loss" in out or "0 packets lost" in out:
        return True, "ping 通"
    return False, "ping 不通（%s）" % (out.strip().splitlines()[-1] if out.strip() else "无输出")


def _run_one(ctx, test_id, kind, reverse, duration, streams, udp, udp_bw, title, expect_dir):
    """跑一次 iperf3 客户端，逐行流式打印。返回结果 dict。"""
    ui = ctx.ui
    ui.rule(title)
    if expect_dir:
        ui.line("  读表  #N=单流  SUM=合计  " + expect_dir)
    if ctx.opts.get("mark") and ui.marks:
        ui.line("  运行中可按键打点：L=亮屏 D=灭屏 M=标记 Q=提前结束")
    ui.line("")

    recs = []

    def on_record(rec):
        recs.append(rec)
        ctx.records.append(rec)
        ui.line(fmt_record(rec))
        ui.flush()

    def on_raw(line):
        s = line.rstrip()
        if not s:
            return
        low = s.lower()
        if "error" in low or "unable" in low or "warning" in low:
            ctx.notes.append("[%s] %s" % (test_id, s))
            ui.line("      !!  " + s)

    started = time.time()
    res = ip.run_client(
        ctx.cli_serial, ctx.server_ip, ctx.port,
        duration=duration, streams=streams, reverse=reverse,
        udp=udp, udp_bw=udp_bw, test_id=test_id,
        on_record=on_record, on_raw=on_raw,
    )
    res["test_id"] = test_id
    res["kind"] = kind
    res["params"] = {
        "duration": duration, "streams": streams, "reverse": bool(reverse),
        "udp": bool(udp), "udp_bw": udp_bw if udp else "",
    }
    res["records_n"] = len(recs)
    # 显式带上本轮记录：wifi_tput 与 hotspot_tput 共用 tcp_up/tcp_down，
    # 若不指定，report 按 test_id 反查会把两项的记录混到一起
    res["records"] = recs
    res["sender_rate"] = _fmt_rate(res, "sender")
    res["receiver_rate"] = _fmt_rate(res, "receiver")
    res["elapsed"] = round(time.time() - started, 1)
    ctx.raws[test_id] = res.get("raw_text") or ""
    ctx.results.append(res)

    ui.line("")
    if res.get("ok"):
        ui.ok("%s 完成，用时 %s 秒，%d 行数据" % (TEST_NAME.get(test_id, test_id), res["elapsed"], len(recs)))
        rec_native = _fmt_rate_native(res, "receiver")
        snd_native = _fmt_rate_native(res, "sender")
        if rec_native:
            ui.info("receiver 速率 %s" % rec_native)
        if snd_native:
            ui.info("sender   速率 %s" % snd_native)
    else:
        ui.err("%s 失败：%s" % (TEST_NAME.get(test_id, test_id), res.get("error") or "未知原因"))
    return res


def _auto_udp_bw(ctx, tcp_res, fallback="50M"):
    """按 TCP 实测带宽的 1.5 倍给 UDP 限速，夹在 20M~200M。"""
    rate = _fmt_rate(tcp_res, "receiver") or _fmt_rate(tcp_res, "sender")
    if not rate:
        return fallback
    try:
        num = float(rate.split()[0])
    except Exception:
        return fallback
    val = int(num * 1.5)
    if val < 20:
        val = 20
    if val > 200:
        val = 200
    return "%dM" % val


def run_plan(ctx, infos, items):
    """按选中的测试项顺序执行。infos 是 DeviceInfo 列表。"""
    ui = ctx.ui
    opts = ctx.opts
    duration = int(opts.get("duration") or 30)
    streams = int(opts.get("streams") or 4)
    reverse = bool(opts.get("reverse"))

    by_serial = dict((i.get("serial"), i) for i in infos)
    srv_info = by_serial.get(ctx.srv_serial) or infos[0]
    cli_info = by_serial.get(ctx.cli_serial) or (infos[1] if len(infos) > 1 else infos[0])

    # ---- 1. 确保两端都有 iperf3
    need_traffic = any(t != "link_snapshot" for t in items)
    local_bin = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(
        os.path.abspath(__file__)))), "bin", ip.LOCAL_BIN_NAME)

    if need_traffic:
        ui.rule("准备 iperf3 二进制")
        for role, serial in (("服务端", ctx.srv_serial), ("客户端", ctx.cli_serial)):
            try:
                ui.step(1, 3, "%s %s 检查 iperf3" % (role, serial))
                info = ip.ensure_phone_bin(serial, local_bin)
                ui.ok("%s %s" % (info.get("version") or "已就绪", "（本次已推送）" if info.get("pushed") else ""))
            except Exception as exc:
                ui.err("在 %s 上准备 iperf3 失败：%s" % (serial, exc))
                raise

    # ---- 2. 服务端 IP 与连通性
    if need_traffic:
        ipaddr, src = pick_server_ip(ctx, srv_info, cli_info)
        if not ipaddr:
            raise RuntimeError("服务端手机 %s 没有任何可用 IP，请先连上 WiFi 或开热点" % ctx.srv_serial)
        ctx.server_ip = ipaddr
        ui.step(2, 3, "服务端地址")
        ui.ok("%s（取自 %s）" % (ipaddr, src))
        report_server_addrs(ui, srv_info, ctx.srv_serial)
        reach, why = check_reach(ctx, ctx.cli_serial, ipaddr)
        if reach:
            ui.ok("客户端到服务端 %s" % why)
        else:
            ui.err("客户端到服务端 %s" % why)
            raise RuntimeError(
                "两台手机不在同一网段或热点没开，无法测真实空口吞吐。\n"
                "      处理办法：让两台手机连同一个 WiFi，或让其中一台开热点、另一台连上去。")

        # ---- 3. 起服务端
        ui.step(3, 3, "启动服务端 iperf3")
        sr = ip.start_server(ctx.srv_serial, ctx.port)
        if not sr.get("ok"):
            raise RuntimeError("服务端启动失败：%s" % (sr.get("detail") or "端口未监听"))
        ui.ok("监听 %d 端口%s" % (ctx.port, ("，PID " + sr["pid"]) if sr.get("pid") else ""))

    screen_on = opts.get("screen_on", True)
    if need_traffic and opts.get("mark"):
        ui.rule("运行中打点")
        ui.line("  L = 标记亮屏    D = 标记灭屏    M = 普通标记    Q = 提前结束当前测试")
        ui.line("")

    try:
        # ================================================================ 各测试项
        if "link_snapshot" in items:
            ui.rule("链路质量快照")
            for info in infos:
                ui.line(di.render_card(info))
            ui.ok("已采集 %d 台设备的链路信息，不产生任何流量" % len(infos))

        if "wifi_tput" in items:
            _run_one(ctx, "tcp_up", "TCP", False, duration, streams, False, None,
                     "WiFi 吞吐量测试 · 上行（客户端发、服务端收）",
                     "本机发出数据，上行看 receiver")
            if reverse:
                _run_one(ctx, "tcp_down", "TCP", True, duration, streams, False, None,
                         "WiFi 吞吐量测试 · 下行（-R：服务端发、客户端收）",
                         "本机接收数据，下行看 receiver")

        if "hotspot_tput" in items:
            hs = srv_info.get("hotspot") or {}
            if not hs.get("on"):
                ctx.note("服务端手机 %s 当前没有开热点，热点吞吐量测试按普通 WiFi 链路跑" % ctx.srv_serial)
            else:
                ui.info("服务端热点 SSID=%s 频段 %s MHz 客户端数 %s"
                        % (hs.get("ssid") or "--", hs.get("freq_mhz") or "--", hs.get("clients") or 0))
            # 与 wifi_tput 共用 tcp_up/tcp_down，报告里靠 name 区分是哪一项
            r = _run_one(ctx, "tcp_up", "TCP", False, duration, streams, False, None,
                         "热点吞吐量测试 · 上行（连热点那台发、开热点那台收）",
                         "本机发出数据，上行看 receiver")
            r["name"] = "热点吞吐量（TCP 正向）"
            if reverse:
                r = _run_one(ctx, "tcp_down", "TCP", True, duration, streams, False, None,
                             "热点吞吐量测试 · 下行（-R：开热点那台发、连热点那台收）",
                             "本机接收数据，下行看 receiver")
                r["name"] = "热点吞吐量（TCP 反向 -R）"

        if "long_run" in items:
            long_s = int(opts.get("long_duration") or max(duration, 300))
            ui.info("长稳测试时长 %d 秒（可用 -L 秒数单独设定）" % long_s)
            _run_one(ctx, "long_run", "TCP", reverse, long_s, streams, False, None,
                     "长稳吞吐测试 · 连续 %d 秒" % long_s,
                     "看逐秒速率是否掉底、retr 是否持续增长")

        if "udp_qos" in items:
            bw = opts.get("udp_bw") or "auto"
            if str(bw).lower() == "auto":
                base = None
                for r in ctx.results:
                    if r.get("test_id") in ("tcp_up", "long_run") and r.get("ok"):
                        base = r
                if base is None:
                    ui.info("还没有 TCP 结果可参考，先跑一轮短 TCP 探底")
                    probe = _run_one(ctx, "tcp_up", "TCP", False, max(5, min(10, duration)),
                                     streams, False, None,
                                     "UDP 限速探底 · 短 TCP", "")
                    base = probe
                bw = _auto_udp_bw(ctx, base)
                ui.info("UDP 限速按 TCP 实测的 1.5 倍取 %s" % bw)
            _run_one(ctx, "udp_up", "UDP", False, duration, 1, True, bw,
                     "UDP 抖动丢包测试 · 限速 %s" % bw,
                     "看 jitter（抖动 ms）与 lost/total（丢包率）")
            if reverse:
                _run_one(ctx, "udp_down", "UDP", True, duration, 1, True, bw,
                         "UDP 抖动丢包测试 · 反向 -R · 限速 %s" % bw,
                         "看 jitter（抖动 ms）与 lost/total（丢包率）")

        if "screen_cmp" in items:
            _run_screen_compare(ctx, infos, duration, streams, reverse)

        if "pc_tunnel" in items:
            _run_pc_tunnel(ctx, duration, streams)

    finally:
        if need_traffic:
            try:
                ip.stop_server(ctx.srv_serial)
            except Exception:
                pass

    # ---- 收尾：屏幕复原
    for serial in (ctx.srv_serial, ctx.cli_serial):
        if serial:
            try:
                if get_screen(serial) != "Awake":
                    set_screen(serial, True)
            except Exception:
                pass

    ctx.finished_at = datetime.datetime.now()
    return ctx


def _run_screen_compare(ctx, infos, duration, streams, reverse):
    """亮屏 / 灭屏交替，各跑 rounds 轮，输出对比。"""
    ui = ctx.ui
    opts = ctx.opts
    seg = int(opts.get("screen_seg") or 20)
    rounds = int(opts.get("screen_rounds") or 2)
    phones = [s for s in (ctx.srv_serial, ctx.cli_serial) if s]

    ui.rule("亮灭屏对比测试")
    ui.line("  每段 %d 秒，亮屏 / 灭屏各 %d 轮，共 %d 段" % (seg, rounds, rounds * 2))
    ui.line("  注意：实测主因是【服务端】手机熄屏，两端会同时切换以贴近真实场景")
    ui.line("")

    on_rates, off_rates = [], []
    for i in range(rounds):
        for state, bucket, tid, label in (
                (True, on_rates, "screen_on", "亮屏"),
                (False, off_rates, "screen_off", "灭屏")):
            real = {}
            for s in phones:
                real[s] = set_screen(s, state)
            ui.line("  --- 第 %d 轮 %s（服务端 %s / 客户端 %s）---"
                    % (i + 1, label, real.get(ctx.srv_serial, "?"), real.get(ctx.cli_serial, "?")))
            res = _run_one(ctx, tid, "TCP", reverse, seg, streams, False, None,
                           "%s 第 %d 轮 · %s" % (label, i + 1, "反向 -R" if reverse else "正向"),
                           "本机%s数据" % ("接收" if reverse else "发出"))
            # 报告里同名 test_id 会重复出现（screen_on 跑两轮），带上轮次才分得清
            res["name"] = "%s 第 %d 轮" % (label, i + 1)
            if not res.get("ok"):
                ui.warn("  第 %d 轮 %s 失败，本轮不计入统计：%s"
                        % (i + 1, label, res.get("error") or "未知原因"))
                continue
            rate = _fmt_rate(res, "receiver")
            if rate:
                try:
                    bucket.append(float(rate.split()[0]))
                except Exception:
                    pass
            else:
                ui.warn("  第 %d 轮 %s 没有取到 receiver 速率，本轮不计入统计" % (i + 1, label))

    def avg(xs):
        return round(sum(xs) / len(xs), 1) if xs else 0.0

    on_avg, off_avg = avg(on_rates), avg(off_rates)
    delta = 0.0
    if on_avg:
        delta = round((off_avg - on_avg) / on_avg * 100.0, 1)

    ctx.screen_compare = {
        "seg_s": seg, "rounds": rounds,
        "screen_on": on_rates, "screen_off": off_rates,
        "on_avg": on_avg, "off_avg": off_avg, "delta_pct": delta,
        "samples_on": len(on_rates), "samples_off": len(off_rates),
    }

    ui.line("")
    ui.rule("亮灭屏对比结果")
    ui.line("  亮屏 n=%d  平均 %.1f Mbits/sec  明细 %s"
            % (len(on_rates), on_avg, ", ".join("%.1f" % x for x in on_rates) or "--"))
    ui.line("  灭屏 n=%d  平均 %.1f Mbits/sec  明细 %s"
            % (len(off_rates), off_avg, ", ".join("%.1f" % x for x in off_rates) or "--"))
    if on_avg:
        ui.line("  灭屏相对亮屏 %+.1f%%" % delta)
        if delta > 10:
            ui.line("  结论：熄屏后吞吐明显更高（省电策略放松了射频/CPU 限制）")
        elif delta < -10:
            ui.line("  结论：熄屏后吞吐明显更低（可能进入了省电或 Doze）")
        else:
            ui.line("  结论：亮灭屏对吞吐影响不明显")
    ui.line("  风险：熄屏 30 分钟后可能进入 Doze；热点 10 分钟无活动会自动关闭")


def _run_pc_tunnel(ctx, duration, streams):
    """PC 当服务端，手机通过 adb reverse 走 USB 隧道。"""
    ui = ctx.ui
    ui.rule("PC 参与的隧道测试")
    ui.line("  注意：这条链路走的是 USB 数据线，测出来是【USB 隧道带宽】，不是 WiFi 带宽")
    ui.line("")
    pc_exe = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(
        os.path.abspath(__file__)))), "bin", "win", "iperf3.exe")
    if not os.path.exists(pc_exe):
        ctx.note("PC 端 iperf3.exe 不存在：%s" % pc_exe)
        ui.err("找不到 PC 端 iperf3：%s" % pc_exe)
        return
    # 先确认 PC 端 iperf3 真的能起来。Cygwin 构建在缺运行库时会直接报
    # "couldn't create signal pipe, Win32 error 5"，若不先探一次，
    # 后面会白等一整个 duration 才失败。
    ver = _probe_pc_iperf(pc_exe)
    if not ver:
        ctx.note("PC 端 iperf3 无法启动：%s" % pc_exe)
        ui.err("PC 端 iperf3 无法启动")
        ui.line("  原因  ：%s -v 没有返回版本号（Cygwin 运行库缺失或被安全软件拦截）"
                % os.path.basename(pc_exe))
        ui.line("  处理办法：确认 %s 与同目录的 cygwin1.dll / cygcrypto-3.dll / cygz.dll 一起存在；"
                % os.path.basename(pc_exe))
        ui.line("           仍失败就跳过本项，用 WiFi 吞吐量测试替代。")
        return
    ui.ok("PC 端 iperf3 %s" % ver)

    port = ctx.port
    try:
        ad.reverse(ctx.cli_serial, port, port)
        ui.ok("已建立 adb reverse tcp:%d -> tcp:%d" % (port, port))
    except Exception as exc:
        ui.err("adb reverse 失败：%s" % exc)
        return
    import subprocess
    proc = subprocess.Popen(
        [pc_exe, "-s", "-p", str(port), "-1"],
        cwd=os.path.dirname(pc_exe),
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, encoding="utf-8", errors="replace", bufsize=1)
    time.sleep(2.0)
    if proc.poll() is not None:
        tail = ""
        try:
            tail = (proc.stdout.read() or "").strip()
        except Exception:
            pass
        ctx.note("PC 端 iperf3 服务端启动后立即退出：%s" % (tail or "无输出"))
        ui.err("PC 端 iperf3 服务端启动后立即退出")
        ui.line("  原因  ：%s" % (tail.splitlines()[-1] if tail else "进程秒退，无输出"))
        ui.line("  处理办法：单独在命令行里跑 bin\\win\\iperf3.exe -s -p %d 看完整报错。" % port)
        try:
            ad.remove_forwards(ctx.cli_serial)
        except Exception:
            pass
        return
    try:
        _run_one(ctx, "tcp_up", "TCP", False, duration, streams, False, None,
                 "PC 隧道 · 手机发、PC 收", "本机发出数据，上行看 receiver")
    finally:
        try:
            proc.terminate()
            proc.wait(timeout=5)
        except Exception:
            try:
                proc.kill()
            except Exception:
                pass
        try:
            ad.remove_forwards(ctx.cli_serial)
        except Exception:
            pass


def _probe_pc_iperf(pc_exe):
    """跑一次 iperf3.exe -v，成功返回版本串，失败返回空串。带硬超时防挂死。"""
    import subprocess
    try:
        p = subprocess.Popen([pc_exe, "-v"], cwd=os.path.dirname(pc_exe),
                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                             text=True, encoding="utf-8", errors="replace")
    except OSError:
        return ""
    try:
        out, _ = p.communicate(timeout=15)
    except subprocess.TimeoutExpired:
        try:
            p.kill()
        except Exception:
            pass
        return ""
    text = (out or "").strip()
    low = text.lower()
    # Cygwin 构建起不来时会往 stdout 打 "*** fatal error - ..."，必须当失败
    if not text or "fatal error" in low or "win32 error" in low or "error -" in low:
        return ""
    for line in text.splitlines():
        if "iperf" in line.lower() and "version" in line.lower():
            return line.strip()
    first = text.splitlines()[0].strip()
    return first if "iperf" in first.lower() else ""

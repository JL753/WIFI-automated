# -*- coding: utf-8 -*-
"""命令行入口：菜单、参数、设备展示、把选中的测试项交给 runner。

设计原则：
- 加载后立刻打印所有已连接设备的详细卡片（用户要「一眼分辨是哪台手机」）。
- 所有能预设的参数都支持命令行直传，供不想进菜单的人用。
- 出错给三段式中文提示，绝不裸抛 traceback 给最终用户。
"""
from __future__ import annotations

import os
import sys
import time
import argparse
import datetime
import traceback

if hasattr(sys.stdout, "reconfigure") and not sys.stdout.isatty():
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

from . import adbkit as ad
from . import devinfo as di
from . import iperf as ip
from . import report as rp
from . import runner as rn

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
DEFAULT_OUT = os.path.join(ROOT, "输出")


def banner():
    print("")
    print("  ==============================================================")
    print("      WiFi / 热点 吞吐量测试工具   v" + rn.VERSION)
    print("  ==============================================================")
    print("")
    print("  测真实空口吞吐：两台手机一台做服务端、一台做客户端，用 iperf3 打流。")
    print("  支持 WiFi、热点、亮灭屏对比、长稳、UDP 抖动丢包、链路快照。")
    print("")


# ---------------------------------------------------------------- 小工具

def ask(prompt, default=""):
    try:
        s = input(prompt)
    except (EOFError, KeyboardInterrupt):
        print("")
        return default
    s = (s or "").strip()
    return s if s else default


def ask_int(prompt, default, lo=None, hi=None):
    s = ask(prompt, str(default))
    try:
        v = int(s)
    except Exception:
        print("      输入不是数字，改用默认值 %s" % default)
        v = int(default)
    if lo is not None and v < lo:
        v = lo
    if hi is not None and v > hi:
        v = hi
    return v


def ask_yes(prompt, default=False):
    s = ask(prompt, "y" if default else "n").lower()
    return s in ("y", "yes", "是", "1")


def die(title, reason, fix, code=1):
    print("")
    print("  [错误] " + title)
    print("  原因  ：" + reason)
    print("  处理办法：" + fix)
    print("")
    return code


# ---------------------------------------------------------------- 设备

def load_devices(verbose=True):
    """返回 [(serial, info)]。info 为 None 表示探测失败。"""
    devs = ad.list_devices()
    ready = [d for d in devs if d.get("state") == "device"]
    if not ready:
        return []
    out = []
    for d in ready:
        info = None
        if verbose:
            try:
                info = di.probe(d["serial"])
            except Exception as exc:
                print("      !! 采集 %s 的信息失败：%s" % (d["serial"], exc))
        else:
            info = {"serial": d["serial"], "state": d.get("state"),
                    "model": d.get("model"), "transport_id": d.get("transport_id")}
        out.append((d["serial"], info))
    return out


def show_devices(pairs):
    print("")
    print("  发现 %d 台已就绪设备：" % len(pairs))
    print("")
    for i, (serial, info) in enumerate(pairs):
        if info:
            print(di.render_card(info, i + 1))
        else:
            print("  [%d] %s  （信息采集失败）" % (i + 1, serial))
        print("")


def pick_two(pairs):
    """返回 (server_serial, client_serial)。"""
    if len(pairs) == 1:
        serial = pairs[0][0]
        return serial, serial
    serials = [p[0] for p in pairs]
    infos = [p[1] for p in pairs]
    try:
        gs, gc = di.guess_roles(infos)
    except Exception:
        gs, gc = serials[0], serials[1]
    ds = serials.index(gs) + 1 if gs in serials else 1
    dc = serials.index(gc) + 1 if gc in serials else 2
    if ds == dc:
        dc = 2 if ds == 1 else 1
    print("  建议：%d 号当服务端（发数据的那台），%d 号当客户端" % (ds, dc))
    print("")
    a = ask_int("  哪一台做【服务端】？回车用建议值 %d ：" % ds, ds, 1, len(serials))
    b = ask_int("  哪一台做【客户端】？回车用建议值 %d ：" % dc, dc, 1, len(serials))
    if a == b:
        print("      服务端与客户端不能是同一台，客户端自动改为另一台")
        b = 2 if a == 1 else 1
    return serials[a - 1], serials[b - 1]


# ---------------------------------------------------------------- 参数

class Opts(object):
    def __init__(self):
        self.duration = 30
        self.streams = 4
        self.reverse = True
        self.mark = True
        self.udp_bw = "auto"
        self.long_duration = 300
        self.screen_seg = 20
        self.screen_rounds = 2
        self.port = 5201
        self.out = DEFAULT_OUT
        self.server = None
        self.client = None

    def as_dict(self):
        return dict(self.__dict__)


def show_params(o):
    print("  当前参数：")
    print("    每项测试时长 : %d 秒" % o.duration)
    print("    并发流数     : %d 条" % o.streams)
    print("    反向 -R      : %s" % ("开（同时测下行）" if o.reverse else "关（只测上行）"))
    print("    UDP 限速     : %s" % o.udp_bw)
    print("    长稳时长     : %d 秒" % o.long_duration)
    print("    亮灭屏分段   : 每段 %d 秒，各 %d 轮" % (o.screen_seg, o.screen_rounds))
    print("    运行中打点   : %s" % ("开" if o.mark else "关"))
    print("    端口         : %d" % o.port)
    print("    输出目录     : %s" % o.out)


def edit_params(o):
    while True:
        print("")
        show_params(o)
        print("")
        print("  修改哪一项？（直接回车返回）")
        print("    1  每项测试时长（秒）")
        print("    2  并发流数（1-16）")
        print("    3  是否做反向 -R 测试")
        print("    4  UDP 限速（auto 或 50M 这样写）")
        print("    5  长稳测试时长（秒）")
        print("    6  亮灭屏每段秒数 / 轮数")
        print("    7  运行中是否允许按键打点")
        print("    8  服务端口")
        c = ask("  序号：", "")
        if not c:
            return
        if c == "1":
            o.duration = ask_int("    每项测试时长（秒，5-3600）：", o.duration, 5, 3600)
        elif c == "2":
            o.streams = ask_int("    并发流数（1-16）：", o.streams, 1, 16)
        elif c == "3":
            o.reverse = ask_yes("    做反向 -R 测试？（y/n，当前 %s）" % ("y" if o.reverse else "n"), o.reverse)
        elif c == "4":
            o.udp_bw = ask("    UDP 限速（auto 或 50M）：", o.udp_bw)
        elif c == "5":
            o.long_duration = ask_int("    长稳测试时长（秒，60-7200）：", o.long_duration, 60, 7200)
        elif c == "6":
            o.screen_seg = ask_int("    亮灭屏每段秒数（5-300）：", o.screen_seg, 5, 300)
            o.screen_rounds = ask_int("    亮屏/灭屏各跑几轮（1-10）：", o.screen_rounds, 1, 10)
        elif c == "7":
            o.mark = ask_yes("    运行中允许按键打点？（y/n）", o.mark)
        elif c == "8":
            o.port = ask_int("    服务端口（1024-65535）：", o.port, 1024, 65535)
        else:
            print("      没有这个序号")


# ---------------------------------------------------------------- 选测试项

PC_ENV = {}


def show_items(chosen):
    print("")
    print("  可选测试项（[x] 表示已选）：")
    for i, (tid, name, need, desc) in enumerate(rn.TEST_DEFS):
        note = ""
        if tid == "pc_tunnel" and not PC_ENV.get("has_wireless", False):
            note = "  <- 本机无无线网卡，只能测 USB 隧道带宽"
        print("    %d  [%s] %-16s %s%s" % (i + 1, "x" if tid in chosen else " ", name, desc, note))
    print("")


def pick_items(o):
    chosen = ["wifi_tput", "udp_qos"]
    while True:
        show_items(chosen)
        print("  输入序号切换（可一次输多个，如 1 3 5）；a 全选，c 清空，直接回车开始：")
        s = ask("  > ", "")
        if not s:
            if not chosen:
                print("      至少选一项")
                continue
            return chosen
        if s.lower() == "a":
            chosen = [d[0] for d in rn.TEST_DEFS]
            continue
        if s.lower() == "c":
            chosen = []
            continue
        for tok in s.replace(",", " ").split():
            if not tok.isdigit():
                continue
            k = int(tok)
            if 1 <= k <= len(rn.TEST_DEFS):
                tid = rn.TEST_DEFS[k - 1][0]
                if tid in chosen:
                    chosen.remove(tid)
                else:
                    chosen.append(tid)
        chosen = [d[0] for d in rn.TEST_DEFS if d[0] in chosen]


# ---------------------------------------------------------------- 输出

def save_all(ctx, items, devices, opts):
    out_dir = rp.make_run_dir(opts.out, items)
    meta = {
        "tool": "WiFi 吞吐量测试工具",
        "version": rn.VERSION,
        "started_at": ctx.started_at.strftime("%Y-%m-%d %H:%M:%S"),
        "finished_at": (getattr(ctx, "finished_at", None) or datetime.datetime.now()).strftime("%Y-%m-%d %H:%M:%S"),
        "items": items,
        "params": {
            "duration": opts.duration, "streams": opts.streams, "reverse": opts.reverse,
            "udp_bw": opts.udp_bw, "long_duration": opts.long_duration,
            "screen_seg": opts.screen_seg, "screen_rounds": opts.screen_rounds,
            "port": opts.port,
        },
        "server_serial": ctx.srv_serial,
        "client_serial": ctx.cli_serial,
        "server_ip": ctx.server_ip,
        "pc": PC_ENV,
        "marks": [{"at": time.strftime("%H:%M:%S", time.localtime(t)), "what": w}
                  for t, w in ctx.ui.marks],
    }
    payload = rp.build_payload(
        meta=meta, devices=devices, records=ctx.records,
        results=ctx.results, screen=ctx.screen_compare, notes=ctx.notes)

    files = []
    p = os.path.join(out_dir, "逐秒明细.csv")
    rp.write_csv(p, ctx.records); files.append(p)
    p = os.path.join(out_dir, "汇总.json")
    rp.write_json(p, payload); files.append(p)
    p = os.path.join(out_dir, "测试报告.md")
    rp.write_markdown(p, payload); files.append(p)
    for tid, text in ctx.raws.items():
        if text:
            p = os.path.join(out_dir, "原始输出_" + tid + ".txt")
            rp.write_raw(p, text); files.append(p)

    print("")
    print("  结果已保存到：")
    print("    " + out_dir)
    for f in files:
        try:
            size = os.path.getsize(f)
        except Exception:
            size = 0
        print("      %-30s %9d 字节" % (os.path.basename(f), size))
    return out_dir


# ---------------------------------------------------------------- 入口

def _load_pc_env():
    global PC_ENV
    try:
        PC_ENV = di.collect_pc_env()
    except Exception as exc:
        PC_ENV = {"has_wireless": False, "error": str(exc)}


def _warn_pc():
    if PC_ENV.get("has_wireless"):
        names = ", ".join(a.get("name", "?") for a in (PC_ENV.get("wireless_adapters") or []))
        print("  PC 环境：检测到无线网卡 " + names)
    else:
        print("  PC 环境：没有检测到无线网卡 —— PC 参与的测试项只能走 USB 隧道，")
        print("           测出来的是 USB 带宽，不是 WiFi 带宽。")


def _require_devices():
    pairs = load_devices()
    if not pairs:
        return None, die("没有检测到可用设备",
                         "adb devices 里没有 state=device 的设备",
                         "1) 手机打开 开发者选项 -> USB 调试；2) 插数据线并在手机上允许调试；3) 重跑本工具")
    return pairs, 0


def cmd_list(args):
    _load_pc_env()
    _warn_pc()
    pairs, rc = _require_devices()
    if pairs is None:
        return rc
    show_devices(pairs)
    print(di.render_summary_table([p[1] for p in pairs if p[1]]))
    return 0


def _apply_args(o, args):
    if not args:
        return
    for attr in ("duration", "streams", "udp_bw", "long_duration", "out", "port",
                 "server", "client"):
        v = getattr(args, attr, None)
        if v:
            setattr(o, attr, v)
    if getattr(args, "reverse", None) is not None:
        o.reverse = args.reverse
    if getattr(args, "duration", None):
        # -t 同时作为亮灭屏每段秒数，否则报告里写「每轮 N 秒」和实际跑的段长对不上
        o.screen_seg = args.duration


def _items_from_args(args):
    items = []
    raw = getattr(args, "items", None)
    if raw:
        bad = []
        for tok in raw.replace(",", " ").split():
            if tok in rn.TEST_NAME:
                items.append(tok)
            else:
                bad.append(tok)
        if bad:
            print("")
            print("  [错误] 不认识这些测试项：%s" % ", ".join(bad))
            print("  原因  ：-i 只接受测试项 id，不接受中文名称或编号")
            print("  处理办法：可用 id 如下，逗号分隔传给 -i")
            for d in rn.TEST_DEFS:
                print("            %-14s %s" % (d[0], rn.TEST_NAME.get(d[0], d[0])))
            return []
    return [d[0] for d in rn.TEST_DEFS if d[0] in items]


def _resolve_roles(pairs, o):
    serials = [p[0] for p in pairs]
    if o.server and o.client:
        if o.server not in serials:
            return None, die("指定的服务端不在线", "%s 不在 adb devices 列表里" % o.server,
                             "检查数据线，或用 list 模式看当前在线的序列号")
        if o.client not in serials:
            return None, die("指定的客户端不在线", "%s 不在 adb devices 列表里" % o.client,
                             "检查数据线，或用 list 模式看当前在线的序列号")
        return (o.server, o.client), 0
    return pick_two(pairs), 0


def _execute(pairs, items, o, srv, cli):
    devs = [p[1] for p in pairs if p[1]]
    ui = rn.UI()
    ctx = rn.Ctx(ui, dict(o.as_dict(), server=srv, client=cli), o.out)
    if o.mark:
        if ui.start_marker():
            print("")
            print("  提示：运行中按 L 标记亮屏、D 标记灭屏、M 普通标记、Q 提前结束")
        else:
            print("      当前终端不支持按键读取（非交互式），手动打点已关闭")
            o.mark = False
    try:
        rn.run_plan(ctx, devs, items)
    except KeyboardInterrupt:
        print("")
        print("  已中断")
    except Exception as exc:
        print("")
        print("  [错误] 测试中断")
        print("  原因  ：%s" % exc)
        print("  处理办法：按上面的提示排查后重跑；完整堆栈见下方")
        traceback.print_exc()
    finally:
        ui.stop_marker()
    if ctx.records or ctx.results:
        try:
            save_all(ctx, items, devs, o)
        except Exception as exc:
            print("  [错误] 保存结果失败")
            print("  原因  ：%s" % exc)
            print("  处理办法：确认输出目录可写，或用 -O 指定别的目录")
            traceback.print_exc()
    else:
        print("")
        print("  没有采集到任何数据，未生成结果文件")
    return ctx


def cmd_run(args):
    _load_pc_env()
    o = Opts()
    _apply_args(o, args)
    items = _items_from_args(args)
    if getattr(args, "items", None) and not items:
        return 2
    pairs, rc = _require_devices()
    if pairs is None:
        return rc

    print("")
    print("=" * 66)
    print(" 已连接设备")
    print("=" * 66)
    show_devices(pairs)
    _warn_pc()

    if len(pairs) < 2 and any(rn.TEST_NEED.get(t, 2) >= 2 for t in items):
        return die("选中的测试项需要两台手机",
                   "当前只有 1 台设备在线：%s" % pairs[0][0],
                   "把第二台手机也插上并允许 USB 调试；或只选「链路质量快照」")
    roles, rc = _resolve_roles(pairs, o)
    if roles is None:
        return rc
    srv, cli = roles
    if not items:
        items = pick_items(o)
    print("")
    show_params(o)
    _execute(pairs, items, o, srv, cli)
    return 0


def cmd_menu(args):
    _load_pc_env()
    banner()
    _warn_pc()
    pairs, rc = _require_devices()
    if pairs is None:
        return rc
    print("")
    print("=" * 66)
    print(" 已连接设备（%d 台）" % len(pairs))
    print("=" * 66)
    show_devices(pairs)
    print(di.render_summary_table([p[1] for p in pairs if p[1]]))

    o = Opts()
    while True:
        print("")
        print("=" * 66)
        print(" 主菜单")
        print("=" * 66)
        print("  1  开始测试（先选测试项，再确认参数）")
        print("  2  修改参数")
        print("  3  重新扫描设备")
        print("  4  只看设备信息")
        print("  5  链路质量快照（单机，不产生流量）")
        print("  0  退出")
        c = ask("  请选择：", "1")
        if c == "0":
            return 0
        if c == "2":
            edit_params(o)
            continue
        if c == "3":
            pairs, rc = _require_devices()
            if pairs is None:
                return rc
            show_devices(pairs)
            continue
        if c == "4":
            show_devices(pairs)
            continue
        if c == "5":
            items = ["link_snapshot"]
            srv = cli = pairs[0][0]
            _execute(pairs, items, o, srv, cli)
            ask("  按回车回到主菜单 ...", "")
            continue
        if c != "1":
            print("      没有这个序号")
            continue

        items = pick_items(o)
        print("")
        show_params(o)
        print("")
        if not ask_yes("  参数没问题，开始测试？（y/n）", True):
            continue
        if len(pairs) < 2 and any(rn.TEST_NEED.get(t, 2) >= 2 for t in items):
            print("  [错误] 选中的测试项需要两台手机")
            print("  原因  ：当前只有 1 台设备在线")
            print("  处理办法：插上第二台手机，或改选「链路质量快照」")
            continue
        if len(pairs) >= 2:
            srv, cli = pick_two(pairs)
        else:
            srv = cli = pairs[0][0]
        _execute(pairs, items, o, srv, cli)
        ask("  按回车回到主菜单 ...", "")


def build_parser():
    p = argparse.ArgumentParser(
        prog="WiFi吞吐量测试工具",
        description="两台手机之间的 WiFi / 热点真实空口吞吐测试工具")
    p.add_argument("mode", nargs="?", default="menu",
                   choices=["menu", "list", "run"],
                   help="menu=交互菜单（默认）；list=只看设备；run=按参数直接跑")
    p.add_argument("-t", "--duration", type=int, help="每项测试时长（秒）")
    p.add_argument("-P", "--streams", type=int, help="并发流数 1-16")
    p.add_argument("-R", "--reverse", dest="reverse", action="store_true", default=None,
                   help="同时做反向 -R 测试")
    p.add_argument("--no-reverse", dest="reverse", action="store_false",
                   help="只测上行，不做反向")
    p.add_argument("-b", "--udp-bw", dest="udp_bw", help="UDP 限速，如 50M 或 auto")
    p.add_argument("-L", "--long-duration", dest="long_duration", type=int,
                   help="长稳测试时长（秒）")
    p.add_argument("-i", "--items", help="测试项 id，逗号分隔")
    p.add_argument("-s", "--server", help="服务端设备序列号")
    p.add_argument("-c", "--client", help="客户端设备序列号")
    p.add_argument("-O", "--out", help="输出目录")
    p.add_argument("-p", "--port", type=int, help="服务端口")
    return p


def main(argv=None):
    try:
        args = build_parser().parse_args(argv)
    except SystemExit as exc:
        # argparse 对 -h 抛 0、对非法参数抛 2；一律返回 0 会让调用方以为跑成功了
        code = exc.code
        return 0 if code in (0, None) else (code if isinstance(code, int) else 2)
    try:
        if args.mode == "list":
            return cmd_list(args)
        if args.mode == "run":
            return cmd_run(args)
        return cmd_menu(args)
    except KeyboardInterrupt:
        print("")
        print("  已取消")
        return 130
    except Exception as exc:
        print("")
        print("  [错误] 工具异常退出")
        print("  原因  ：%s" % exc)
        print("  处理办法：把下面的堆栈发给开发者")
        traceback.print_exc()
        return 1

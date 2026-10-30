# -*- coding: utf-8 -*-
"""wb/iperf.py —— 手机端 iperf3 的部署、服务端管理与逐行流式客户端（契约 4.3）。

以下几条已被真机验证，改了就废：
  * 客户端命令行必须带 --forceflush，否则逐秒行被块缓冲、几秒后才一次性涌出。
  * 必须用 Popen 逐行读，禁止 communicate()（那会整段缓冲）。
  * 手机侧同时 | tee 落盘；adb shell 的退出码被管道吞掉、恒为 0，
    所以成败判定改为结束后 cat 落盘文件，看里面有没有 "iperf Done."。
  * start_server 的 pkill 与 nohup 必须是两次独立 shell 调用，
    合成一条会报 /data/local/tmp/iperf_srv.log: No such file or directory。
"""

import os
import re
import shutil
import subprocess
import threading
import time

PHONE_BIN = "/data/local/tmp/iperf3"
LOCAL_BIN_NAME = "iperf3-arm64v8"
LOCAL_BIN_BYTES = 7465336

SERVER_LOG = "/data/local/tmp/iperf_srv.log"
_DONE_MARK = "iperf Done."
_GOOD_VERSION = "iperf 3.2"

__all__ = [
    "PHONE_BIN", "LOCAL_BIN_NAME", "IperfError",
    "ensure_phone_bin", "start_server", "stop_server", "is_listening",
    "run_client", "parse_line", "probe_version",
]

_ADB_CANDIDATES = [r"C:\Program Files\ADB\adb.exe"]
_ADB_CACHE = [None]


class IperfError(RuntimeError):
    """iperf3 部署/调用失败。消息统一三段式：现象 / 原因 / 处理办法。"""


def _msg(what, why, how):
    return "[错误] %s\n原因: %s\n处理办法: %s" % (what, why, how)


def _log(log, text):
    if callable(log):
        try:
            log(text)
        except Exception:
            pass


def _load_adbkit():
    """尽力复用子代理 A 的 wb.adbkit；它还没落地时返回 None。"""
    try:
        from . import adbkit as _ak
        return _ak
    except Exception:
        pass
    try:
        import adbkit as _ak
        return _ak
    except Exception:
        return None


def _adb_path():
    if _ADB_CACHE[0]:
        return _ADB_CACHE[0]
    ak = _load_adbkit()
    if ak is not None and hasattr(ak, "find_adb"):
        try:
            p = ak.find_adb()
            if p and os.path.isfile(p):
                _ADB_CACHE[0] = p
                return p
        except Exception:
            pass
    tried = []
    for cand in _ADB_CANDIDATES:
        tried.append(cand)
        if os.path.isfile(cand):
            _ADB_CACHE[0] = cand
            return cand
    found = shutil.which("adb")
    tried.append("PATH 上的 adb")
    if found:
        _ADB_CACHE[0] = found
        return found
    env = os.environ.get("ADB", "")
    tried.append("环境变量 ADB")
    if env and os.path.isfile(env):
        _ADB_CACHE[0] = env
        return env
    raise IperfError(_msg(
        "找不到 adb.exe",
        "已尝试：" + " / ".join(tried),
        "安装 platform-tools 并把 adb.exe 放到 C:\\Program Files\\ADB\\，或设环境变量 ADB 指向它"))


def _run(argv, timeout):
    """返回 (rc, stdout, stderr)，全部解码成文本。超时/无法启动抛 IperfError。"""
    try:
        p = subprocess.run(argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                           timeout=timeout)
    except subprocess.TimeoutExpired:
        raise IperfError(_msg(
            "命令超时", "超过 %.0f 秒未返回：%s" % (timeout, " ".join(argv)),
            "确认手机仍在线（adb devices），或加大超时后重试"))
    except OSError as exc:
        raise IperfError(_msg("无法启动 adb", str(exc),
                              "检查 adb 可执行文件是否存在：" + argv[0]))
    out = (p.stdout or b"").decode("utf-8", "replace")
    err = (p.stderr or b"").decode("utf-8", "replace")
    return p.returncode, out, err


def _shell(serial, cmd, timeout=20):
    return _run([_adb_path(), "-s", serial, "shell", cmd], timeout)


def _shell_out(serial, cmd, timeout=20):
    """只要 stdout；rc 非 0 也返回已有输出（本模块大量命令允许失败）。"""
    _rc, out, _err = _shell(serial, cmd, timeout)
    return out


def probe_version(serial, path=PHONE_BIN):
    """返回手机上 iperf3 -v 的第一行，例如 'iperf 3.22 (cJSON 1.7.15)'；失败返回 ''。"""
    try:
        _rc, out, err = _shell(serial, "%s -v 2>&1" % path, timeout=15)
    except IperfError:
        return ""
    text = (out or "") + (err or "")
    for line in text.splitlines():
        line = line.strip()
        if line.lower().startswith("iperf"):
            return line
    return ""


def _remote_exists(serial, path):
    out = _shell_out(serial, "ls -l %s 2>/dev/null" % path, timeout=15)
    return bool(out.strip()) and "No such file" not in out


def ensure_phone_bin(serial, local_path, log=None):
    """确保手机上有 3.22 的 iperf3（3.1.x 做客户端必报 unable to create a new stream）。"""
    # 契约要求本地文件缺失/大小不对一律抛错，先校验再碰设备
    if not os.path.isfile(local_path):
        raise IperfError(_msg(
            "本地缺少手机端 iperf3 二进制",
            "找不到文件：%s（应为 iperf 3.22 的 arm64-v8a 静态二进制，%d 字节）"
            % (local_path, LOCAL_BIN_BYTES),
            "把 %s（%d 字节）放到 %s"
            % (LOCAL_BIN_NAME, LOCAL_BIN_BYTES, os.path.dirname(local_path) or ".")))
    size = os.path.getsize(local_path)
    if size != LOCAL_BIN_BYTES:
        raise IperfError(_msg(
            "本地 iperf3 二进制大小不对",
            "%s 是 %d 字节，应为 %d 字节；3.1.x 版本做客户端会报 "
            "unable to create a new stream" % (local_path, size, LOCAL_BIN_BYTES),
            "换成 iperf 3.22 的 arm64-v8a 版本（%d 字节）后重试" % LOCAL_BIN_BYTES))

    exists = _remote_exists(serial, PHONE_BIN)
    version = probe_version(serial) if exists else ""
    if exists and _GOOD_VERSION in version:
        _log(log, "  OK  手机端 iperf3 已就绪：%s" % version)
        return {"path": PHONE_BIN, "version": version, "pushed": False, "ok": True}

    _log(log, "  --  推送 %s -> %s" % (os.path.basename(local_path), PHONE_BIN))
    _rc, out, err = _run([_adb_path(), "-s", serial, "push", local_path, PHONE_BIN], 180)
    if _rc != 0:
        raise IperfError(_msg("推送 iperf3 失败", (err or out).strip(),
                              "确认手机可写 /data/local/tmp 且 adb 未被其它进程占用"))
    _shell_out(serial, "chmod 755 %s" % PHONE_BIN, timeout=15)

    exists = _remote_exists(serial, PHONE_BIN)
    version = probe_version(serial) if exists else ""
    ok = bool(exists and _GOOD_VERSION in version)
    if ok:
        _log(log, "  OK  推送完成：%s" % version)
    else:
        _log(log, "  !!  推送后版本仍不合格：%r" % (version or "无法执行"))
    return {"path": PHONE_BIN, "version": version, "pushed": True, "ok": ok}


# ------------------------------------------------------------------ server side

def is_listening(serial, port):
    """netstat -ltn 里是否有该端口处于 LISTEN。"""
    try:
        out = _shell_out(serial, "netstat -ltn 2>/dev/null | grep %d" % int(port), timeout=15)
    except IperfError:
        return False
    pat = re.compile(r"[:.]%d(?!\d)" % int(port))
    for line in out.splitlines():
        if "LISTEN" not in line.upper():
            continue
        fields = line.split()
        target = fields[3] if len(fields) > 3 else line
        if pat.search(target):
            return True
    return False


def stop_server(serial):
    """杀掉手机上所有 iperf3 进程（pkill 允许失败）。"""
    try:
        _shell_out(serial, "pkill -9 -f iperf3", timeout=15)
    except IperfError:
        return


def start_server(serial, port, log=None):
    """pkill -> 等 2s -> nohup 起 -s -> 等 3s -> netstat 确认监听。"""
    port = int(port)
    # 第 1 次独立 shell 调用：清掉旧进程（无进程时 pkill 返回 1，属正常）
    try:
        _shell_out(serial, "pkill -9 -f iperf3", timeout=15)
    except IperfError as exc:
        _log(log, "  --  pkill 未完成：%s" % exc)
    _log(log, "  --  等待旧服务端退出（2s）")
    time.sleep(2)

    # 第 2 次独立 shell 调用：后台起服务端（与 pkill 合并会失败）
    cmd = "nohup %s -s -p %d > %s 2>&1 &" % (PHONE_BIN, port, SERVER_LOG)
    try:
        _rc, out, err = _shell(serial, cmd, timeout=20)
    except IperfError as exc:
        return {"ok": False, "pid": "", "detail": str(exc)}
    noise = (out or "").strip() or (err or "").strip()
    _log(log, "  --  等待服务端监听（3s）")
    time.sleep(3)

    # 第 3 次独立 shell 调用：确认 LISTEN
    listening = is_listening(serial, port)
    detail = _shell_out(serial, "netstat -ltn 2>/dev/null | grep %d" % port, timeout=15).strip()
    if not detail:
        detail = _shell_out(serial, "cat %s 2>/dev/null" % SERVER_LOG, timeout=15).strip()
    pid = ""
    try:
        pid_out = _shell_out(serial, "pidof iperf3 2>/dev/null", timeout=15).split()
        if pid_out:
            pid = pid_out[0]
    except IperfError:
        pass
    if listening:
        _log(log, "  OK  服务端已监听 %d（pid %s）" % (port, pid or "?"))
    else:
        _log(log, "  !!  服务端未监听 %d：%s" % (port, noise or detail or "无输出"))
    return {"ok": listening, "pid": pid, "detail": detail or noise}


# -------------------------------------------------------------------- parsing

_ROW_RE = re.compile(
    r"^\[\s*(?P<stream>SUM|\d+)\]\s+"
    r"(?P<start>\d+(?:\.\d+)?)-(?P<end>\d+(?:\.\d+)?)\s+sec\s+"
    r"(?P<transfer>\d+(?:\.\d+)?)\s+(?P<unit>Bytes|KBytes|MBytes|GBytes)\s+"
    r"(?P<rate>\d+(?:\.\d+)?)\s+(?P<rate_unit>[KMGT]?bits/sec)"
    r"(?P<rest>.*)$")

_LOSS_RE = re.compile(r"^(\d+)/(\d+)$")
_PCT_RE = re.compile(r"^\((\d+(?:\.\d+)?)%\)$")
_INT_RE = re.compile(r"^\d+$")
_FLOAT_RE = re.compile(r"^\d+(?:\.\d+)?$")


def parse_line(line, test_id, kind, elapsed, tstamp):
    """把一行 iperf3 输出解析成契约 3.1 的 Record；不是数据行返回 None。"""
    raw = (line or "").strip("\r\n")
    m = _ROW_RE.match(raw.strip())
    if not m:
        return None

    stream = m.group("stream")
    rest = m.group("rest").split()

    role = ""
    if rest and rest[-1] in ("sender", "receiver"):
        role = rest.pop()

    rec = {
        "t": tstamp,
        "elapsed": round(float(elapsed), 2),
        "test": test_id,
        "kind": kind,
        "stream": "SUM" if stream == "SUM" else "#" + stream,
        "start": float(m.group("start")),
        "end": float(m.group("end")),
        "transfer": float(m.group("transfer")),
        "unit": m.group("unit"),
        "rate": float(m.group("rate")),
        "rate_unit": m.group("rate_unit"),
        "retr": "",
        "jitter": "",
        "lost": "",
        "total": "",
        "lost_pct": "",
        "role": role,
        "raw": raw,
    }

    if kind == "UDP":
        # 接收侧末尾多出 jitter/lost/total/百分比 三段：0.041 ms  1504/79796 (1.9%)
        if len(rest) >= 2 and rest[1] == "ms" and _FLOAT_RE.match(rest[0]):
            rec["jitter"] = float(rest[0])
            rest = rest[2:]
        if rest and _LOSS_RE.match(rest[0]):
            lost, total = _LOSS_RE.match(rest[0]).groups()
            rec["lost"] = int(lost)
            rec["total"] = int(total)
            rest = rest[1:]
        if rest and _PCT_RE.match(rest[0]):
            rec["lost_pct"] = float(_PCT_RE.match(rest[0]).group(1))
            rest = rest[1:]
        if not rec["total"] and rest and _INT_RE.match(rest[0]):
            # UDP sender 的第 11 个字段是 Total Datagrams，不是重传
            rec["total"] = int(rest[0])
        if role == "sender":
            # 契约 3.1：UDP sender 没有丢包率，这两项一律留空
            rec["lost"] = ""
            rec["lost_pct"] = ""
    else:
        if rest and _INT_RE.match(rest[0]):
            rec["retr"] = int(rest[0])

    return rec


def _summarize(records):
    """取 sender/receiver 汇总速率。有 [SUM] 行优先用 [SUM]；
    单流（-P 1）时 iperf3 不打 [SUM]，退化为最后一条带 role 的流汇总行。"""
    summary = {"sender": {"rate": 0.0, "unit": ""},
               "receiver": {"rate": 0.0, "unit": ""}}
    rank = {"sender": 0, "receiver": 0}
    for rec in records:
        role = rec.get("role")
        if role not in summary:
            continue
        pri = 2 if rec.get("stream") == "SUM" else 1
        if pri < rank[role]:
            continue
        rank[role] = pri
        summary[role] = {"rate": float(rec.get("rate") or 0.0),
                         "unit": rec.get("rate_unit") or ""}
    return summary


def _pick_error(raw_text, fallback):
    for line in (raw_text or "").splitlines():
        low = line.lower()
        if "iperf3: error" in low or low.startswith("error") or "unable to" in low:
            return line.strip()
    return fallback


# --------------------------------------------------------------------- client

def _client_argv(server_ip, port, duration, streams, reverse, udp, udp_bw):
    argv = [PHONE_BIN, "-c", str(server_ip), "-p", str(int(port)),
            "-t", str(int(duration)), "-P", str(int(streams))]
    if reverse:
        argv.append("-R")
    if udp:
        argv.extend(["-u", "-b", str(udp_bw)])
    argv.append("--forceflush")
    return argv


def run_client(serial, server_ip, port, *, duration, streams=1, reverse=False,
               udp=False, udp_bw="50M", test_id="tcp_up",
               on_record=None, on_raw=None, log=None):
    """手机上跑 iperf3 客户端，逐行实时回调。返回契约 4.3 规定的 dict。"""
    kind = "UDP" if udp else "TCP"
    safe_id = re.sub(r"[^\w.\-]", "_", str(test_id)) or "run"
    remote = "/data/local/tmp/iperf_%s.txt" % safe_id
    argv = _client_argv(server_ip, port, duration, streams, reverse, udp, udp_bw)
    shell_cmd = "%s 2>&1 | tee %s" % (" ".join(argv), remote)

    _log(log, "  --  %s %s" % (kind, " ".join(argv[1:])))
    result = {"ok": False, "test_id": test_id, "raw_path_phone": remote,
              "raw_text": "", "summary": _summarize([]), "error": ""}

    adb = _adb_path()
    try:
        proc = subprocess.Popen(
            [adb, "-s", serial, "shell", shell_cmd],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace", bufsize=1)
    except OSError as exc:
        result["error"] = _msg("无法启动 adb 客户端进程", str(exc),
                               "检查 adb 路径：" + adb)
        return result

    deadline = int(duration) + 90
    killer = threading.Timer(deadline, _hard_kill, args=(proc,))
    killer.daemon = True
    killer.start()

    records = []
    lines = []
    t0 = time.monotonic()
    try:
        # 逐行流式读取；绝不用 communicate()
        for line in proc.stdout:
            now = time.time()
            tstamp = time.strftime("%H:%M:%S", time.localtime(now))
            elapsed = round(time.monotonic() - t0, 2)
            text = line.rstrip("\r\n")
            if not text.strip():
                continue
            lines.append(text)
            rec = parse_line(text, test_id, kind, elapsed, tstamp)
            if rec is not None:
                records.append(rec)
                if callable(on_record):
                    try:
                        on_record(rec)
                    except Exception as exc:
                        _log(log, "  !!  on_record 回调异常：%s" % exc)
            elif callable(on_raw):
                try:
                    on_raw(text)
                except Exception as exc:
                    _log(log, "  !!  on_raw 回调异常：%s" % exc)
    finally:
        killer.cancel()
        try:
            proc.wait(timeout=15)
        except subprocess.TimeoutExpired:
            _hard_kill(proc)

    # 退出码被管道吞掉恒为 0，只能靠落盘文件判定
    raw_text = ""
    try:
        _rc, out, err = _shell(serial, "cat %s 2>/dev/null" % remote, timeout=25)
        raw_text = out or ""
    except IperfError as exc:
        _log(log, "  !!  读取落盘文件失败：%s" % exc)

    result["raw_text"] = raw_text
    result["summary"] = _summarize(records)

    if _DONE_MARK in raw_text:
        result["ok"] = True
    else:
        joined = "\n".join(lines)
        if not raw_text.strip() and not joined.strip():
            result["error"] = _msg(
                "没有收到任何 iperf3 输出",
                "adb shell 无输出，手机端进程可能未启动",
                "确认手机在线（adb devices）且 %s 可执行（chmod 755）" % PHONE_BIN)
        else:
            result["error"] = _pick_error(
                raw_text or joined,
                "落盘文件 %s 中没有 iperf Done. 标记，判定为失败" % remote)
    return result


def _hard_kill(proc):
    try:
        proc.kill()
    except Exception:
        pass

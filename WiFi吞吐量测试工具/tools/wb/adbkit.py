# -*- coding: utf-8 -*-
"""adb 封装：定位 adb、执行命令、设备枚举、文件推送、端口转发。"""

import os
import re
import shutil
import subprocess

DEFAULT_ADB_PATH = r"C:\Program Files\ADB\adb.exe"
DEFAULT_TIMEOUT = 20.0
PUSH_TIMEOUT = 180.0

KNOWN_STATES = (
    "device",
    "unauthorized",
    "offline",
    "recovery",
    "sideload",
    "bootloader",
    "host",
    "rescue",
)


class AdbError(RuntimeError):
    """adb 调用失败（找不到 adb、非 0 退出、设备无响应等）。"""


class AdbTimeout(AdbError):
    """adb 调用超时。"""


def find_adb():
    """返回 adb.exe 绝对路径。顺序：环境变量 ADB -> 默认安装路径 -> PATH。"""
    tried = []

    env = os.environ.get("ADB", "").strip().strip('"')
    if env:
        tried.append(env)
        if os.path.isfile(env):
            return os.path.abspath(env)
        as_dir = os.path.join(env, "adb.exe")
        tried.append(as_dir)
        if os.path.isfile(as_dir):
            return os.path.abspath(as_dir)

    tried.append(DEFAULT_ADB_PATH)
    if os.path.isfile(DEFAULT_ADB_PATH):
        return os.path.abspath(DEFAULT_ADB_PATH)

    tried.append("PATH 上的 adb")
    found = shutil.which("adb")
    if found:
        return os.path.abspath(found)

    raise AdbError(
        "[错误] 找不到 adb.exe\n"
        "原因：环境变量 ADB、默认安装路径、PATH 三处都没有可用的 adb\n"
        "处理办法：安装 Android Platform-Tools，或把 adb.exe 放到 %s，"
        "或设置环境变量 ADB 指向 adb.exe 的完整路径\n"
        "已尝试的位置：\n  %s"
        % (DEFAULT_ADB_PATH, "\n  ".join(tried))
    )


def _decode(raw):
    for enc in ("utf-8", "gbk"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            continue
    return raw.decode("utf-8", "replace")


def _quote(text):
    """POSIX 单引号转义，用于把整段命令塞进 sh -c。"""
    return "'" + str(text).replace("'", "'\\''") + "'"


def _build_cmd(args, serial):
    cmd = [find_adb()]
    if serial:
        cmd.extend(["-s", str(serial)])
    cmd.extend(str(a) for a in args)
    return cmd


def _exec(cmd, timeout):
    """执行命令，返回 (rc, stdout_bytes, stderr_bytes)。超时抛 AdbTimeout。"""
    try:
        proc = subprocess.run(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
        )
    except subprocess.TimeoutExpired:
        raise AdbTimeout(
            "[错误] adb 执行超过 %.1f 秒仍未返回\n"
            "原因：手机端命令卡住、数据线/无线连接不稳定，或设备未响应\n"
            "处理办法：确认手机屏幕已解锁且 adb 授权有效；重试一次；"
            "仍超时就把 timeout 调大或重新插拔数据线\n"
            "命令：%s" % (float(timeout), " ".join(cmd))
        )
    except FileNotFoundError as exc:
        raise AdbError(
            "[错误] 无法执行 adb\n"
            "原因：adb 路径不存在或没有执行权限（%s）\n"
            "处理办法：检查环境变量 ADB 与安装路径是否正确\n"
            "命令：%s" % (exc, " ".join(cmd))
        )
    except OSError as exc:
        raise AdbError(
            "[错误] adb 启动失败\n"
            "原因：操作系统拒绝启动该进程（%s）\n"
            "处理办法：确认 adb.exe 未被安全软件拦截，并检查路径是否含非法字符\n"
            "命令：%s" % (exc, " ".join(cmd))
        )
    return proc.returncode, proc.stdout, proc.stderr


def adb(args, timeout=DEFAULT_TIMEOUT, serial=None, binary=False):
    """执行 adb 命令，返回 stdout 文本（binary=True 时返回 bytes）。
    非 0 退出抛 AdbError，超时抛 AdbTimeout。"""
    cmd = _build_cmd(args, serial)
    rc, out, err = _exec(cmd, timeout)
    if rc != 0:
        detail = _decode(err).strip() or _decode(out).strip()
        raise AdbError(
            "[错误] adb 命令返回非 0 退出码（rc=%d）\n"
            "原因：%s\n"
            "处理办法：按上面的原因处理；若是设备未授权，请在手机上点「允许 USB 调试」"
            "后重试\n"
            "命令：%s" % (rc, detail or "adb 未给出说明", " ".join(cmd))
        )
    return out if binary else _decode(out)


def shell(serial, cmd, timeout=DEFAULT_TIMEOUT):
    """adb -s <serial> shell <cmd>，返回 stdout 文本（去掉行尾换行）。"""
    return adb(["shell", cmd], timeout=timeout, serial=serial).rstrip("\r\n")


_RC_RE = re.compile(r"__RC__(\d+)\s*$")


def shell_rc(serial, cmd, timeout=DEFAULT_TIMEOUT):
    """返回 (rc, out)。用 sh -c 'cmd; echo __RC__$?' 取手机侧真实退出码。"""
    script = "sh -c " + _quote(cmd + "; echo __RC__$?")
    rc, out, _err = _exec(_build_cmd(["shell", script], serial), timeout)
    text = _decode(out)
    real_rc = rc
    lines = text.splitlines()
    for idx in range(len(lines) - 1, -1, -1):
        hit = _RC_RE.search(lines[idx].strip())
        if hit:
            real_rc = int(hit.group(1))
            del lines[idx]
            break
    return real_rc, "\n".join(lines)


def list_devices():
    """解析 adb devices -l，返回 [{serial,state,model,product,device,transport_id}]，
    保持 adb 输出顺序。"""
    text = adb(["devices", "-l"])
    devices = []
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("List of devices") or line.startswith("*"):
            continue
        parts = line.split()
        if len(parts) < 2 or parts[1] not in KNOWN_STATES:
            continue
        item = {
            "serial": parts[0],
            "state": parts[1],
            "model": "",
            "product": "",
            "device": "",
            "transport_id": "",
        }
        for token in parts[2:]:
            if ":" not in token:
                continue
            key, value = token.split(":", 1)
            if key in ("model", "product", "device", "transport_id"):
                item[key] = value
        item["model"] = item["model"].replace("_", " ")
        devices.append(item)
    return devices


def push(serial, local, remote, timeout=PUSH_TIMEOUT):
    """把本地文件推到手机。"""
    out = adb(["push", local, remote], timeout=timeout, serial=serial)
    low = out.lower()
    if "adb: error" in low or "failed" in low:
        raise AdbError("push 失败：%s -> %s\n%s" % (local, remote, out.strip()))


def chmod(serial, remote, mode="755"):
    """给手机上的文件加执行权限。"""
    shell(serial, "chmod %s %s" % (mode, _quote(remote)))


def forward(serial, local_port, remote_port):
    """PC 端口 -> 手机端口。"""
    adb(["forward", "tcp:%d" % int(local_port), "tcp:%d" % int(remote_port)], serial=serial)


def reverse(serial, remote_port, local_port):
    """手机端口 -> PC 端口。"""
    adb(["reverse", "tcp:%d" % int(remote_port), "tcp:%d" % int(local_port)], serial=serial)


def remove_forwards(serial=None):
    """清掉指定设备的全部 forward/reverse；serial 为空则清全局。失败静默。"""
    for args in (["forward", "--remove-all"], ["reverse", "--remove-all"]):
        try:
            adb(args, serial=serial)
        except AdbError:
            pass

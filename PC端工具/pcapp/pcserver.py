# -*- coding: utf-8 -*-
"""本机 iperf3 服务端 + UDP 8738 发现/空闲探测。

「连PC」模式需要 PC 同时扮演两个角色：
1. iperf3 服务端（默认 5201），手机作为客户端跑下载/上传；
2. UDP 8738 应答：手机广播发现时回 IP/端口/版本，空闲探测时回 PONG。
"""

import os
import socket
import subprocess
import threading
import time

from . import paths

UDP_PORT = 8738
ASK = "WIFITPUT/DISCOVER/1"
HERE = "WIFITPUT/HERE/1"
PING = "WIFITPUT/PING/1"
PONG = "WIFITPUT/PONG/1"


class PcServerError(RuntimeError):
    """启动失败。消息是给人看的中文说明。"""


def _no_window():
    return getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0


def default_label():
    name = socket.gethostname() or "PC"
    name = name.replace("|", "/").replace("\r", " ").replace("\n", " ").strip()
    return "PC · " + (name[:40] or "PC")


class PcServer:
    """一个后台 iperf3 -s 进程 + 一个 UDP 应答线程。start/stop 可反复调用。"""

    def __init__(self, on_log=None):
        self.on_log = on_log
        self.port = 5201
        self.http_port = 0
        self.label = default_label()
        self.version = ""
        self.log_path = os.path.join(paths.root_dir(), "iperf3_server.log")
        self.udp_error = ""
        self._proc = None
        self._log_file = None
        self._udp = None
        self._udp_thread = None
        self._stop = threading.Event()

    @property
    def running(self):
        return self._proc is not None and self._proc.poll() is None

    # ---------------- 启动 / 停止 ----------------

    def exe_path(self):
        return paths.pc_iperf3_path()

    def detect_version(self):
        exe = self.exe_path()
        if not exe:
            return ""
        try:
            out = subprocess.run([exe, "-v"], capture_output=True, text=True,
                                 timeout=8, creationflags=_no_window()).stdout
            for line in (out or "").splitlines():
                if line.strip().startswith("iperf 3"):
                    return line.strip()
        except Exception:
            pass
        return ""

    def set_http_port(self, port):
        self.http_port = int(port or 0)

    def start(self, port=5201, http_port=None):
        exe = self.exe_path()
        if not exe:
            raise PcServerError(
                "找不到 iperf3.exe。请放到 PC端工具\\bin\\win\\iperf3.exe，"
                "或保持同级「WiFi吞吐量测试工具」目录完整。")
        self.port = int(port or 5201)
        if http_port is not None:
            self.http_port = int(http_port or 0)
        if self.running:
            return

        self._stop.set()
        self._stop_udp()
        self._stop.clear()
        self._stop_proc(quiet=True)

        try:
            if os.path.isfile(self.log_path):
                os.remove(self.log_path)
        except OSError:
            pass
        try:
            self._log_file = open(self.log_path, "ab", buffering=0)
            self._proc = subprocess.Popen(
                [exe, "-s", "-p", str(self.port), "--forceflush"],
                stdout=self._log_file, stderr=subprocess.STDOUT,
                creationflags=_no_window())
        except OSError as exc:
            self._close_log()
            raise PcServerError("启动 iperf3 失败：%s" % exc)

        deadline = time.time() + 6.0
        while time.time() < deadline:
            if self._proc.poll() is not None:
                tail = self.tail_log(6)
                self._stop_proc(quiet=True)
                raise PcServerError("iperf3 服务端启动后立即退出。%s" % (tail or ""))
            if _listening(self.port):
                self.version = self.detect_version()
                self._start_udp()
                self._log("iperf3 服务端已启动，端口 %d%s"
                          % (self.port, ("，" + self.version) if self.version else ""))
                return
            time.sleep(0.2)

        tail = self.tail_log(4)
        self._stop_proc(quiet=True)
        raise PcServerError("iperf3 在 6 秒内没有监听端口 %d，可能端口被占用。%s"
                            % (self.port, tail or ""))

    def stop(self, quiet=False):
        self._stop.set()
        self._stop_udp()
        self._stop_proc(quiet=quiet)
        if not quiet:
            self._log("本机服务端已停止")

    def _stop_proc(self, quiet=False):
        proc = self._proc
        self._proc = None
        if proc is not None:
            try:
                proc.terminate()
                proc.wait(timeout=3)
            except Exception:
                try:
                    proc.kill()
                except Exception:
                    pass
        self._close_log()

    def _close_log(self):
        f = self._log_file
        self._log_file = None
        if f is not None:
            try:
                f.close()
            except Exception:
                pass

    # ---------------- UDP 发现 / 探测 ----------------

    def _start_udp(self):
        self._stop_udp()
        self._stop.clear()
        self.udp_error = ""
        th = threading.Thread(target=self._udp_loop, name="pc-udp", daemon=True)
        self._udp_thread = th
        th.start()

    def _stop_udp(self):
        s = self._udp
        self._udp = None
        if s is not None:
            try:
                s.close()
            except Exception:
                pass
        self._udp_thread = None

    def _udp_loop(self):
        s = None
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            s.bind(("", UDP_PORT))
            s.settimeout(0.5)
            self._udp = s
            while not self._stop.is_set():
                try:
                    data, addr = s.recvfrom(1024)
                except socket.timeout:
                    continue
                except OSError:
                    break
                msg = data.decode("utf-8", "ignore").strip()
                if msg == ASK:
                    out = "%s|%s|%d|%s|%d|PC" % (
                        HERE, self.label, self.port,
                        self.version or "", self.http_port or 0)
                    try:
                        s.sendto(out.encode("utf-8"), addr)
                    except OSError:
                        pass
                elif msg.startswith(PING):
                    nonce = msg[len(PING):].lstrip("|").strip()
                    try:
                        s.sendto((PONG + "|" + nonce).encode("utf-8"), addr)
                    except OSError:
                        pass
        except OSError as exc:
            self.udp_error = "UDP %d 无法监听：%s" % (UDP_PORT, exc)
            self._log("发现/探测服务启动失败：" + self.udp_error)
        finally:
            if s is not None:
                try:
                    s.close()
                except Exception:
                    pass
            if self._udp is s:
                self._udp = None

    # ---------------- 杂项 ----------------

    def tail_log(self, lines=30):
        try:
            if not os.path.isfile(self.log_path):
                return ""
            with open(self.log_path, "r", encoding="utf-8", errors="replace") as f:
                data = f.readlines()
            return "".join(data[-max(1, int(lines)):]).strip()
        except OSError:
            return ""

    def read_log(self, max_bytes=131072):
        try:
            if not os.path.isfile(self.log_path):
                return ""
            size = os.path.getsize(self.log_path)
            with open(self.log_path, "r", encoding="utf-8", errors="replace") as f:
                if size > max_bytes:
                    f.seek(size - max_bytes)
                return f.read()
        except OSError:
            return ""

    def _log(self, msg):
        if self.on_log:
            try:
                self.on_log(msg)
            except Exception:
                pass


def _listening(port):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(0.4)
    try:
        s.connect(("127.0.0.1", int(port)))
        return True
    except OSError:
        return False
    finally:
        try:
            s.close()
        except Exception:
            pass

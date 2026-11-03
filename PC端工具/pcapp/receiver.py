# -*- coding: utf-8 -*-
"""本地 HTTP 接收服务：手机端把结果包 POST 过来。

只监听局域网，不做鉴权（同一 WiFi 下才可达），但会校验来源与体积上限，
避免一个畸形请求把界面拖死。所有回调都在后台线程，界面侧要自己切回主线程。
"""

import json
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from . import protocol

MAX_BODY = 32 * 1024 * 1024      # 一次结果包上限 32 MB，超过直接 413
TIMEOUT_S = 20                    # 单连接读超时，防止半开连接把线程占住


def local_ips():
    """本机所有可能的局域网 IPv4，供界面显示「让手机连这个地址」。"""
    ips = []
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip and ip not in ips and not ip.startswith("127."):
                ips.append(ip)
    except OSError:
        pass
    if not ips:
        # gethostname 解析不出来时（常见于改了主机名没重启），用 UDP 探一次出口地址
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.settimeout(1.0)
            s.connect(("223.5.5.5", 53))
            ips.append(s.getsockname()[0])
            s.close()
        except OSError:
            pass
    return ips


class _Handler(BaseHTTPRequestHandler):
    server_version = "WiFiTputPC/1.0"
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        """默认实现往 stderr 打访问日志，在 GUI 里会刷屏，改成走回调。"""
        cb = getattr(self.server, "on_log", None)
        if cb:
            try:
                cb("%s - %s" % (self.address_string(), fmt % args))
            except Exception:
                pass

    def _send(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        """手机端如果用 WebView 或浏览器调试会先发预检。"""
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "POST, GET, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):
        path = self.path.split("?")[0].rstrip("/")
        if path == protocol.PATH_PING:
            self._send(200, {
                "ok": True, "schema": protocol.SCHEMA, "app": "WiFiTputPC",
                "time": time.strftime("%Y-%m-%d %H:%M:%S"),
                "modes": list(protocol.MODES),
            })
            return
        self._send(404, {"ok": False, "error": "未知路径 %s" % self.path})

    def do_POST(self):
        path = self.path.split("?")[0].rstrip("/")
        if path != protocol.PATH_RESULTS:
            self._send(404, {"ok": False, "error": "未知路径 %s" % self.path})
            return
        try:
            n = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            n = 0
        if n <= 0:
            self._send(411, {"ok": False, "error": "缺少 Content-Length"})
            return
        if n > MAX_BODY:
            self._send(413, {"ok": False,
                             "error": "结果包过大（%d 字节，上限 %d）" % (n, MAX_BODY)})
            return

        raw = b""
        try:
            self.connection.settimeout(TIMEOUT_S)
            while len(raw) < n:
                chunk = self.rfile.read(min(65536, n - len(raw)))
                if not chunk:
                    break
                raw += chunk
        except OSError as exc:
            self._send(400, {"ok": False, "error": "读请求体失败：%s" % exc})
            return

        if len(raw) != n:
            self._send(400, {"ok": False,
                             "error": "请求体不完整（收到 %d / 声明 %d）" % (len(raw), n)})
            return

        try:
            bundle = json.loads(raw.decode("utf-8-sig"))
        except (UnicodeDecodeError, ValueError) as exc:
            self._send(400, {"ok": False, "error": "不是合法的 UTF-8 JSON：%s" % exc})
            return

        try:
            bundle = protocol.validate(bundle)
        except protocol.BundleError as exc:
            self._send(422, {"ok": False, "error": str(exc)})
            return

        on_result = getattr(self.server, "on_result", None)
        if on_result is None:
            self._send(503, {"ok": False, "error": "接收器尚未就绪"})
            return
        try:
            info = on_result(bundle, self.client_address[0])
        except Exception as exc:
            self._send(500, {"ok": False, "error": "接收器处理失败：%s" % exc})
            return

        resp = {"ok": True, "saved_to": (info or {}).get("run_dir", ""),
                "files": (info or {}).get("files", [])}
        self._send(200, resp)


class Receiver:
    """后台线程里跑的接收服务。start()/stop() 可反复调用。"""

    def __init__(self, on_result=None, on_log=None):
        self.on_result = on_result
        self.on_log = on_log
        self._srv = None
        self._thread = None
        self.port = 0

    @property
    def running(self):
        return self._srv is not None

    def start(self, port=8737):
        """启动服务，返回实际端口。已在跑就直接返回现有端口。"""
        if self._srv is not None:
            return self.port
        try:
            srv = ThreadingHTTPServer(("0.0.0.0", int(port)), _Handler)
        except OSError as exc:
            raise ReceiverError("端口 %d 无法监听：%s" % (port, exc))
        srv.daemon_threads = True
        srv.on_result = self.on_result
        srv.on_log = self.on_log
        th = threading.Thread(target=srv.serve_forever, kwargs={"poll_interval": 0.3},
                              name="receiver", daemon=True)
        th.start()
        self._srv, self._thread, self.port = srv, th, int(port)
        self._log("接收服务已启动，端口 %d" % self.port)
        return self.port

    def stop(self):
        if self._srv is None:
            return
        try:
            self._srv.shutdown()
            self._srv.server_close()
        except Exception:
            pass
        self._srv = None
        self._thread = None
        self._log("接收服务已停止")

    def _log(self, msg):
        if self.on_log:
            try:
                self.on_log(msg)
            except Exception:
                pass


class ReceiverError(RuntimeError):
    """端口被占用等启动失败。消息是给人看的中文说明。"""


def free_port(preferred=8737, span=20):
    """从 preferred 开始找一个能绑的端口。全被占返回 preferred。"""
    for p in range(int(preferred), int(preferred) + int(span)):
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            s.bind(("0.0.0.0", p))
            return p
        except OSError:
            continue
        finally:
            s.close()
    return int(preferred)

# -*- coding: utf-8 -*-
"""本机服务端页：一键启动 iperf3 服务端，供手机「连PC」模式使用。"""

import os
import subprocess

from PySide6 import QtCore, QtGui, QtWidgets

from .. import pcserver, receiver, store
from .widgets import card


class ServerTab(QtWidgets.QWidget):

    def __init__(self, ctx, parent=None):
        super().__init__(parent)
        self.ctx = ctx
        self._last_log = None
        self._build()
        self._timer = QtCore.QTimer(self)
        self._timer.setInterval(1000)
        self._timer.timeout.connect(self._tick)
        self._timer.start()
        self.refresh()

    # ---------------- 界面 ----------------

    def _build(self):
        root = QtWidgets.QVBoxLayout(self)
        root.setContentsMargins(10, 10, 10, 10)
        root.setSpacing(8)

        box, lay = card("本机 iperf3 服务端")
        row = QtWidgets.QHBoxLayout()
        row.setSpacing(8)
        self.lbl_state = QtWidgets.QLabel("--")
        self.lbl_state.setMinimumWidth(180)
        self.btn_start = QtWidgets.QPushButton("启动服务端")
        self.btn_start.setFixedWidth(108)
        self.btn_start.clicked.connect(self.start)
        self.btn_stop = QtWidgets.QPushButton("停止")
        self.btn_stop.setFixedWidth(72)
        self.btn_stop.clicked.connect(self.stop)
        row.addWidget(QtWidgets.QLabel("状态"))
        row.addWidget(self.lbl_state)
        row.addStretch(1)
        row.addWidget(self.btn_start)
        row.addWidget(self.btn_stop)
        lay.addLayout(row)

        port_row = QtWidgets.QHBoxLayout()
        port_row.setSpacing(8)
        self.spin_port = QtWidgets.QSpinBox()
        self.spin_port.setRange(1, 65535)
        self.spin_port.setValue(int(self.ctx.cfg.get("iperf_port") or 5201))
        self.spin_port.setFixedWidth(96)
        port_row.addWidget(QtWidgets.QLabel("iperf3 端口"))
        port_row.addWidget(self.spin_port)
        port_row.addStretch(1)
        lay.addLayout(port_row)

        recv_row = QtWidgets.QHBoxLayout()
        recv_row.setSpacing(8)
        self.lbl_recv = QtWidgets.QLabel("--")
        self.lbl_recv.setMinimumWidth(220)
        self.btn_recv = QtWidgets.QPushButton("启动接收")
        self.btn_recv.setFixedWidth(96)
        self.btn_recv.clicked.connect(self.start_receiver)
        recv_row.addWidget(QtWidgets.QLabel("结果接收"))
        recv_row.addWidget(self.lbl_recv)
        recv_row.addStretch(1)
        recv_row.addWidget(self.btn_recv)
        lay.addLayout(recv_row)

        self.lbl_addr = QtWidgets.QLabel("--")
        self.lbl_addr.setWordWrap(True)
        self.lbl_addr.setTextInteractionFlags(QtCore.Qt.TextSelectableByMouse)
        lay.addWidget(self.lbl_addr)

        tip = QtWidgets.QLabel(
            "手机「连PC」页会扫描这个服务端。第一次启动时 Windows 可能弹「允许访问」，"
            "请允许专用网络；若手机扫描不到，在防火墙入站规则里放行 TCP 端口和 UDP 8738。")
        tip.setWordWrap(True)
        tip.setStyleSheet("color:#666;")
        lay.addWidget(tip)

        btn_fw = QtWidgets.QPushButton("打开防火墙设置")
        btn_fw.setFixedWidth(140)
        btn_fw.clicked.connect(self.open_firewall)
        lay.addWidget(btn_fw, 0, QtCore.Qt.AlignLeft)
        root.addWidget(box)

        box2, lay2 = card("服务端日志（实时）")
        self.log = QtWidgets.QPlainTextEdit()
        self.log.setReadOnly(True)
        self.log.setLineWrapMode(QtWidgets.QPlainTextEdit.NoWrap)
        self.log.setMaximumBlockCount(5000)
        f = QtGui.QFont("Consolas", 9)
        f.setStyleHint(QtGui.QFont.Monospace)
        self.log.setFont(f)
        lay2.addWidget(self.log, 1)
        root.addWidget(box2, 1)

    # ---------------- 动作 ----------------

    def start(self):
        if self.ctx.pcserver.running:
            return
        http_port = 0
        try:
            http_port = self._ensure_receiver()
        except Exception as exc:
            self.ctx.bus.log.emit("HTTP 接收服务没起来：%s（手机仍可测试，但结果不会自动上传）"
                                  % exc)
        self.ctx.pcserver.set_http_port(http_port)
        port = int(self.spin_port.value())
        self.ctx.cfg["iperf_port"] = port
        store.save_config(self.ctx.cfg)
        try:
            self.ctx.pcserver.start(port)
        except Exception as exc:
            QtWidgets.QMessageBox.critical(self, "启动本机服务端失败", str(exc))
            self.refresh()
            return
        self.ctx.bus.log.emit("本机服务端已启动，等待手机连接")
        self.refresh()

    def stop(self):
        self.ctx.pcserver.stop()
        self.refresh()

    def start_receiver(self):
        try:
            port = self._ensure_receiver()
            self.ctx.bus.log.emit("结果接收服务已在端口 %d 监听" % port)
        except Exception as exc:
            QtWidgets.QMessageBox.critical(self, "启动接收失败", str(exc))
        self.refresh()

    def _ensure_receiver(self):
        if self.ctx.receiver.running:
            return self.ctx.receiver.port
        port = int(self.ctx.cfg.get("port") or 8737)
        try:
            real = self.ctx.receiver.start(port)
        except receiver.ReceiverError:
            alt = receiver.free_port(port + 1, 20)
            real = self.ctx.receiver.start(alt)
            self.ctx.cfg["port"] = alt
            store.save_config(self.ctx.cfg)
        self.ctx.bus.receiver_changed.emit()
        return real

    def open_firewall(self):
        try:
            if os.name == "nt":
                subprocess.Popen(["control.exe", "/name", "Microsoft.WindowsFirewall"])
            else:
                QtWidgets.QMessageBox.information(
                    self, "防火墙", "请手动放行 TCP iperf3 端口和 UDP 8738。")
        except Exception as exc:
            QtWidgets.QMessageBox.information(self, "打不开防火墙设置", str(exc))

    # ---------------- 状态 ----------------

    def refresh(self):
        on = self.ctx.pcserver.running
        self.lbl_state.setText("● 正在运行" if on else "○ 未启动")
        self.lbl_state.setStyleSheet(
            "color:#1a7f37;font-weight:bold;" if on else "color:#999;")
        self.btn_start.setEnabled(not on)
        self.btn_stop.setEnabled(on)
        self.spin_port.setEnabled(not on)

        recv_on = self.ctx.receiver.running
        self.lbl_recv.setText(("● 端口 %d" % self.ctx.receiver.port) if recv_on else "○ 未启动")
        self.lbl_recv.setStyleSheet(
            "color:#1a7f37;font-weight:bold;" if recv_on else "color:#999;")
        self.btn_recv.setEnabled(not recv_on)

        ips = receiver.local_ips()
        lines = []
        if on:
            if ips:
                lines.extend(["iperf3  %s:%d（手机连这个地址）" % (ip, self.ctx.pcserver.port)
                              for ip in ips])
            else:
                lines.append("没有检测到局域网 IPv4 地址")
            lines.append("UDP 8738  发现/空闲探测%s"
                         % ("（" + self.ctx.pcserver.udp_error + "）"
                            if self.ctx.pcserver.udp_error else ""))
        else:
            lines.append("启动后这里会显示手机可连的地址")
        if recv_on:
            for ip in ips:
                lines.append("HTTP  %s:%d（结果回传）" % (ip, self.ctx.receiver.port))
        if self.ctx.pcserver.version:
            lines.append("版本  " + self.ctx.pcserver.version)
        self.lbl_addr.setText("\n".join(lines))

    def _tick(self):
        on = self.ctx.pcserver.running
        # 用 1 秒轮询兜底状态变化（例如进程自己退出）
        self.refresh()
        text = self.ctx.pcserver.read_log() if (on or self._last_log) else ""
        if text != self._last_log:
            self._last_log = text
            bar = self.log.verticalScrollBar()
            stick = bar.value() >= bar.maximum() - 4
            self.log.setPlainText(text)
            if stick:
                bar.setValue(bar.maximum())

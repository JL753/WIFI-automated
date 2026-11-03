# -*- coding: utf-8 -*-
"""Tab 1：收结果。起接收服务、看实时日志、列出已收到的结果。"""

import os
import threading
import urllib.error
import urllib.request

from PySide6 import QtCore, QtGui, QtWidgets

from .. import protocol, receiver, reports, store
from .widgets import card, human_size

COLUMNS = ["接收时间", "模式", "设备", "测试项", "逐秒行", "来源 IP", "大小"]


class ReceiveTab(QtWidgets.QWidget):

    # 模拟发送在后台线程跑，回来要切到主线程再动按钮
    sim_done = QtCore.Signal()

    def __init__(self, ctx, parent=None):
        super().__init__(parent)
        self.ctx = ctx
        self._build()
        self.sim_done.connect(self._on_sim_done)
        self.refresh_table()
        self._update_status()
        if self.ctx.cfg.get("auto_start"):
            self.start()

    # ---------------- 界面 ----------------

    def _build(self):
        root = QtWidgets.QVBoxLayout(self)
        root.setContentsMargins(10, 10, 10, 10)
        root.setSpacing(8)

        box, lay = card("接收服务")
        row = QtWidgets.QHBoxLayout()
        row.setSpacing(8)
        self.lbl_state = QtWidgets.QLabel("--")
        self.lbl_state.setMinimumWidth(150)
        self.btn_start = QtWidgets.QPushButton("启动接收")
        self.btn_start.setFixedWidth(96)
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

        self.lbl_addr = QtWidgets.QLabel("--")
        self.lbl_addr.setWordWrap(True)
        self.lbl_addr.setTextInteractionFlags(QtCore.Qt.TextSelectableByMouse)
        lay.addWidget(self.lbl_addr)

        tip = QtWidgets.QLabel(
            "手机端扫描同一 WiFi 下的这台电脑，或直接填上面的地址，即可把结果推过来。")
        tip.setStyleSheet("color:#666;")
        lay.addWidget(tip)
        root.addWidget(box)

        box2, lay2 = card("已接收的结果（双击打开报告）")
        self.table = QtWidgets.QTableWidget(0, len(COLUMNS))
        self.table.setHorizontalHeaderLabels(COLUMNS)
        self.table.setSelectionBehavior(QtWidgets.QAbstractItemView.SelectRows)
        self.table.setSelectionMode(QtWidgets.QAbstractItemView.SingleSelection)
        self.table.setEditTriggers(QtWidgets.QAbstractItemView.NoEditTriggers)
        self.table.verticalHeader().setVisible(False)
        self.table.setAlternatingRowColors(True)
        self.table.horizontalHeader().setStretchLastSection(True)
        self.table.doubleClicked.connect(self.open_report)
        lay2.addWidget(self.table, 1)

        bar = QtWidgets.QHBoxLayout()
        bar.setSpacing(6)
        self.btn_sim = QtWidgets.QPushButton("模拟发送（不用手机先验证链路）")
        self.btn_sim.clicked.connect(self.simulate)
        self.btn_open_dir = QtWidgets.QPushButton("打开结果目录")
        self.btn_open_dir.clicked.connect(self.open_dir)
        self.btn_del = QtWidgets.QPushButton("从列表移除")
        self.btn_del.clicked.connect(self.remove_entry)
        bar.addWidget(self.btn_sim)
        bar.addStretch(1)
        bar.addWidget(self.btn_open_dir)
        bar.addWidget(self.btn_del)
        lay2.addLayout(bar)
        root.addWidget(box2, 1)

    # ---------------- 服务 ----------------

    def start(self):
        if self.ctx.receiver.running:
            return
        port = int(self.ctx.cfg.get("port") or 8737)
        try:
            real = self.ctx.receiver.start(port)
        except receiver.ReceiverError as exc:
            alt = receiver.free_port(port + 1, 20)
            ret = QtWidgets.QMessageBox.question(
                self, "端口被占用",
                "%s\n\n换成端口 %d 再试？" % (exc, alt),
                QtWidgets.QMessageBox.Yes | QtWidgets.QMessageBox.No)
            if ret != QtWidgets.QMessageBox.Yes:
                return
            try:
                real = self.ctx.receiver.start(alt)
            except receiver.ReceiverError as exc2:
                QtWidgets.QMessageBox.critical(self, "启动失败", str(exc2))
                return
            self.ctx.cfg["port"] = alt
            store.save_config(self.ctx.cfg)
        self._update_status()

    def stop(self):
        self.ctx.receiver.stop()
        self._update_status()

    def _update_status(self):
        on = self.ctx.receiver.running
        self.lbl_state.setText("● 正在接收（端口 %d）" % self.ctx.receiver.port if on
                               else "○ 未启动")
        self.lbl_state.setStyleSheet("color:#1a7f37;font-weight:bold;" if on
                                     else "color:#999;")
        self.btn_start.setEnabled(not on)
        self.btn_stop.setEnabled(on)
        if on:
            ips = receiver.local_ips()
            port = self.ctx.receiver.port
            if ips:
                lines = ["地址  http://%s:%d%s" % (ip, port, protocol.PATH_RESULTS) for ip in ips]
                self.lbl_addr.setText("\n".join(lines))
            else:
                self.lbl_addr.setText("没有检测到局域网 IPv4 地址，手机可能连不上")
        else:
            self.lbl_addr.setText("--")

    # ---------------- 结果表 ----------------

    def refresh_table(self):
        items = self.ctx.index
        self.table.setRowCount(len(items))
        for i, it in enumerate(items):
            vals = [
                it.get("received_at", ""),
                _mode_cn(it.get("mode")),
                (it.get("model") or "--") + ((" " + it["serial"]) if it.get("serial") else ""),
                str(it.get("test_count", 0)),
                str(it.get("record_count", 0)),
                it.get("source_ip", ""),
                human_size(it.get("total_bytes", 0)),
            ]
            for c, v in enumerate(vals):
                cell = QtWidgets.QTableWidgetItem(str(v))
                if c == 0:
                    cell.setData(QtCore.Qt.UserRole, it.get("run_dir", ""))
                self.table.setItem(i, c, cell)
        self.table.resizeColumnsToContents()
        self.table.horizontalHeader().setStretchLastSection(True)

    def _selected(self):
        row = self.table.currentRow()
        if row < 0 or row >= len(self.ctx.index):
            return None
        return self.ctx.index[row]

    def open_report(self):
        it = self._selected()
        if not it:
            return
        md = os.path.join(it.get("run_dir", ""), "测试报告.md")
        if os.path.isfile(md):
            QtGui.QDesktopServices.openUrl(QtCore.QUrl.fromLocalFile(md))
        else:
            self.open_dir()

    def open_dir(self):
        it = self._selected()
        d = it.get("run_dir") if it else self.ctx.out_dir()
        if d and os.path.isdir(d):
            QtGui.QDesktopServices.openUrl(QtCore.QUrl.fromLocalFile(d))

    def remove_entry(self):
        it = self._selected()
        if not it:
            return
        ret = QtWidgets.QMessageBox.question(
            self, "从列表移除",
            "只从列表里移掉，磁盘上的报告文件不删。继续？",
            QtWidgets.QMessageBox.Yes | QtWidgets.QMessageBox.No)
        if ret != QtWidgets.QMessageBox.Yes:
            return
        self.ctx.index = [x for x in self.ctx.index
                          if x.get("run_dir") != it.get("run_dir")]
        store.save_index(self.ctx.index)
        self.refresh_table()

    # ---------------- 模拟发送 ----------------

    def simulate(self):
        if not self.ctx.receiver.running:
            self.start()
            if not self.ctx.receiver.running:
                return
        self.btn_sim.setEnabled(False)
        th = threading.Thread(target=self._simulate_worker, name="sim", daemon=True)
        th.start()

    def _simulate_worker(self):
        try:
            bundle = protocol.sample_bundle()
            import json
            body = json.dumps(bundle, ensure_ascii=False).encode("utf-8")
            url = "http://127.0.0.1:%d%s" % (self.ctx.receiver.port, protocol.PATH_RESULTS)
            req = urllib.request.Request(url, data=body, method="POST")
            req.add_header("Content-Type", "application/json; charset=utf-8")
            with urllib.request.urlopen(req, timeout=30) as resp:
                text = resp.read().decode("utf-8", "replace")
            self.ctx.bus.log.emit("模拟发送成功：%s" % text)
        except urllib.error.HTTPError as exc:
            detail = exc.read().decode("utf-8", "replace")[:400]
            self.ctx.bus.log.emit("模拟发送失败 HTTP %d：%s" % (exc.code, detail))
        except Exception as exc:
            self.ctx.bus.log.emit("模拟发送失败：%s" % exc)
        finally:
            self.sim_done.emit()

    def _on_sim_done(self):
        self.btn_sim.setEnabled(True)


def _mode_cn(mode):
    return {"wifi": "WiFi", "hotspot": "热点", "p2p": "P2P"}.get(str(mode or ""), str(mode or "--"))

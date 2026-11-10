# -*- coding: utf-8 -*-
"""主窗口：四个 Tab + 底部日志栏。"""

import os
import sys

from PySide6 import QtCore, QtGui, QtWidgets

from .. import APP_NAME, VERSION, paths, store
from ..context import Ctx
from .tab_receive import ReceiveTab
from .tab_report import ReportTab
from .tab_server import ServerTab
from .tab_settings import SettingsTab
from .widgets import LogView


class MainWindow(QtWidgets.QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("%s  v%s" % (APP_NAME, VERSION))
        self.resize(1080, 720)

        self.ctx = Ctx()
        self._split_timer = QtCore.QTimer(self)
        self._split_timer.setSingleShot(True)
        self._split_timer.setInterval(400)
        self._split_timer.timeout.connect(self._save_split)
        self._build()
        self._wire()

        self.ctx.bus.log.emit("界面已就绪。报告模块：%s"
                              % (self.ctx.report_probe()[0] or "不可用"))
        if os.path.isdir(self.ctx.out_dir()):
            self.ctx.bus.log.emit("输出目录：%s" % self.ctx.out_dir())

    def _build(self):
        # 日志先建好并接上总线：Tab 构造时（例如自动启动接收）就会打日志，
        # 晚一步连信号，那几条初始化日志会直接丢掉。
        self.log = LogView()
        self.ctx.bus.log.connect(self.log.add)

        central = QtWidgets.QWidget()
        self.setCentralWidget(central)
        root = QtWidgets.QVBoxLayout(central)
        root.setContentsMargins(0, 0, 0, 0)
        root.setSpacing(0)

        split = QtWidgets.QSplitter(QtCore.Qt.Vertical)
        split.setChildrenCollapsible(False)
        split.setHandleWidth(6)
        self.split = split

        self.tabs = QtWidgets.QTabWidget()
        self.tab_server = ServerTab(self.ctx)
        self.tab_receive = ReceiveTab(self.ctx)
        self.tab_report = ReportTab(self.ctx)
        self.tab_settings = SettingsTab(self.ctx)
        self.tabs.addTab(self.tab_server, "本机服务端")
        self.tabs.addTab(self.tab_receive, "收结果")
        self.tabs.addTab(self.tab_report, "出报告")
        self.tabs.addTab(self.tab_settings, "设置")
        self.tabs.currentChanged.connect(self._on_tab_changed)
        split.addWidget(self.tabs)

        bottom = QtWidgets.QWidget()
        bl = QtWidgets.QVBoxLayout(bottom)
        bl.setContentsMargins(8, 4, 8, 6)
        bl.setSpacing(4)
        head = QtWidgets.QHBoxLayout()
        head.setSpacing(6)
        lbl = QtWidgets.QLabel("日志")
        lbl.setStyleSheet("font-weight:bold;")
        btn_clear = QtWidgets.QPushButton("清空")
        btn_clear.setFixedWidth(60)
        btn_clear.clicked.connect(lambda: self.log.clear())
        btn_open = QtWidgets.QPushButton("打开输出目录")
        btn_open.setFixedWidth(108)
        btn_open.clicked.connect(self._open_out_dir)
        head.addWidget(lbl)
        head.addStretch(1)
        head.addWidget(btn_clear)
        head.addWidget(btn_open)
        bl.addLayout(head)
        bl.addWidget(self.log, 1)
        split.addWidget(bottom)
        # 比例由分隔条决定，窗口变大时两边一起长（不再固定 5:1 挤压日志区）
        split.setStretchFactor(0, 1)
        split.setStretchFactor(1, 1)
        split.setSizes(self._saved_split())
        split.splitterMoved.connect(self._on_split_moved)
        root.addWidget(split)

        self.status = self.statusBar()
        self._lbl_srv = QtWidgets.QLabel("服务端未启动")
        self._lbl_recv = QtWidgets.QLabel("接收未启动")
        self._lbl_cnt = QtWidgets.QLabel("已收 0")
        self.status.addWidget(self._lbl_srv)
        self.status.addWidget(QtWidgets.QLabel("  |  "))
        self.status.addWidget(self._lbl_recv)
        self.status.addWidget(QtWidgets.QLabel("  |  "))
        self.status.addWidget(self._lbl_cnt)
        self._refresh_status()

    # ---------------- 分隔条比例 ----------------

    def _saved_split(self):
        """读回上次拖动后的 Tab 区 / 日志区高度；数据不合法就用默认值。"""
        sizes = self.ctx.cfg.get("split_sizes")
        if (isinstance(sizes, (list, tuple)) and len(sizes) == 2
                and all(isinstance(v, int) and v > 0 for v in sizes)):
            return [int(sizes[0]), int(sizes[1])]
        return list(store.DEFAULTS["split_sizes"])

    def _on_split_moved(self, pos, index):
        # 拖动过程中每一像素都会触发，攒到停手 400 毫秒后再写盘
        self._split_timer.start()

    def _save_split(self):
        sizes = self.split.sizes()
        if len(sizes) != 2 or sizes[0] <= 0 or sizes[1] <= 0:
            return
        self.ctx.cfg["split_sizes"] = [int(sizes[0]), int(sizes[1])]
        store.save_config(self.ctx.cfg)

    def _reset_split(self):
        self.split.setSizes(list(store.DEFAULTS["split_sizes"]))

    def _wire(self):
        self.ctx.bus.reset_layout.connect(self._reset_split)
        self.ctx.bus.received.connect(self._on_received)
        self.ctx.bus.receiver_changed.connect(self._refresh_status)
        self.ctx.bus.failed.connect(
            lambda title, reason: QtWidgets.QMessageBox.critical(self, title, reason))

    # ---------------- 事件 ----------------

    def _on_received(self, entry):
        self.tab_receive.refresh_table()
        self.tab_report.refresh()
        self._refresh_status()
        if self.ctx.cfg.get("open_report_after_receive"):
            md = os.path.join(entry.get("run_dir", ""), "测试报告.md")
            if os.path.isfile(md):
                QtGui.QDesktopServices.openUrl(QtCore.QUrl.fromLocalFile(md))

    def _on_tab_changed(self, idx):
        if self.tabs.currentWidget() is self.tab_report:
            self.tab_report.refresh()
        self._refresh_status()

    def _refresh_status(self):
        s = self.ctx.pcserver
        if s.running:
            self._lbl_srv.setText("服务端运行  端口 %d" % s.port)
            self._lbl_srv.setStyleSheet("color:#1a7f37;")
        else:
            self._lbl_srv.setText("服务端未启动")
            self._lbl_srv.setStyleSheet("color:#999;")
        r = self.ctx.receiver
        if r.running:
            self._lbl_recv.setText("接收运行  端口 %d" % r.port)
            self._lbl_recv.setStyleSheet("color:#1a7f37;")
        else:
            self._lbl_recv.setText("接收未启动")
            self._lbl_recv.setStyleSheet("color:#999;")
        self._lbl_cnt.setText("已收 %d 份结果" % len(self.ctx.index))

    def _open_out_dir(self):
        d = self.ctx.out_dir()
        if os.path.isdir(d):
            QtGui.QDesktopServices.openUrl(QtCore.QUrl.fromLocalFile(d))
        else:
            QtWidgets.QMessageBox.information(self, "目录不存在", d)

    def closeEvent(self, ev):
        if self._split_timer.isActive():
            self._split_timer.stop()
        self._save_split()
        try:
            self.ctx.pcserver.stop()
        except Exception:
            pass
        try:
            self.ctx.receiver.stop()
        except Exception:
            pass
        self.ctx.reset_report_probe()
        super().closeEvent(ev)


def run(argv=None):
    """启动界面。返回进程退出码。"""
    app = QtWidgets.QApplication(argv if argv is not None else sys.argv)
    app.setApplicationName(APP_NAME)
    app.setApplicationVersion(VERSION)
    f = QtGui.QFont("Microsoft YaHei UI", 9)
    app.setFont(f)
    w = MainWindow()
    w.show()
    return app.exec()

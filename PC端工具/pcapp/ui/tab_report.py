# -*- coding: utf-8 -*-
"""Tab 2：出报告。看历史结果、预览报告、导出与打包。"""

import json
import os
import time

from PySide6 import QtCore, QtGui, QtWidgets

from .. import pack, store
from .widgets import card, human_size


class ReportTab(QtWidgets.QWidget):
    def __init__(self, ctx, parent=None):
        super().__init__(parent)
        self.ctx = ctx
        self._build()
        self.refresh()

    def _build(self):
        root = QtWidgets.QVBoxLayout(self)
        root.setContentsMargins(10, 10, 10, 10)
        root.setSpacing(8)

        box, lay = card("结果目录")
        row = QtWidgets.QHBoxLayout()
        row.setSpacing(6)
        self.lbl_dir = QtWidgets.QLabel("--")
        self.lbl_dir.setWordWrap(True)
        self.lbl_dir.setTextInteractionFlags(QtCore.Qt.TextSelectableByMouse)
        btn_open = QtWidgets.QPushButton("打开")
        btn_open.setFixedWidth(60)
        btn_open.clicked.connect(self._open_out_dir)
        btn_rescan = QtWidgets.QPushButton("重新扫描")
        btn_rescan.setFixedWidth(84)
        btn_rescan.clicked.connect(self.refresh)
        row.addWidget(QtWidgets.QLabel("目录"))
        row.addWidget(self.lbl_dir, 1)
        row.addWidget(btn_open)
        row.addWidget(btn_rescan)
        lay.addLayout(row)

        pick = QtWidgets.QHBoxLayout()
        pick.setSpacing(6)
        btn_import = QtWidgets.QPushButton("导入结果包 JSON…")
        btn_import.clicked.connect(self.import_bundle)
        pick.addWidget(btn_import)
        pick.addStretch(1)
        self.lbl_mod = QtWidgets.QLabel("--")
        self.lbl_mod.setStyleSheet("color:#666;")
        pick.addWidget(self.lbl_mod)
        lay.addLayout(pick)
        root.addWidget(box)

        split = QtWidgets.QSplitter(QtCore.Qt.Horizontal)
        split.setChildrenCollapsible(False)

        self.list = QtWidgets.QListWidget()
        self.list.setMinimumWidth(240)
        self.list.currentRowChanged.connect(self._show_detail)
        split.addWidget(self.list)

        right = QtWidgets.QWidget()
        rl = QtWidgets.QVBoxLayout(right)
        rl.setContentsMargins(0, 0, 0, 0)
        rl.setSpacing(6)
        self.view = QtWidgets.QPlainTextEdit()
        self.view.setReadOnly(True)
        f = self.view.font()
        f.setFamily("Microsoft YaHei UI")
        f.setPointSize(9)
        self.view.setFont(f)
        rl.addWidget(self.view, 1)

        bar = QtWidgets.QHBoxLayout()
        bar.setSpacing(6)
        self.btn_md = QtWidgets.QPushButton("打开 Markdown")
        self.btn_md.clicked.connect(lambda: self._open_file("测试报告.md"))
        self.btn_csv = QtWidgets.QPushButton("打开 CSV")
        self.btn_csv.clicked.connect(lambda: self._open_file("逐秒明细.csv"))
        self.btn_json = QtWidgets.QPushButton("打开 JSON")
        self.btn_json.clicked.connect(lambda: self._open_file("汇总.json"))
        self.btn_zip = QtWidgets.QPushButton("打包成 zip")
        self.btn_zip.clicked.connect(self.make_zip)
        for b in (self.btn_md, self.btn_csv, self.btn_json, self.btn_zip):
            bar.addWidget(b)
        bar.addStretch(1)
        rl.addLayout(bar)
        split.addWidget(right)
        split.setStretchFactor(0, 1)
        split.setStretchFactor(1, 3)
        root.addWidget(split, 1)

    # ---------------- 数据 ----------------

    def refresh(self):
        self.lbl_dir.setText(self.ctx.out_dir())
        src, ver = self.ctx.report_probe()
        self.lbl_mod.setText("报告模块：%s（%s）" % (os.path.basename(src), ver) if src
                             else "报告模块：不可用")
        self.list.clear()
        self._dirs = store.scan_output_dir()
        for d in self._dirs:
            it = QtWidgets.QListWidgetItem("%s\n%s   %s" % (
                d["name"], human_size(d["bytes"]),
                time.strftime("%m-%d %H:%M", time.localtime(d["mtime"]))))
            it.setData(QtCore.Qt.UserRole, d["run_dir"])
            self.list.addItem(it)
        if not self._dirs:
            self.view.setPlainText("输出目录里还没有结果。\n\n"
                                   "去「收结果」页等手机推送，或用「模拟发送」生成一次示例。")
        else:
            self.list.setCurrentRow(0)

    def _current_dir(self):
        it = self.list.currentItem()
        return it.data(QtCore.Qt.UserRole) if it else ""

    def _show_detail(self):
        d = self._current_dir()
        if not d:
            return
        md = os.path.join(d, "测试报告.md")
        text = ""
        if os.path.isfile(md):
            try:
                with open(md, "r", encoding="utf-8", errors="replace") as f:
                    text = f.read()
            except OSError as exc:
                text = "读报告失败：%s" % exc
        else:
            text = "这个目录里没有 测试报告.md"
        self.view.setPlainText(text)

    def _open_out_dir(self):
        d = self.ctx.out_dir()
        if os.path.isdir(d):
            QtGui.QDesktopServices.openUrl(QtCore.QUrl.fromLocalFile(d))

    def _open_file(self, name):
        d = self._current_dir()
        p = os.path.join(d, name)
        if os.path.isfile(p):
            QtGui.QDesktopServices.openUrl(QtCore.QUrl.fromLocalFile(p))
        else:
            QtWidgets.QMessageBox.information(self, "没有这个文件", "%s 里没有 %s" % (d or "输出目录", name))

    # ---------------- 动作 ----------------

    def import_bundle(self):
        p, _f = QtWidgets.QFileDialog.getOpenFileName(
            self, "选择结果包 JSON", self.ctx.out_dir(), "JSON 文件 (*.json)")
        if not p:
            return
        try:
            with open(p, "r", encoding="utf-8-sig") as f:
                bundle = json.load(f)
            entry, files = self.ctx.write_from_bundle(bundle)
        except Exception as exc:
            QtWidgets.QMessageBox.critical(self, "导入失败", str(exc))
            return
        total = sum(n for _n, n in files)
        self.ctx.bus.log.emit("已导入 %s：%d 个文件，%s"
                              % (os.path.basename(entry["run_dir"]), len(files), human_size(total)))
        self.refresh()

    def make_zip(self):
        d = self._current_dir()
        if not d:
            return
        path, info = pack.zip_run_dir(d)
        if path:
            self.ctx.bus.log.emit("已打包：%s（%s）" % (path, info))
            QtWidgets.QMessageBox.information(
                self, "打包完成", "%s\n%s" % (path, info))
        else:
            QtWidgets.QMessageBox.warning(self, "打包失败", str(info))

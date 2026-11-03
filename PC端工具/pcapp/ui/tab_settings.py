# -*- coding: utf-8 -*-
"""Tab 3：设置。端口、输出目录、自动启动、环境自检。"""

import os
import sys

from PySide6 import QtCore, QtGui, QtWidgets

from .. import paths, receiver, store
from .widgets import PathRow, LogView, card


class SettingsTab(QtWidgets.QWidget):
    def __init__(self, ctx, parent=None):
        super().__init__(parent)
        self.ctx = ctx
        self._build()
        self.load()
        self.check_env()

    def _build(self):
        root = QtWidgets.QVBoxLayout(self)
        root.setContentsMargins(10, 10, 10, 10)
        root.setSpacing(8)

        box, lay = card("接收")
        row = QtWidgets.QHBoxLayout()
        row.setSpacing(6)
        row.addWidget(QtWidgets.QLabel("监听端口"))
        self.spin_port = QtWidgets.QSpinBox()
        self.spin_port.setRange(1024, 65535)
        self.spin_port.setFixedWidth(96)
        row.addWidget(self.spin_port)
        self.chk_auto = QtWidgets.QCheckBox("启动时自动开始接收")
        row.addWidget(self.chk_auto)
        row.addStretch(1)
        lay.addLayout(row)

        self.row_out = PathRow("输出目录", picker=True)
        lay.addWidget(self.row_out)
        tip = QtWidgets.QLabel(
            "留空表示用工具自带的 输出 目录；改成别处时该目录必须已存在。")
        tip.setStyleSheet("color:#666;")
        lay.addWidget(tip)
        root.addWidget(box)

        btns = QtWidgets.QHBoxLayout()
        btns.setSpacing(6)
        self.btn_save = QtWidgets.QPushButton("保存设置")
        self.btn_save.clicked.connect(self.save)
        self.btn_reset = QtWidgets.QPushButton("恢复默认")
        self.btn_reset.clicked.connect(self.reset)
        self.btn_check = QtWidgets.QPushButton("重新自检")
        self.btn_check.clicked.connect(self.check_env)
        btns.addWidget(self.btn_save)
        btns.addWidget(self.btn_reset)
        btns.addWidget(self.btn_check)
        btns.addStretch(1)
        root.addLayout(btns)

        box2, lay2 = card("环境自检")
        self.txt = QtWidgets.QPlainTextEdit()
        self.txt.setReadOnly(True)
        f = self.txt.font()
        f.setFamily("Consolas")
        f.setPointSize(9)
        self.txt.setFont(f)
        lay2.addWidget(self.txt)
        root.addWidget(box2, 1)

    # ---------------- 读写 ----------------

    def load(self):
        c = self.ctx.cfg
        self.spin_port.setValue(int(c.get("port") or 8737))
        self.chk_auto.setChecked(bool(c.get("auto_start", True)))
        self.row_out.set_value(str(c.get("out_dir") or ""))

    def save(self):
        port = int(self.spin_port.value())
        out = self.row_out.value()
        if out and not os.path.isdir(out):
            QtWidgets.QMessageBox.warning(self, "目录不存在",
                                          "输出目录不存在：%s\n请选一个已存在的目录。" % out)
            return
        if self.ctx.receiver.running and port != self.ctx.receiver.port:
            QtWidgets.QMessageBox.information(
                self, "需要重启接收",
                "端口改动要重启接收服务才生效。先停止再启动一次即可。")
        self.ctx.cfg["port"] = port
        self.ctx.cfg["auto_start"] = bool(self.chk_auto.isChecked())
        self.ctx.cfg["out_dir"] = out
        ok = store.save_config(self.ctx.cfg)
        self.ctx.bus.log.emit("设置已保存" if ok else "设置保存失败（可能目录不可写）")

    def reset(self):
        self.ctx.cfg = dict(store.DEFAULTS)
        store.save_config(self.ctx.cfg)
        self.load()
        self.ctx.bus.reset_layout.emit()
        self.ctx.bus.log.emit("设置已恢复默认")

    # ---------------- 自检 ----------------

    def check_env(self):
        lines = []
        lines.append("Python      %s" % sys.version.split()[0])
        lines.append("解释器      %s" % sys.executable)
        lines.append("源码目录    %s" % paths.root_dir())
        lines.append("输出目录    %s" % self.ctx.out_dir())
        lines.append("")

        src, ver = self.ctx.report_probe()
        if src:
            lines.append("[OK] 报告模块 %s（版本 %s）" % (src, ver))
        else:
            lines.append("[!!] 找不到可以用的 report.py，出报告会失败")
            lines.append("     期望位置 %s" % paths.sibling_report_path())
            lines.append("     兜底副本 %s" % paths.bundled_report_path())

        try:
            import PySide6
            from PySide6 import QtCore as _qc
            lines.append("[OK] PySide6 %s（Qt %s）"
                         % (PySide6.__version__, _qc.qVersion()))
        except Exception as exc:
            lines.append("[!!] PySide6 异常：%s" % exc)

        ips = receiver.local_ips()
        lines.append("[OK] 本机地址 %s" % (", ".join(ips) if ips else "（没检测到，手机可能连不上）"))

        port = int(self.ctx.cfg.get("port") or 8737)
        free = receiver.free_port(port, 1)
        if free == port:
            lines.append("[OK] 端口 %d 可用" % port)
        else:
            lines.append("[!!] 端口 %d 已被占用，启动接收时会提示换端口" % port)

        adb = _probe_adb()
        lines.append("[%s] adb %s" % ("OK" if adb else "--", adb or "（没找到，PC 端不直接连手机，不影响接收）"))

        out = self.ctx.out_dir()
        if os.path.isdir(out):
            writable = _writable(out)
            lines.append("[%s] 输出目录可写 %s" % ("OK" if writable else "!!", out))
        else:
            lines.append("[!!] 输出目录不存在 %s" % out)

        self.txt.setPlainText("\n".join(lines))


def _probe_adb():
    from shutil import which
    p = which("adb")
    if p:
        return p
    cand = r"C:\Program Files\ADB\adb.exe"
    return cand if os.path.isfile(cand) else ""


def _writable(d):
    probe = os.path.join(d, ".dsh_write_probe")
    try:
        with open(probe, "w", encoding="utf-8") as f:
            f.write("ok")
        os.remove(probe)
        return True
    except OSError:
        return False

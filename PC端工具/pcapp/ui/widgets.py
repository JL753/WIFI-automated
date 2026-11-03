# -*- coding: utf-8 -*-
"""几个反复用到的小控件，避免三个 Tab 各写一份。"""

from PySide6 import QtCore, QtWidgets


def human_size(n):
    """字节数转成 12.3 KB 这种。"""
    try:
        n = float(n)
    except (TypeError, ValueError):
        return "--"
    for unit in ("B", "KB", "MB", "GB"):
        if abs(n) < 1024.0 or unit == "GB":
            return ("%d %s" % (n, unit)) if unit == "B" else ("%.1f %s" % (n, unit))
        n /= 1024.0
    return "--"


def card(title, parent=None):
    """带标题的分组框。"""
    box = QtWidgets.QGroupBox(title, parent)
    lay = QtWidgets.QVBoxLayout(box)
    lay.setContentsMargins(10, 8, 10, 10)
    lay.setSpacing(6)
    return box, lay


class LogView(QtWidgets.QPlainTextEdit):
    """只读日志：等宽字体 + 自动滚到底，上限 2000 行防止长跑吃内存。"""

    MAX_LINES = 2000

    def __init__(self, parent=None):
        super().__init__(parent)
        self.setReadOnly(True)
        self.setMaximumBlockCount(self.MAX_LINES)
        f = self.font()
        f.setFamily("Consolas")
        f.setPointSize(9)
        self.setFont(f)

    def add(self, msg):
        ts = QtCore.QTime.currentTime().toString("HH:mm:ss")
        self.appendPlainText("%s  %s" % (ts, msg))
        sb = self.verticalScrollBar()
        sb.setValue(sb.maximum())


class PathRow(QtWidgets.QWidget):
    """一行「标签 + 只读路径 + 选择按钮」。"""

    changed = QtCore.Signal(str)

    def __init__(self, label, value="", picker=False, parent=None):
        super().__init__(parent)
        lay = QtWidgets.QHBoxLayout(self)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.setSpacing(6)
        self._label = QtWidgets.QLabel(label)
        self._label.setMinimumWidth(88)
        self._edit = QtWidgets.QLineEdit(value)
        self._edit.setReadOnly(True)
        lay.addWidget(self._label)
        lay.addWidget(self._edit, 1)
        if picker:
            btn = QtWidgets.QPushButton("选择…")
            btn.setFixedWidth(72)
            btn.clicked.connect(self._pick)
            lay.addWidget(btn)

    def value(self):
        return self._edit.text().strip()

    def set_value(self, v):
        self._edit.setText(str(v or ""))
        self.changed.emit(self.value())

    def _pick(self):
        d = QtWidgets.QFileDialog.getExistingDirectory(self, "选择目录", self.value() or "")
        if d:
            self.set_value(d)

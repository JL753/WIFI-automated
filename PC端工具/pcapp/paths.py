# -*- coding: utf-8 -*-
r"""路径与运行时环境。Qt 装在 vendor 里，靠这里插进 sys.path。"""

import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PC_ROOT = os.path.dirname(HERE)


def root_dir():
    """PC 端工具根目录（PC端工具\）。"""
    return PC_ROOT


def vendor_dir():
    """随包携带的第三方库（PySide6）。"""
    return os.path.join(PC_ROOT, "vendor")


def out_dir():
    return os.path.join(PC_ROOT, "输出")


def config_path():
    return os.path.join(PC_ROOT, "配置.json")


def bundled_report_path():
    return os.path.join(HERE, "_vendor_report.py")


def sibling_report_path():
    """同盘的 Python 版工具里的 report.py —— 存在就优先用，改动会同步过来。"""
    return os.path.join(os.path.dirname(PC_ROOT),
                        "WiFi吞吐量测试工具", "tools", "wb", "report.py")


def pc_iperf3_path():
    """PC 端 iperf3 可执行文件：随包一份，找不到再找同盘的 CLI/exe 目录。"""
    candidates = [
        os.path.join(PC_ROOT, "bin", "win", "iperf3.exe"),
        os.path.join(os.path.dirname(PC_ROOT), "WiFi吞吐量测试工具", "bin", "win", "iperf3.exe"),
        os.path.join(os.path.dirname(PC_ROOT), "WiFiTput", "bin", "win", "iperf3.exe"),
    ]
    for p in candidates:
        if os.path.isfile(p):
            return p
    return ""


def ensure_vendor_on_path():
    r"""把 vendor 插到 sys.path 最前面，返回是否成功。"""
    v = vendor_dir()
    if not os.path.isdir(v):
        return False
    if v not in sys.path:
        sys.path.insert(0, v)
    return True

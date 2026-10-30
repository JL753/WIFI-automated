# -*- coding: utf-8 -*-
"""集中解析工具根目录，供 Python 源码运行与 PyInstaller 冻结后共用。

源码运行时 __file__ 在 tools\\wb\\ 下，根目录要往上退三级；
打包成 exe 后 __file__ 会指向临时解压目录（_MEIxxxx），拿它推根目录
会找不到 bin\\ 与 输出\\，必须改用 exe 自身所在目录。
"""
from __future__ import annotations

import os
import sys


def is_frozen():
    """是否运行在 PyInstaller 打包出的 exe 里。"""
    return bool(getattr(sys, "frozen", False))


def root_dir():
    """工具根目录：源码模式下是工具目录，exe 模式下是 exe 所在目录。"""
    if is_frozen():
        return os.path.dirname(os.path.abspath(sys.executable))
    here = os.path.dirname(os.path.abspath(__file__))
    return os.path.dirname(os.path.dirname(here))


def bin_dir():
    """手机端与 PC 端 iperf3 二进制所在目录。"""
    return os.path.join(root_dir(), "bin")


def pc_iperf():
    """PC 端 iperf3.exe 的绝对路径。"""
    return os.path.join(bin_dir(), "win", "iperf3.exe")


def out_dir():
    """测试结果输出目录。"""
    return os.path.join(root_dir(), "输出")

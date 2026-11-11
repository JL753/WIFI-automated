# -*- coding: utf-8 -*-
r"""PC 端入口：双击 启动.bat 打开（GUI 无命令窗口）。

不带参数打开界面；带 --selftest 只做离线自检并打印结果（保留控制台输出，
适合远程或第一次安装后确认环境是否齐全）。

GUI 启动走 pythonw.exe，没有控制台，所以任何启动失败都写进
PC端工具\启动错误.log 并尽量弹一个 Qt 提示框，避免「双击没反应」。
"""

import os
import sys
import traceback

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

ERROR_LOG = os.path.join(HERE, "启动错误.log")


def _gui_mode():
    """pythonw.exe 没有控制台；把输出接到日志文件，异常不会静默丢失。"""
    if sys.stdout is not None and sys.stderr is not None:
        return
    try:
        fp = open(ERROR_LOG, "a", encoding="utf-8", errors="replace")
        if sys.stdout is None:
            sys.stdout = fp
        if sys.stderr is None:
            sys.stderr = fp
    except Exception:
        pass


def _log_startup_error(text):
    """把启动失败写进日志；能用 Qt 时再弹一个可见提示。"""
    try:
        with open(ERROR_LOG, "a", encoding="utf-8", errors="replace") as fp:
            fp.write(text.rstrip() + "\n")
    except Exception:
        pass
    try:
        from PySide6 import QtWidgets
        app = QtWidgets.QApplication.instance() or QtWidgets.QApplication([])
        QtWidgets.QMessageBox.critical(
            None, "WiFiTput PC 端启动失败",
            "程序没能启动。详细原因已写入：\n%s" % ERROR_LOG)
    except Exception:
        pass


def _selftest():
    """离线自检：不联网、不开窗口。返回退出码。"""
    from pcapp import paths
    ok = True
    print("源码目录    %s" % paths.root_dir())
    print("输出目录    %s" % paths.out_dir())
    print("vendor      %s" % paths.vendor_dir())

    if not paths.ensure_vendor_on_path():
        print("[!!] vendor 目录不存在：%s" % paths.vendor_dir())
        return 2
    try:
        import PySide6
        from PySide6 import QtCore, QtWidgets
        print("[OK] PySide6 %s（Qt %s）" % (PySide6.__version__, QtCore.qVersion()))
    except Exception as exc:
        print("[!!] PySide6 不可用：%s" % exc)
        ok = False

    from pcapp import paths as _p, protocol, receiver, reports, store
    src, ver = reports.module_info()
    if src:
        print("[OK] 报告模块 %s（%s）" % (src, ver))
    else:
        print("[!!] 找不到可用的 report.py")
        ok = False

    ips = receiver.local_ips()
    print("[%s] 本机地址 %s" % ("OK" if ips else "!!", ", ".join(ips) or "无"))
    ok = ok and bool(ips)

    port = receiver.free_port(int(store.load_config().get("port") or 8737), 1)
    print("[%s] 端口 %d %s" % ("OK" if port else "!!", port, "可用"))

    d = _p.out_dir()
    if os.path.isdir(d):
        try:
            t = os.path.join(d, ".selftest")
            with open(t, "w", encoding="utf-8") as f:
                f.write("ok")
            os.remove(t)
            print("[OK] 输出目录可写")
        except OSError as exc:
            print("[!!] 输出目录不可写：%s" % exc)
            ok = False
    else:
        print("[!!] 输出目录不存在：%s" % d)
        ok = False

    print("自检结果：%s" % ("全部通过" if ok else "有问题，见上面的 [!!]"))
    return 0 if ok else 1


def _main(argv):
    from pcapp import paths
    if "--selftest" in argv or "-selftest" in argv:
        return _selftest()

    _gui_mode()
    try:
        if os.path.isfile(ERROR_LOG):
            os.remove(ERROR_LOG)
    except OSError:
        pass
    paths.ensure_vendor_on_path()
    try:
        from pcapp.ui import run
        return run(argv)
    except ImportError as exc:
        _log_startup_error(
            "[错误] 缺少 PySide6 界面库，无法启动。\n"
            "原因  ：%s\n"
            "处理办法：双击 启动.bat 让它自动安装 PySide6==6.12.0 到 vendor\，"
            "或手动执行 python -m pip install --target vendor PySide6==6.12.0\n"
            "vendor 目录：%s\n%s" % (exc, paths.vendor_dir(), traceback.format_exc()))
        return 1
    except Exception:
        _log_startup_error("界面启动异常：\n" + traceback.format_exc())
        return 1


if __name__ == "__main__":
    sys.exit(_main(sys.argv))

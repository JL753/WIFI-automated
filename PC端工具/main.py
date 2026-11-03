# -*- coding: utf-8 -*-
r"""PC 端入口：双击或用 启动.bat 打开。

不带参数打开界面；带 --selftest 只做离线自检并打印结果（不开窗口，
适合远程或第一次安装后确认环境是否齐全）。
r"""

import os
import sys
import traceback

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)


def _selftest():
    """离线自检：只读、不联网、不开窗口。返回退出码。"""
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
        from PySide6 import QtCore
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
    paths.ensure_vendor_on_path()
    try:
        from pcapp.ui import run
    except ImportError as exc:
        sys.stderr.write(
            "[错误] 缺少 PySide6 界面库，无法启动。\n"
            "原因  ：%s\n"
            "处理办法：确认 vendor 目录完整存在：%s\n" % (exc, paths.vendor_dir()))
        return 1
    except Exception:
        traceback.print_exc()
        return 1
    return run(argv)


if __name__ == "__main__":
    sys.exit(_main(sys.argv))

# -*- coding: utf-8 -*-
"""界面与后台的数据中转：配置、接收器、已收结果清单。

HTTP 服务跑在后台线程，所有跨线程通知一律走 Qt 信号（队列连接），
不直接碰控件 —— 否则偶发崩溃且极难复现。
"""

import os
import threading
import time

from PySide6 import QtCore

from . import paths, protocol, receiver, reports, store


class Bus(QtCore.QObject):
    """跨线程通知总线。"""

    log = QtCore.Signal(str)              # 一行日志
    received = QtCore.Signal(dict)        # 收到一包结果（已落盘）
    failed = QtCore.Signal(str, str)      # (标题, 原因)
    reset_layout = QtCore.Signal()        # 设置里点了「恢复默认」，主窗口把分隔条拉回默认比例


def default_run_id():
    return "pc-" + time.strftime("%Y%m%d-%H%M%S")


class Ctx:
    """三个 Tab 与主窗口共用的运行时状态。"""

    def __init__(self):
        self.cfg = store.load_config()
        self.bus = Bus()
        self.log_signal = self.bus.log
        self.ui_signal = self.bus.log       # 兼容旧写法，语义同 log
        self.index = store.load_index()
        self.receiver = receiver.Receiver(on_result=self.on_result,
                                          on_log=self._rlog)
        self._lock = threading.Lock()
        self._report_probe = None

    # ---------------- 路径 ----------------

    def out_dir(self):
        d = str(self.cfg.get("out_dir") or "").strip()
        return d if d else paths.out_dir()

    def set_out_dir(self, d):
        self.cfg["out_dir"] = str(d or "")
        store.save_config(self.cfg)

    # ---------------- 报告模块自检 ----------------

    def report_probe(self):
        """返回 (report.py 路径, 版本)；不可用时路径为 None。缓存结果。"""
        if self._report_probe is None:
            self._report_probe = reports.module_info()
        return self._report_probe

    def reset_report_probe(self):
        self._report_probe = None

    # ---------------- 接收回调（后台线程） ----------------

    def _rlog(self, msg):
        self.bus.log.emit(str(msg))

    def on_result(self, bundle, source_ip=""):
        """HTTP 线程里被调用：校验 → 落盘 → 记索引 → 通知界面。"""
        with self._lock:
            run_dir, files, _payload = reports.write_bundle(
                bundle, out_base=self.out_dir())
            entry = store.make_entry(bundle, run_dir, files, source_ip)
            self.index = store.add_entry(entry)
        total = sum(s for _n, s in files)
        self.bus.log.emit("收到结果：%s（%d 个文件，%s）"
                          % (os.path.basename(run_dir), len(files), _hs(total)))
        self.bus.received.emit(entry)
        return {"run_dir": run_dir,
                "files": [{"name": n, "bytes": s} for n, s in files]}

    # ---------------- 页面「出报告」用 ----------------

    def write_from_bundle(self, bundle, source_ip="本地导入"):
        """手工导入一个 JSON 结果包时走这里，语义与接收一致。

        source_ip 让列表里能区分「本地导入」和「从手机 USB 导出」。
        """
        bundle = protocol.validate(bundle)
        with self._lock:
            run_dir, files, _payload = reports.write_bundle(
                bundle, out_base=self.out_dir())
            entry = store.make_entry(bundle, run_dir, files, source_ip)
            self.index = store.add_entry(entry)
        self.bus.received.emit(entry)
        return entry, files

    # ---------------- 从手机 USB 导出 ----------------

    def usb_export_dir(self, serial):
        r"""电脑端存放手机导出原始 JSON 的目录：手机导出\<序列号>-<时间戳>（反斜杠是分隔符）。

        每次都新建一个，重复导出不会互相覆盖，出问题也能回看原始文件。
        """
        name = "%s-%s" % (_safe_name(serial), time.strftime("%Y%m%d-%H%M%S"))
        return os.path.join(paths.root_dir(), "手机导出", name)

    def import_from_usb(self, serial=None):
        """把手机上的历史记录一次全导进来。会在后台线程里被调用。

        返回 {"adb","serial","dest","pulled","imported","skipped","errors"}；
        单条记录坏掉只记进 errors，不中断整批 —— 导出一条算一条。
        """
        from . import usbexport

        adb = usbexport.find_adb()          # 找不到 adb 直接抛 UsbError
        serial = str(serial or "").strip()
        if not serial:
            serial = usbexport.default_serial(usbexport.list_devices(adb))
        if not serial:
            raise usbexport.UsbError(
                "没检测到可用的手机。请用 USB 连上电脑并在开发者选项里打开 USB 调试，"
                "手机上弹出的授权提示要点「允许」。")

        dest_base = self.usb_export_dir(serial)
        pulled = usbexport.pull_history(adb, serial, dest_base)
        dest = pulled.get("dest") or dest_base

        records = usbexport.parse_records(usbexport.json_files(dest))
        ready, skipped = usbexport.pick_bundles(records)

        imported = 0
        errors = []
        source_ip = "USB:" + serial
        for rec in ready:
            try:
                self.write_from_bundle(rec["bundle"], source_ip=source_ip)
                imported += 1
            except Exception as exc:      # 一条坏数据不能拖垮整批
                errors.append("%s：%s" % (rec.get("id") or rec.get("path"), exc))

        self.bus.log.emit("USB 导出：%s 拉到 %d 个文件，导入 %d 条，跳过 %d 条"
                          % (serial, len(records), imported, len(skipped)))
        return {"adb": adb, "serial": serial, "dest": dest,
                "pulled": int(pulled.get("pulled") or 0),
                "imported": imported, "skipped": len(skipped), "errors": errors}


def _safe_name(text):
    """把序列号之类的东西变成能当目录名的字符串。"""
    out = "".join(c if (c.isalnum() or c in "-_.") else "_" for c in str(text or ""))
    return out.strip("._") or "unknown"


def _hs(n):
    try:
        n = float(n)
    except (TypeError, ValueError):
        return "--"
    for unit in ("B", "KB", "MB", "GB"):
        if abs(n) < 1024.0 or unit == "GB":
            return ("%d %s" % (n, unit)) if unit == "B" else ("%.1f %s" % (n, unit))
        n /= 1024.0
    return "--"

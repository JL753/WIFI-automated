# -*- coding: utf-8 -*-
"""把手机端的结果包落盘成 CSV / JSON / Markdown / 原始输出。

复用 WiFi吞吐量测试工具 的 wb/report.py，不另写一套渲染逻辑 —— 否则 PC 出的报告
和手机/命令行出的报告会慢慢变得不一样。优先用同盘的源码版（改了立刻生效），
找不到才退回本目录里的副本。
"""

import importlib.util
import os
import sys

from . import paths

_MOD = None
_SRC = ""


def _load_module():
    """加载 report.py，返回 (module, 来源路径)。"""
    global _MOD, _SRC
    if _MOD is not None:
        return _MOD, _SRC

    for cand in (paths.sibling_report_path(), paths.bundled_report_path()):
        if not cand or not os.path.isfile(cand):
            continue
        spec = importlib.util.spec_from_file_location("_wifitput_report", cand)
        if spec is None or spec.loader is None:
            continue
        mod = importlib.util.module_from_spec(spec)
        sys.modules["_wifitput_report"] = mod
        try:
            spec.loader.exec_module(mod)
        except Exception:
            sys.modules.pop("_wifitput_report", None)
            continue
        _MOD, _SRC = mod, cand
        return _MOD, _SRC

    raise ReportError(
        "找不到报告生成模块 report.py。\n"
        "查找位置：\n  1) %s\n  2) %s\n"
        "处理办法：把 WiFi吞吐量测试工具 放在本工具的上一级目录，"
        "或确认 pcapp\\_vendor_report.py 副本存在。"
        % (paths.sibling_report_path(), paths.bundled_report_path()))


class ReportError(RuntimeError):
    """报告模块缺失或渲染失败。消息是给人看的中文说明。"""


def module_info():
    """返回 (report.py 路径, SCRIPT_VERSION)；不可用时返回 (None, None)。"""
    try:
        mod, src = _load_module()
    except ReportError:
        return None, None
    return src, getattr(mod, "SCRIPT_VERSION", None)


def write_bundle(bundle, out_base=None, tag=""):
    """把 bundle 写成运行目录下的四个文件，返回 (run_dir, 文件清单)。

    文件清单是 [(文件名, 字节数), ...]，顺序固定。
    """
    mod, _src = _load_module()
    base = out_base or paths.out_dir()
    items = list((bundle.get("meta") or {}).get("items") or [])
    if not items:
        items = ["wifi_tput"]

    try:
        run_dir = mod.make_run_dir(base, items, tag or _mode_tag(bundle))
    except Exception as exc:
        raise ReportError("创建结果目录失败：%s" % exc)

    files = []

    def _size(name):
        p = os.path.join(run_dir, name)
        return os.path.getsize(p) if os.path.isfile(p) else 0

    records = bundle.get("records") or []
    csv_name = "逐秒明细.csv"
    try:
        mod.write_csv(os.path.join(run_dir, csv_name), records)
    except Exception as exc:
        raise ReportError("写 %s 失败：%s" % (csv_name, exc))
    files.append((csv_name, _size(csv_name)))

    try:
        payload = mod.build_payload(
            meta=bundle.get("meta") or {},
            devices=bundle.get("devices") or [],
            records=records,
            results=bundle.get("results") or [],
            screen=(bundle.get("screen") if isinstance(bundle.get("screen"), dict) else None),
            notes=bundle.get("notes") or [],
        )
    except Exception as exc:
        raise ReportError("组装报告数据失败：%s" % exc)

    # 报告里要能看到这是哪台手机、哪个 App 版本推来的
    run = bundle.get("run") or {}
    app = bundle.get("app") or {}
    extra = []
    if run.get("id"):
        extra.append("手机端运行编号 %s" % run["id"])
    if app.get("version"):
        extra.append("手机端 App 版本 %s" % app["version"])
    if extra:
        payload.setdefault("notes", [])
        for line in extra:
            if line not in payload["notes"]:
                payload["notes"].append(line)

    json_name = "汇总.json"
    try:
        mod.write_json(os.path.join(run_dir, json_name), payload)
    except Exception as exc:
        raise ReportError("写 %s 失败：%s" % (json_name, exc))
    files.append((json_name, _size(json_name)))

    md_name = "测试报告.md"
    try:
        mod.write_markdown(os.path.join(run_dir, md_name), payload)
    except Exception as exc:
        raise ReportError("写 %s 失败：%s" % (md_name, exc))
    files.append((md_name, _size(md_name)))

    raw = bundle.get("raw") if isinstance(bundle.get("raw"), dict) else {}
    for tid, text in raw.items():
        if not isinstance(text, str) or not text.strip():
            continue
        name = "原始输出_%s.txt" % _safe(tid)
        try:
            mod.write_raw(os.path.join(run_dir, name), text)
        except Exception as exc:
            raise ReportError("写 %s 失败：%s" % (name, exc))
        files.append((name, _size(name)))

    return run_dir, files, payload


def _mode_tag(bundle):
    """目录名后缀。只给 P2P 加 —— 它和 WiFi 共用 wifi_tput 这个 id，不加就分不清。"""
    mode = str((bundle.get("run") or {}).get("mode") or "")
    return "P2P" if mode == "p2p" else ""


def _safe(s):
    keep = []
    for ch in str(s or ""):
        keep.append(ch if (ch.isalnum() or ch in "._-") else "_")
    return "".join(keep)[:60] or "raw"

# -*- coding: utf-8 -*-
"""接收到的结果索引与配置读写。

索引只是个 JSON 清单，真正的报告文件在各次运行目录里 —— 清单丢了不影响报告，
所以这里对损坏文件一律宽容处理，绝不让界面因为索引文件坏掉而打不开。
"""

import json
import os
import time

from . import paths

INDEX_NAME = "已接收.json"


def index_path():
    return os.path.join(paths.root_dir(), INDEX_NAME)


def load_index():
    p = index_path()
    try:
        with open(p, "r", encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError):
        return []
    items = data.get("items") if isinstance(data, dict) else data
    if not isinstance(items, list):
        return []
    return [x for x in items if isinstance(x, dict) and x.get("run_dir")]


def _write_json_atomic(path, obj):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8", newline="\n") as f:
        json.dump(obj, f, ensure_ascii=False, indent=2)
        f.write("\n")
    os.replace(tmp, path)


def save_index(items):
    try:
        _write_json_atomic(index_path(), {"version": 1, "items": list(items)})
        return True
    except (OSError, TypeError, ValueError):
        return False


def add_entry(entry):
    """新记录插到最前面（界面列表最新在上）。返回更新后的清单。"""
    items = load_index()
    items = [x for x in items if x.get("run_dir") != entry.get("run_dir")]
    items.insert(0, entry)
    save_index(items)
    return items


def make_entry(bundle, run_dir, files, source_ip=""):
    run = bundle.get("run") or {}
    meta = bundle.get("meta") or {}
    results = bundle.get("results") or []
    devices = bundle.get("devices") or []
    total = sum(n for _name, n in files)
    return {
        "run_id": str(run.get("id") or ""),
        "mode": str(run.get("mode") or meta.get("mode") or ""),
        "run_dir": run_dir,
        "received_at": time.strftime("%Y-%m-%d %H:%M:%S"),
        "source_ip": source_ip,
        "device_count": len(devices),
        "test_count": len(results),
        "record_count": len(bundle.get("records") or []),
        "files": [{"name": n, "bytes": s} for n, s in files],
        "total_bytes": total,
        "serial": str((devices[0] or {}).get("serial") or "") if devices else "",
        "model": str((devices[0] or {}).get("model") or "") if devices else "",
    }


def scan_output_dir():
    """扫 输出 下所有含 汇总.json 的目录，用于「出报告」Tab 打开历史。"""
    base = paths.out_dir()
    out = []
    if not os.path.isdir(base):
        return out
    for name in sorted(os.listdir(base), reverse=True):
        d = os.path.join(base, name)
        if not os.path.isdir(d):
            continue
        summ = os.path.join(d, "汇总.json")
        if not os.path.isfile(summ):
            continue
        out.append({"name": name, "run_dir": d,
                    "mtime": os.path.getmtime(d),
                    "bytes": sum(os.path.getsize(os.path.join(d, f))
                                 for f in os.listdir(d)
                                 if os.path.isfile(os.path.join(d, f)))})
    return out


# ---------------- 配置 ----------------

DEFAULTS = {
    "port": 8737,
    "out_dir": "",
    "auto_start": True,
    "open_report_after_receive": False,
    "split_sizes": [560, 150],   # 上：Tab 区、下：日志区，拖动分隔条后记住
}


def load_config():
    cfg = dict(DEFAULTS)
    try:
        with open(paths.config_path(), "r", encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError):
        return cfg
    if isinstance(data, dict):
        for k in DEFAULTS:
            if k in data:
                cfg[k] = data[k]
    return cfg


def save_config(cfg):
    data = {k: cfg.get(k, DEFAULTS[k]) for k in DEFAULTS}
    try:
        _write_json_atomic(paths.config_path(), data)
        return True
    except (OSError, TypeError, ValueError):
        return False

# -*- coding: utf-8 -*-
"""参数记忆：把用户上次用过的测试参数存到工具根目录下的 配置.json。

设计原则：
- 存盘文件是给人看的，所以用 UTF-8 无 BOM + 缩进 2 的中文 JSON，方便手改。
- 写盘先写临时文件再 os.replace 原子替换，写到一半断电也不会留下半个坏文件。
- 读不到、读不懂、读出来不是 dict 一律当没配过，返回空字典，绝不把异常抛给最终用户。
- 模块导入时不做任何 IO，只在使用时才碰磁盘。
"""
from __future__ import annotations

import os
import json

from . import paths

CONFIG_NAME = "配置.json"


def config_path():
    """配置文件绝对路径：工具根目录下的 配置.json。"""
    return os.path.join(paths.root_dir(), CONFIG_NAME)


def load():
    """读取配置，返回 dict。文件不存在、内容损坏、不是 dict 一律返回 {}。"""
    path = config_path()
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
    except (OSError, ValueError):
        return {}
    if not isinstance(data, dict):
        return {}
    return data


def save(data):
    """把 dict 写入配置文件，成功返回 True，失败返回 False（不抛异常）。"""
    if not isinstance(data, dict):
        return False
    path = config_path()
    tmp = path + ".tmp"
    try:
        with open(tmp, "w", encoding="utf-8", newline="\n") as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
            f.write("\n")
        os.replace(tmp, path)
    except (OSError, TypeError, ValueError):
        try:
            if os.path.isfile(tmp):
                os.remove(tmp)
        except OSError:
            pass
        return False
    return True


def get(key, default=None):
    """取单个键，不存在返回 default。"""
    return load().get(key, default)


def update(**kw):
    """合并写入若干键（先 load 再合并再 save），返回合并后的完整 dict。"""
    data = load()
    data.update(kw)
    save(data)
    return data

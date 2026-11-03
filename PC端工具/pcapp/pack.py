# -*- coding: utf-8 -*-
"""把一次测试的结果目录打包成 zip，方便拷走或发给别人。

设计要点：
1. zip 内的条目一律用「相对 run_dir 的路径」，不带绝对前缀、也不套 run_dir
   这层目录名，解压出来直接就是 逐秒明细.csv / 汇总.json 这些文件本身。
2. 中文文件名靠 zipfile 对 str 类型 arcname 的默认处理：按 UTF-8 写入并置
   flag bit 11，Windows 资源管理器与 7-Zip 都能正确还原。
3. 本模块绝不裸抛异常，失败统一返回 (None, 中文原因)，由调用方打印三段式提示。
"""

import os
import sys
import zipfile


def human_size(n):
    """把字节数变成 '12.3 KB' / '4.5 MB' 这样的可读字符串，用于提示"""
    try:
        size = float(n)
    except (TypeError, ValueError):
        size = 0.0
    if size < 0:
        size = 0.0
    if size < 1024:
        return "%d B" % int(size)
    if size < 1024 * 1024:
        return "%.1f KB" % (size / 1024.0)
    if size < 1024 * 1024 * 1024:
        return "%.1f MB" % (size / (1024.0 * 1024.0))
    return "%.1f GB" % (size / (1024.0 * 1024.0 * 1024.0))


def _default_dest(run_dir):
    """默认目标路径：<run_dir 的同级目录>/<目录名>.zip"""
    parent = os.path.dirname(os.path.abspath(run_dir))
    name = os.path.basename(os.path.abspath(run_dir).rstrip("\\/"))
    return os.path.join(parent, name + ".zip")


def _iter_entries(run_dir, skip_path):
    """遍历 run_dir，产出 (绝对路径, zip 内相对路径) 二元组。

    相对路径统一把分隔符换成 '/'，这样 Windows 上打出来的 zip 在别的平台
    也能正常展开。skip_path 是目标 zip 自身，避免把它自己包进去。
    """
    skip_abs = os.path.normcase(os.path.abspath(skip_path)) if skip_path else None
    base = os.path.abspath(run_dir)
    for cur, dirnames, filenames in os.walk(base):
        dirnames.sort()
        for fname in sorted(filenames):
            full = os.path.join(cur, fname)
            if skip_abs is not None and os.path.normcase(os.path.abspath(full)) == skip_abs:
                continue
            rel = os.path.relpath(full, base).replace(os.sep, "/")
            yield full, rel


def zip_run_dir(run_dir, dest=None):
    """把一个测试结果目录压成一个 zip。

    run_dir: 结果目录绝对路径
    dest:    zip 目标路径；None 时自动生成 <run_dir 同级>/<目录名>.zip
    成功返回 (zip绝对路径, 文件数)，失败返回 (None, 中文错误原因)
    """
    if not run_dir or not os.path.isdir(run_dir):
        return (None, "结果目录不存在：%s" % run_dir)

    run_abs = os.path.abspath(run_dir)
    if dest is None:
        dest_abs = os.path.abspath(_default_dest(run_abs))
    else:
        dest_abs = os.path.abspath(dest)

    # 目标已存在就直接覆盖；顺手确保父目录在
    try:
        dest_parent = os.path.dirname(dest_abs)
        if dest_parent and not os.path.isdir(dest_parent):
            os.makedirs(dest_parent)
        if os.path.exists(dest_abs):
            os.remove(dest_abs)
    except OSError as exc:
        return (None, "打包失败：%s" % exc)

    count = 0
    try:
        with zipfile.ZipFile(dest_abs, "w", zipfile.ZIP_DEFLATED) as zf:
            for full, rel in _iter_entries(run_abs, dest_abs):
                zf.write(full, arcname=rel)
                count += 1
    except (OSError, zipfile.BadZipFile, zipfile.LargeZipFile, RuntimeError, ValueError) as exc:
        # 打包中途失败就把半成品删掉，别留一个坏 zip 骗人
        try:
            if os.path.exists(dest_abs):
                os.remove(dest_abs)
        except OSError:
            pass
        return (None, "打包失败：%s" % exc)
    except Exception as exc:  # 兜底：任何意外都不许裸抛
        try:
            if os.path.exists(dest_abs):
                os.remove(dest_abs)
        except OSError:
            pass
        return (None, "打包失败：%s" % exc)

    return (dest_abs, count)


def _main(argv):
    """命令行入口：python pack.py <结果目录> [zip路径]，供手工验证用。"""
    if len(argv) < 2:
        sys.stdout.write("用法：python pack.py <结果目录> [zip目标路径]\n")
        return 2
    dest = argv[2] if len(argv) > 2 else None
    path, info = zip_run_dir(argv[1], dest)
    if path is None:
        sys.stdout.write("打包失败：%s\n" % info)
        return 1
    size = human_size(os.path.getsize(path))
    sys.stdout.write("打包成功：%s（%d 个文件，%s）\n" % (path, info, size))
    return 0


if __name__ == "__main__":
    sys.exit(_main(sys.argv))

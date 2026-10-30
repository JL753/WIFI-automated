# -*- coding: utf-8 -*-
"""包内模块用相对导入，所以必须由一个顶层脚本把它们当包导入。

启动.bat 设好 PYTHONPATH=tools 后运行本文件。
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)

try:
    from wb import cli
except Exception:
    import traceback
    print('')
    print('  [错误] 加载工具模块失败')
    print('  原因  ：tools\\wb 下的 .py 文件缺失或损坏')
    print('  处理办法：重新完整拷贝整个工具目录；下面是具体错误')
    traceback.print_exc()
    sys.exit(1)

if __name__ == '__main__':
    sys.exit(cli.main())

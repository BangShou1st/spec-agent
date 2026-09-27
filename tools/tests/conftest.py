"""文件名:conftest.py

诊断工具测试的 pytest 路径配置(不 import 生产代码)。
"""
import sys
from pathlib import Path

TOOLS_DIR = Path(__file__).resolve().parents[1]
if str(TOOLS_DIR) not in sys.path:
    sys.path.insert(0, str(TOOLS_DIR))

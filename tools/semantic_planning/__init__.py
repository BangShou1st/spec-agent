"""文件名:__init__.py

R4 语义规划诊断包(仅诊断用,仅依赖标准库)。

生产代码绝不 import 本包。实现 planning-state.v2 schema(C1)、
G1-G5 参考目标、C2 交叉校验、planning-mapping.v2、R4 提示词,
以及合成校准夹具。
"""

SCHEMA_VERSION = "planning-state.v2"
MAPPING_VERSION = "planning-mapping.v2"
REFERENCE_GOAL_VERSION = "r4-goalref.v1"

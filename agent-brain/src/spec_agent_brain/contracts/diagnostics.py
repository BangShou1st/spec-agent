"""文件名:diagnostics.py(contracts 包)

用途:Brain 契约失败诊断的跨引擎共享契约与构造原语。

``ContractDiagnostic`` 是失败路径的结构化事实,经 /v1/* 端点错误体的
``contractDiagnostic`` 字段过线,与 Java 侧的 ``ModelContractDiagnostic``
一一对应;``app._fail`` 负责序列化为 camelCase。它只承载可安全外发的
结构化事实(阶段、校验器、机器码、字段路径、类型期望),绝不承载模型
输出、prompt、凭据或异常原文——这是三个引擎共同的脱敏边界。

与顶层 ``diagnostics.py`` 的职责划分:那边的 ``semantic_diagnostics``
属于成功/观察侧,嵌入响应信封的 ``diagnostics`` 字段(语义输入追踪);
本模块只描述失败侧的契约违约事实。两者消费方与数据边界不同,不合并。
"""

import uuid
from dataclasses import dataclass
from typing import Any, Collection


@dataclass(frozen=True)
class ContractDiagnostic:
    diagnostic_id: str
    stage: str
    validator_id: str
    machine_code: str
    field_path: str | None
    expected_type: str | None
    actual_type: str | None
    boundary: str = "PYTHON_BRAIN"
    java_wire_validator_entered: bool = False
    executor_reached: bool = False
    source_exception_type: str | None = None


def contract_diagnostic(*, stage: str, validator_id: str, machine_code: str,
                        field_path: str | None, expected_type: str | None,
                        actual_type: str | None,
                        source_exception_type: str | None) -> ContractDiagnostic:
    return ContractDiagnostic(
        diagnostic_id=str(uuid.uuid4()),
        stage=stage,
        validator_id=validator_id,
        machine_code=machine_code,
        field_path=field_path,
        expected_type=expected_type,
        actual_type=actual_type,
        source_exception_type=source_exception_type,
    )


def safe_field_path(location: Any, known_fields: Collection[str]) -> str | None:
    """把 pydantic 错误位置渲染为无值字段路径;遇到白名单之外的键即放弃。

    模型输出里的字段名与值都可能由模型挑选,未知键可以携带任意用户内容,
    所以只有调用方声明过的字段名才允许进入诊断。
    """
    if not isinstance(location, (tuple, list)):
        return None
    parts: list[str] = []
    for item in location[:8]:
        if isinstance(item, int) and item >= 0:
            parts.append(f"[{item}]")
        elif isinstance(item, str) and item in known_fields:
            if parts:
                parts.append(".")
            parts.append(item)
        else:
            # Unknown model-selected keys can contain user content; don't echo them.
            return None
    path = "".join(parts)
    return path[:160] if path else None


def actual_type(value: Any) -> str:
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "boolean"
    if isinstance(value, str):
        return "string"
    if isinstance(value, int):
        return "integer"
    if isinstance(value, float):
        return "number"
    if isinstance(value, list):
        return "array"
    if isinstance(value, dict):
        return "object"
    return "unknown"


def expected_type(error_type: Any) -> str | None:
    return {
        "string_type": "string",
        "int_type": "integer",
        "float_type": "number",
        "bool_type": "boolean",
        "list_type": "array",
        "dict_type": "object",
    }.get(error_type)

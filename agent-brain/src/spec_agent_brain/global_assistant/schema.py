"""Closed schema dialect and deterministic tool names; never infer business fields."""

from copy import deepcopy
import re
from typing import Any

from jsonschema import Draft202012Validator


def tool_name(capability_id: str) -> str:
    name = capability_id.replace(".", "_")
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", name):
        raise ValueError("capability ID cannot be represented as a tool name")
    return name


def normalize_schema(schema: dict[str, Any]) -> dict[str, Any]:
    """Convert the host's simple field maps, or validate a closed JSON Schema."""
    if "type" not in schema:
        properties = {}
        required = []
        for name, value in schema.items():
            if not isinstance(value, dict):
                raise ValueError("field schema must be an object")
            item = deepcopy(value)
            is_required = item.pop("required", False)
            if not isinstance(is_required, bool):
                raise ValueError("simple field required must be boolean")
            if is_required:
                required.append(name)
            properties[name] = item
        schema = {"type": "object", "properties": properties,
                  "required": required, "additionalProperties": False}
    else:
        schema = deepcopy(schema)
    _validate_node(schema)
    if schema.get("type") != "object":
        raise ValueError("tool schema root must be object")
    Draft202012Validator.check_schema(schema)
    return schema


def _validate_node(node: dict[str, Any]) -> None:
    if not isinstance(node, dict):
        raise ValueError("schema node must be object")
    allowed = {"type", "description", "enum", "properties", "required", "additionalProperties",
               "items", "minLength", "maxLength", "minimum", "maximum", "minItems", "maxItems"}
    if set(node) - allowed:
        raise ValueError("unsupported schema keyword")
    kind = node.get("type")
    if kind not in {"object", "array", "string", "integer", "number", "boolean"}:
        raise ValueError("unsupported schema type")
    if kind == "object":
        if node.get("additionalProperties", False) is not False:
            raise ValueError("unknown object arguments must be forbidden")
        node["additionalProperties"] = False
        if not isinstance(node.get("properties", {}), dict):
            raise ValueError("properties must be object")
        if not isinstance(node.get("required", []), list):
            raise ValueError("required must be array")
        if set(node.get("required", [])) - set(node.get("properties", {})):
            raise ValueError("required references unknown property")
        for child in node.get("properties", {}).values():
            _validate_node(child)
    elif kind == "array":
        if "items" not in node:
            raise ValueError("array requires bounded item schema")
        _validate_node(node["items"])
    if kind != "object" and any(k in node for k in ("properties", "required", "additionalProperties")):
        raise ValueError("object keywords on non-object")
    if kind != "array" and "items" in node:
        raise ValueError("array keywords on non-array")

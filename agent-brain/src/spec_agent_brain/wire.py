"""Shared strict internal JSON utilities; contains no agent state or business protocol."""
import hashlib
import json
from typing import Any

from pydantic import BaseModel, ConfigDict, ValidationError


def canonical_hash(value: Any) -> str:
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"),
                                     ensure_ascii=False, allow_nan=False).encode()).hexdigest()


class WireModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, populate_by_name=False)

    @classmethod
    def model_validate_json(cls, json_data, **kwargs):
        def pairs(items):
            result = {}
            for key, value in items:
                if key in result:
                    raise ValueError("duplicate JSON key")
                result[key] = value
            return result

        def constant(value):
            raise ValueError("invalid JSON number")

        try:
            json.loads(json_data, object_pairs_hook=pairs, parse_constant=constant)
        except (ValueError, TypeError):
            raise ValidationError.from_exception_data(cls.__name__, [{
                "type": "value_error", "loc": (), "input": None,
                "ctx": {"error": ValueError("Invalid internal JSON")},
            }]) from None
        return super().model_validate_json(json_data, **kwargs)

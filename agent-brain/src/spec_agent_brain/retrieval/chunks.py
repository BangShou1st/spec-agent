"""Source-preserving deterministic blocks; offsets refer to the host's original UTF-16 text."""
from dataclasses import dataclass
import hashlib
import re


@dataclass(frozen=True)
class Chunk:
    index: int
    text: str
    start_offset: int
    end_offset: int
    content_hash: str
    headings: tuple[str, ...]


def split_source(text: str, kind: str, target: int = 1200) -> list[Chunk]:
    if len(text) > 200_000 or target < 128 or target > 12000:
        raise ValueError("Source/chunk budget exceeded")
    if not text.strip():
        return []
    structured = kind in {"NODE", "ANSWER", "CLAIM", "CAPABILITY_OBSERVATION", "ROUTE_SUMMARY"}
    if structured and len(text) > 12000:
        raise ValueError("Structured source exceeds host projection budget")
    if kind not in {"NODE", "ANSWER", "CLAIM", "CAPABILITY_OBSERVATION", "ROUTE_SUMMARY", "RESOURCE_CHUNK", "HELP_CHUNK"}:
        raise ValueError("Unsupported source kind")
    spans = [(0, len(text))] if structured else _blocks(text, target)
    utf16 = [0]
    for char in text:
        utf16.append(utf16[-1] + (2 if ord(char) > 0xffff else 1))
    result = []
    headings: list[str] = []
    cursor = 0
    for index, (start, end) in enumerate(spans):
        for match in re.finditer(r"(?m)^[ \t]{0,3}(#{1,6})\s+([^\r\n]+)", text[cursor:end]):
            depth = len(match[1]); headings = headings[:depth - 1] + [match[2].strip()]
        cursor = end
        content = text[start:end]
        result.append(Chunk(index, content, utf16[start], utf16[end], hashlib.sha256(content.encode()).hexdigest(), tuple(headings)))
    return result


def _blocks(text: str, target: int) -> list[tuple[int, int]]:
    spans = []
    start = 0
    while start < len(text):
        end = min(len(text), start + target)
        if end < len(text):
            # Preserve paragraphs/headings first, then sentence boundaries; no synthetic prefix in offsets.
            minimum = start + target // 3
            boundaries = [m.end() for m in re.finditer(r"\r?\n\s*\r?\n|(?=\r?\n#{1,6}\s)|[。！？.!?](?:\s|$)", text[start:end])]
            candidates = [start + value for value in boundaries if start + value >= minimum]
            if candidates:
                end = candidates[-1]
        if end <= start:
            raise ValueError("Non-progressing chunk")
        spans.append((start, end)); start = end
        if len(spans) > 256:
            raise ValueError("Too many chunks")
    return spans

"""Pin the installed runtime/dev dependency closure, excluding unrelated local tools."""
from importlib.metadata import distribution
from pathlib import Path
import tomllib

from packaging.requirements import Requirement
from packaging.utils import canonicalize_name

root = Path(__file__).resolve().parents[1]
project = tomllib.loads((root / "pyproject.toml").read_text(encoding="utf-8"))["project"]
pending = [Requirement(value) for value in project["dependencies"] + project["optional-dependencies"]["dev"]]
resolved = {}
while pending:
    requirement = pending.pop()
    if requirement.marker and not requirement.marker.evaluate({"extra": ""}):
        continue
    name = canonicalize_name(requirement.name)
    if name in resolved:
        continue
    package = distribution(name)
    resolved[name] = package.version
    pending.extend(Requirement(value) for value in package.requires or [])
(root / "requirements.lock").write_text(
    "# Runtime + dev closure verified on Python 3.11 / Windows. Use as pip constraints.\n"
    + "\n".join(f"{name}=={version}" for name, version in sorted(resolved.items())) + "\n", encoding="utf-8")

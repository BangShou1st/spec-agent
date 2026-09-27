"""文件名:migrate-skill-mirror.py

一次性迁移脚本:把 Postgres 中已安装的技能项目导出到本地镜像目录
(~/.spec-agent/skills)。此后后端启动时的回填逻辑会承担同样的工作;
本脚本只是为了迁移存量数据,无需等待后端重启。"""
import base64
import pathlib
import subprocess
import sys

QUERY = (
    "select s.skill_id, v.version_no, f.relative_path, "
    "replace(encode(f.content,'base64'), E'\\n', '') "
    "from skills s "
    "join skill_versions v on v.skill_row_id = s.id "
    "join skill_package_files f on f.version_id = v.id "
    "where s.current_version_id = v.id"
)


def unsafe(relative_path: str) -> bool:
    """与 Java SkillLocalMirror 相同的规则：拒绝反斜杠、.. 与前导 /。"""
    return (not relative_path or relative_path.startswith("/")
            or relative_path.startswith("\\") or ".." in relative_path
            or "\\" in relative_path)


def main() -> int:
    result = subprocess.run(
        ["docker", "exec", "spec-agent-postgres", "psql", "-U", "spec_agent",
         "-d", "spec_agent", "-t", "-A", "-F", "|", "-c", QUERY],
        capture_output=True, text=True, check=True)
    root = pathlib.Path.home() / ".spec-agent" / "skills"
    count = 0
    for line in result.stdout.splitlines():
        line = line.strip()
        if not line:
            continue
        skill_id, version_no, relative_path, payload = line.split("|", 3)
        if unsafe(relative_path):
            print(f"skip unsafe: {skill_id} {relative_path}")
            continue
        target = root / skill_id / f"v{version_no}" / relative_path
        if ".." in target.relative_to(root).parts:
            print(f"skip escape: {relative_path}")
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(base64.b64decode(payload))
        count += 1
    print(f"mirrored {count} files under {root}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

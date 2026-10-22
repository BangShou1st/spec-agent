"""Read files/Gradle JUnit and lint the proposed SQL; never connects to a database."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parent

def suites(directory):
    rows = []
    for path in sorted(directory.glob('TEST-*.xml')):
        suite = ET.parse(path).getroot()
        rows.append({'name': suite.attrib['name'], **{key: int(suite.get(key, 0))
                    for key in ('tests', 'failures', 'errors', 'skipped')}})
    assert rows
    total = {key: sum(row[key] for row in rows) for key in ('tests', 'failures', 'errors', 'skipped')}
    assert total['failures'] == total['errors'] == total['skipped'] == 0, total
    return {'total': total, 'suites': rows}

plan = Path(__file__).with_name('database-readonly-audit-plan.md').read_text(encoding='utf-8')
blocks = re.findall(r'```sql\n(.*?)```', plan, flags=re.S)
assert len(blocks) == 18, len(blocks)
for block in blocks:
    without_comments = re.sub(r'--[^\n]*', '', block)
    code = re.sub(r"'(?:''|[^'])*'", "''", without_comments)
    assert not re.search(r'\b(INSERT|UPDATE|DELETE|CREATE|DROP|ALTER|TRUNCATE|COPY|CALL|DO|INTO|LOCK)\b', code, re.I), code
    for statement in code.split(';'):
        if statement.strip():
            assert statement.split()[0].upper() in ('BEGIN', 'SET', 'SELECT', 'ROLLBACK'), statement

migrations = {}
for path in sorted((ROOT / 'backend/src/main/resources/db/migration').glob('V9[89]__*.sql')):
    rel = path.relative_to(ROOT).as_posix()
    original = subprocess.check_output(['git', 'show', '2ff6f07:' + rel], cwd=ROOT)
    # Git checkout on Windows may use CRLF. Compare normalized source bytes.
    content = path.read_bytes().replace(b'\r\n', b'\n')
    assert content == original.replace(b'\r\n', b'\n')
    migrations[path.name] = hashlib.sha256(content).hexdigest()
assert len(migrations) == 2
v99 = next((ROOT / 'backend/src/main/resources/db/migration').glob('V99__*.sql')).read_text(encoding='utf-8')
assert "WHERE status NOT IN ('created', 'completed', 'failed')" in v99
assert 'IS NULL' not in v99.upper()
status_source = (ROOT / 'backend/src/main/java/com/specagent/agent/runtime/AgentRunStatus.java').read_text(encoding='utf-8')
status_names = re.findall(r'^    ([A-Z_]+)[,;]', status_source, flags=re.M)
assert 'AWAITING_APPROVAL' not in status_names
matrix = [{'database_status': status.lower(), 'v99_overwrites_null_and_existing_fingerprint':
           status.lower() not in ('created', 'completed', 'failed')} for status in status_names]
report = {
    'scope': 'offline file/JUnit audit only; no SQL execution or server identity verification',
    'independent': suites(ROOT / 'backend/independent/build/test-results/test'),
    'root_offline': suites(ROOT / 'backend/build/test-results/testOffline'),
    'sql_plan': {'query_blocks': 17, 'control_block': 1, 'lexical_readonly_check': 'PASS',
                 'postgresql_execution': 'NOT RUN', 'sql_parser_or_database_validation': 'NOT RUN'},
    'migration_sources_unchanged_sha256_lf': migrations,
    'v99_predicate_matrix': {'status': 'STATIC_ONLY, not a migration test', 'rows': matrix},
}
OUT.mkdir(parents=True, exist_ok=True)
(OUT / 'offline-verification.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({key: value.get('total', value) for key, value in report.items()
                  if key in ('independent', 'root_offline', 'sql_plan')}, ensure_ascii=False, indent=2))

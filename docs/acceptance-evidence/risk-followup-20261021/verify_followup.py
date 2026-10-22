"""Summarize actual local JUnit and static migration evidence; never executes SQL."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
EVIDENCE = Path(sys.argv[1])

def summarize(relative, expected):
    suites = []
    for path in sorted((ROOT / relative).glob('TEST-*.xml')):
        root = ET.parse(path).getroot()
        suites.append({'name': root.get('name'), **{key: int(root.get(key, '0'))
                       for key in ('tests', 'failures', 'errors', 'skipped')}})
    totals = {key: sum(row[key] for row in suites) for key in ('tests', 'failures', 'errors', 'skipped')}
    assert totals == {'tests': expected, 'failures': 0, 'errors': 0, 'skipped': 0}, totals
    return {'totals': totals, 'suites': suites}

sha = {}
for path in sorted((ROOT / 'backend/src/main/resources/db/migration').glob('V9[89]__*.sql')):
    relative = path.relative_to(ROOT).as_posix()
    baseline = subprocess.check_output(['git', 'show', '880bdd9:' + relative], cwd=ROOT).replace(b'\r\n', b'\n')
    content = path.read_bytes().replace(b'\r\n', b'\n')
    assert content == baseline
    sha[path.name] = hashlib.sha256(content).hexdigest()
assert len(sha) == 2
assert not subprocess.check_output(['git', 'diff', '880bdd9', '--',
    'agent-brain/src/spec_agent_brain/prompts', 'backend/src/main/resources/db/migration'], cwd=ROOT)

replay = json.loads((EVIDENCE / 'contract-replay.json').read_text(encoding='utf-8-sig'))
assert replay['fixtureCount'] == replay['outcomeMatched'] == replay['actionMatched'] == 11
assert not replay['payloadMismatches']
historical = json.loads((EVIDENCE / 'historical-evidence.json').read_text(encoding='utf-8-sig'))
assert historical['status'] == 'PASS'
assert historical['gemini']['historical_decision'] == 'REJECT'
protected = json.loads((EVIDENCE / 'authorization-checklist-hash.json').read_text(encoding='utf-8-sig'))
assert hashlib.sha256(Path(protected['Path']).read_bytes()).hexdigest().upper() == protected['Hash']
report = {
    'scope': 'actual offline results; no application startup, SQL, database, external model or network-provider experiment',
    'independent': summarize('backend/independent/build/test-results/test', 102),
    'root_offline': summarize('backend/build/test-results/testOffline', 81),
    'python': {'tests': 485, 'source': 'python-final.log', 'status': 'PASS'},
    'migration_sources_unchanged_sha256_lf': sha,
    'migration_execution': 'NOT RUN', 'real_database_concurrency_and_restart': 'NOT RUN',
    'contract_replay': {'outcomes': '11/11', 'actions': '11/11', 'payload_mismatches': 0,
                        'scope': 'frozen synthetic contract fixtures, not live model quality'},
    'historical_evidence': {'consistency': 'PASS', 'C03': 'REJECT', 'new_live_requests': 0},
    'previous_authorization_checklist_unchanged': True,
}
assert '485 passed' in (EVIDENCE / 'python-final.log').read_text(encoding='utf-8-sig')
(EVIDENCE / 'verification.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps({key: report[key] for key in ('migration_execution', 'python', 'historical_evidence')}, ensure_ascii=False))

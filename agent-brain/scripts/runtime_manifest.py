"""Record exact candidate provenance; fail instead of repairing an existing environment."""
import argparse
import hashlib
import importlib.metadata
import json
import platform
import re
import sys
from datetime import datetime, timezone
from pathlib import Path


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    brain = Path(__file__).resolve().parents[1]
    lock = brain / 'requirements.lock'
    expected = dict(re.findall(r'^([\w.-]+)==([^\s]+)$', lock.read_text(encoding='utf-8'), re.M))
    actual = {name: importlib.metadata.version(name) for name in expected}
    if platform.python_version() != '3.11.14' or sys.prefix == sys.base_prefix or actual != expected:
        raise SystemExit('Candidate interpreter/dependencies do not match; no retry or mutation')
    original = brain / '.venv'
    if Path(sys.prefix).resolve() == original.resolve():
        raise SystemExit('Candidate must be separate from original .venv')
    import pydantic_core
    native = next(Path(pydantic_core.__file__).parent.glob('*.pyd'))
    base = Path(sys.base_prefix)
    runtime_lock = json.loads((brain / 'candidate-runtime.lock.json').read_text(encoding='utf-8'))
    fingerprints = {'requirementsLockSha256': sha(lock), 'pythonExeSha256': sha(base / 'python.exe'),
                    'pythonDllSha256': sha(base / 'python311.dll'), 'pydanticCoreNativeSha256': sha(native)}
    if any(runtime_lock[key] != value for key, value in fingerprints.items()) or \
            (base / 'BUILD').read_text().strip() != runtime_lock['standaloneBuild']:
        raise SystemExit('Candidate build fingerprints differ; no overwrite, repair or retry')
    report = {
        'recordedAt': datetime.now(timezone.utc).isoformat(), 'status': 'MANIFEST_VERIFIED',
        'pythonVersion': platform.python_version(), 'platform': platform.platform(),
        'implementation': platform.python_implementation(), 'executable': sys.executable,
        'basePrefix': sys.base_prefix, 'requirementsLockSha256': sha(lock),
        'lockedDistributions': actual, 'pythonExeSha256': sha(base / 'python.exe'),
        'pythonDllSha256': sha(base / 'python311.dll'), 'pydanticCoreNativeSha256': sha(native),
        'originalVenvFingerprints': {str(p.relative_to(brain)): sha(p) for p in
            [original / 'pyvenv.cfg', original / 'Scripts/python.exe'] if p.is_file()},
        'source': 'uv managed python-build-standalone, exact 3.11.14; no global bin/registry changes',
        'originalEnvironmentReplaced': False, 'productionRuntimeChanged': False,
        'qualificationLimit': 'Manifest equality is not a startup or deployment qualification.'
    }
    report['runtimeLockSha256'] = sha(brain / 'candidate-runtime.lock.json')
    report['standaloneBuild'] = runtime_lock['standaloneBuild']
    report['archiveUrl'] = runtime_lock['archiveUrl']
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()

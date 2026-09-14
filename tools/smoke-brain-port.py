#!/usr/bin/env python3
"""Smoke test: verify brain broker URL matches expected backend port.

Usage:  python tools/smoke-brain-port.py <BACKEND_PORT>
Example: python tools/smoke-brain-port.py 8081
"""
import subprocess
import sys


def docker_exec(cmd: str) -> str:
    """Run a command inside the agent-brain container and return stdout."""
    result = subprocess.run(
        ["docker", "exec", "spec-agent-brain", "python", "-c", cmd],
        capture_output=True, text=True, timeout=10
    )
    return result.stdout.strip()


def main():
    print("=== Brain Port Smoke Test ===\n")

    # --- 1. Validate argument ---
    if len(sys.argv) < 2:
        print("  [FAIL] No port argument provided.")
        print(f"  Usage: python {sys.argv[0]} <BACKEND_PORT>")
        sys.exit(1)

    try:
        expected_port = int(sys.argv[1])
    except ValueError:
        print(f"  [FAIL] Port must be a number, got: {sys.argv[1]}")
        sys.exit(1)

    if not (1 <= expected_port <= 65535):
        print(f"  [FAIL] Port must be 1-65535, got: {expected_port}")
        sys.exit(1)

    print(f"  Expected backend port : {expected_port}")

    # --- 2. Verify backend is reachable at expected port ---
    try:
        status = docker_exec(
            f"import urllib.request; "
            f"r=urllib.request.urlopen('http://host.docker.internal:"
            f"{expected_port}/actuator/health', timeout=3); print(r.status)"
        )
    except Exception as e:
        status = f"ERROR: {e}"

    if status != "200":
        print(f"  Backend health        : FAIL (HTTP {status})")
        print(f"  [FAIL] Backend not reachable at port {expected_port}.")
        sys.exit(1)
    print("  Backend health        : PASS (HTTP 200)")

    # --- 3. Read brain broker URL target port ---
    broker_url = docker_exec(
        "import os; url=os.environ.get('SPEC_AGENT_INTERNAL_BROKER_URL',''); "
        "print(url.split(':')[-1].split('/')[0] if ':' in url else 'NONE')"
    )
    print(f"  Brain broker target   : host.docker.internal:{broker_url}")

    # --- 4. Compare ---
    if str(expected_port) == broker_url:
        print("  Port match            : PASS")
    else:
        print("  Port match            : FAIL")
        print(f"  [FAIL] DRIFT DETECTED! Expected {expected_port} but brain points to {broker_url}.")
        sys.exit(1)

    # --- 5. Brain health ---
    brain_status = docker_exec(
        "import urllib.request; "
        "r=urllib.request.urlopen('http://localhost:8100/health', timeout=2); "
        "print(r.status)"
    )
    if brain_status == "200":
        print("  Brain health          : PASS (HTTP 200)")
    else:
        print(f"  Brain health          : FAIL (HTTP {brain_status})")
        print("  [WARN] Brain is not healthy. Check: docker compose logs agent-brain")
        sys.exit(1)

    # --- 6. Brain has available_skills ---
    skills = docker_exec(
        "from spec_agent_brain.contracts.inputs import AgentInputSnapshot; "
        "print('available_skills' in AgentInputSnapshot.model_fields)"
    )
    if skills == "True":
        print("  available_skills      : PASS")
    else:
        print("  available_skills      : FAIL")
        sys.exit(1)

    print("\n  [PASS] All checks passed.")
    sys.exit(0)


if __name__ == "__main__":
    main()

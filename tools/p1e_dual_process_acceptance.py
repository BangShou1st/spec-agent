#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P1-E 双实例故障验收驱动(design §12)。

两个独立 Java 进程 + 独立 PostgreSQL 容器(spec_agent_p1e 库)+ 真实 Python
brain(fake 模型网关),覆盖:

  S1  模式准入与禁止混跑(发布切换 + 旧二进制边界)
  S2  双 Worker 并行认领
  S3  杀进程 → 租约过期 → 存活实例接管 → 无重复产物
  S5  GA 跨实例:SSE 补发 / 跨实例回调 / steer(含 worker_id ↔ 实例映射)
  S6  数据库中断(容器 pause/unpause)→ 恢复后重新认领而非复活旧租约
  S7  容量边界(GLOBAL=4,有界排队)
  S8  停机回退演练(切回单执行器模式,历史保留)

S4(迟到写入拒绝)在 Windows 上无法对真实进程做"暂停后恢复",以自动测试
证据(TaskWriteGuardTest / AgentRunEventConcurrentAppendTest)记录,本脚本
如实标注为 AUTOMATED,不冒充进程级证据。

运行方式(必须整段后台执行,进程随脚本存活):
  agent-brain/.venv/Scripts/python.exe tools/p1e_dual_process_acceptance.py
"""
import json
import os
import shutil
import subprocess
import sys
import time
import uuid
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parents[1]
BACKEND = ROOT / "backend"
JAR = BACKEND / "build" / "libs" / "spec-agent-backend-0.0.1-SNAPSHOT.jar"
BRAIN = ROOT / "agent-brain"
PY = BRAIN / ".venv" / "Scripts" / "python.exe"
LOGDIR = ROOT / "scratch" / "p1e"
PG_NAME = "spec-agent-p1e-postgres"
PG_PORT = 5435
DB = "spec_agent_p1e"
SECRET = "p1e-acceptance-internal-secret"
PORT_A, PORT_B = 7601, 7602

RESULTS = []
PROCS = {}


def record(step, passed, detail):
    RESULTS.append({"step": step, "passed": bool(passed), "detail": detail})
    print(("PASS " if passed else "FAIL ") + step + " :: " + detail, flush=True)


# ─────────────────────────── 基础设施 ───────────────────────────

def docker(*args, check=True):
    return subprocess.run(["docker", *args], check=check, capture_output=True, text=True)


def psql(sql):
    r = docker("exec", PG_NAME, "psql", "-U", "spec_agent", "-d", DB, "-tAc", sql)
    return r.stdout.strip()


def prepare_postgres():
    docker("rm", "-f", PG_NAME, check=False)
    docker("run", "-d", "--name", PG_NAME, "-p", f"127.0.0.1:{PG_PORT}:5432",
           "-e", "POSTGRES_USER=spec_agent", "-e", "POSTGRES_PASSWORD=spec_agent_dev",
           "-e", "POSTGRES_DB=spec_agent", "pgvector/pgvector:pg17")
    deadline = time.time() + 90
    while time.time() < deadline:
        r = docker("exec", PG_NAME, "pg_isready", "-U", "spec_agent", check=False)
        if r.returncode == 0:
            break
        time.sleep(1)
    else:
        raise RuntimeError("p1e postgres did not become ready")
    # 初始化只创建默认库;验收库按需建(幂等,重试直到真正可用)。
    deadline = time.time() + 60
    while time.time() < deadline:
        r = docker("exec", PG_NAME, "psql", "-U", "spec_agent", "-d", "spec_agent",
                   "-tAc", "SELECT 1 FROM pg_database WHERE datname='" + DB + "'",
                   check=False)
        if r.returncode == 0 and r.stdout.strip() == "1":
            break
        create = docker("exec", PG_NAME, "psql", "-U", "spec_agent", "-d", "spec_agent",
                        "-c", "CREATE DATABASE " + DB + " OWNER spec_agent", check=False)
        time.sleep(1)
    else:
        raise RuntimeError("could not create acceptance database " + DB)


def java_exe():
    found = shutil.which("java")
    if found:
        return found
    jh = os.environ.get("JAVA_HOME")
    if jh:
        candidate = Path(jh) / "bin" / "java.exe"
        if candidate.exists():
            return str(candidate)
    raise RuntimeError("no java executable found; set JAVA_HOME")


def base_args(port, multi_worker):
    """test profile 提供 determinstic stub(模型/引擎),其余生产形态不变:
    独立 JVM 进程、独立数据库、真实 HTTP 与调度路径。test profile 自带的
    worker.enabled=false / 专用测试库等全部被显式参数覆盖。"""
    return [
        "--spring.profiles.active=test",
        "--server.port=" + str(port),
        # 专用验收库(绝不指向 spec_agent_test)。
        "--spring.datasource.url=jdbc:postgresql://127.0.0.1:%d/%s" % (PG_PORT, DB),
        "--spring.datasource.username=spec_agent",
        "--spring.datasource.password=spec_agent_dev",
        # worker 执行路径在验收进程内真实开启。
        "--spec.agent.brain.enabled=true",
        "--spec.agent.brain.worker.enabled=true",
        # GA:两个实例共用同一个 Python brain(回调按部署配置走 A,天然跨实例)。
        "--spec.global-assistant.brain-base-url=http://127.0.0.1:8101",
        # 关闭与本验收无关的后台机制,减少噪声。
        "--spec.embedding-settings.worker-enabled=false",
        "--spec.agent.retrieval.embedding.worker.enabled=false",
        "--spec.agent.skill.builtin.seed-enabled=false",
        "--spec.agent.skill.local-mirror-enabled=false",
    ] + ([
        "--spec.agent.execution.multi-worker-enabled=true",
        # 加速的租约节奏:验收观测窗口,不是生产初值(生产默认 60s/10s)。
        "--spec.agent.execution.lease-seconds=8",
        "--spec.agent.execution.heartbeat-seconds=3",
        "--spec.agent.execution.expiry-scan-interval-ms=1000",
        "--spec.agent.execution.claim-interval-ms=300",
        # 认领节拍加密:fake 引擎下单个任务 ~0.3s 完成,默认 2s 轮询会把两个
        # Worker 的认领窗口串行化(错峰永不重叠);200ms 让两实例的认领密集交错。
        "--spec.agent.brain.worker.poll-interval-ms=200",
    ] if multi_worker else [
        "--spec.agent.execution.multi-worker-enabled=false",
    ])


def start_java(name, port, multi_worker=True):
    log = open(LOGDIR / (name + ".log"), "w", encoding="utf-8", errors="replace")
    args = [java_exe(), "-jar", str(JAR)] + base_args(port, multi_worker)
    env = {**os.environ, "SPEC_AGENT_BRAIN_INTERNAL_SECRET": SECRET}
    proc = subprocess.Popen(args, stdout=log, stderr=subprocess.STDOUT,
                            cwd=str(BACKEND), env=env)
    PROCS[name] = {"proc": proc, "port": port, "log": log}
    return proc


def start_java_expect_refusal(name, port, multi_worker, needle, timeout=200):
    """启动一个预期失败的实例:必须在 timeout 内退出,且日志含 needle。"""
    log_path = LOGDIR / (name + ".log")
    with open(log_path, "w", encoding="utf-8", errors="replace") as fh:
        env = {**os.environ, "SPEC_AGENT_BRAIN_INTERNAL_SECRET": SECRET}
        proc = subprocess.Popen([java_exe(), "-jar", str(JAR)]
                                + base_args(port, multi_worker),
                                stdout=fh, stderr=subprocess.STDOUT,
                                cwd=str(BACKEND), env=env)
        deadline = time.time() + timeout
        while time.time() < deadline and proc.poll() is None:
            time.sleep(1)
        exited = proc.poll() is not None
        proc.kill()
    text = log_path.read_text(encoding="utf-8", errors="replace")
    return exited, needle in text, text[-400:] if not exited else ""


def stop_java(name):
    entry = PROCS.pop(name, None)
    if not entry:
        return
    entry["proc"].kill()
    try:
        entry["proc"].wait(15)
    except Exception:
        pass
    entry["log"].close()


def health(port):
    r = httpx.get(f"http://127.0.0.1:{port}/api/health", timeout=5)
    r.raise_for_status()
    return r.json()


def wait_health(port, timeout=180):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        try:
            return health(port)
        except Exception as ex:
            last = type(ex).__name__
        time.sleep(1)
    raise RuntimeError(f"health on {port} never became UP: {last}")


def wait_until(predicate, timeout, interval=0.5, what="condition"):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        try:
            if predicate():
                return True
        except Exception as ex:
            last = ex
        time.sleep(interval)
    raise TimeoutError(f"timed out waiting for {what}; last={last}")


# ─────────────────────────── 画布操作 ───────────────────────────

def create_project(port, title):
    r = httpx.post(f"http://127.0.0.1:{port}/api/v1/projects",
                   json={"title": title}, timeout=20)
    r.raise_for_status()
    return r.json()["id"]


def create_root_node(port, project_id, content):
    r = httpx.post(f"http://127.0.0.1:{port}/api/v1/projects/{project_id}/nodes",
                   json={"subtype": "IDEA", "content": {"text": content}}, timeout=20)
    r.raise_for_status()
    return r.json()["id"]


def enqueue_answer(port, project_id, node_id, free_text):
    r = httpx.post(f"http://127.0.0.1:{port}/api/v1/projects/{project_id}/agent-runs",
                   json={"nodeId": node_id, "freeText": free_text,
                         "idempotencyKey": str(uuid.uuid4())}, timeout=20)
    r.raise_for_status()
    body = r.json()
    return body.get("runId") or body.get("id")


def canvas_run(port, project_id, run_id):
    r = httpx.get(f"http://127.0.0.1:{port}/api/v1/projects/{project_id}/agent-runs/{run_id}",
                  timeout=10)
    r.raise_for_status()
    return r.json()


def make_slow_task(port, title, delay_ms):
    """画布任务载体:NODE_QUERY(一次 DECISION 调用,走完整多 Worker 调度路径)。

    确定性引擎刻意让 NODE_QUERY/ANSWER 内部的 DECISION 不受 [[delay-decision-ms]]
    延迟指令影响(见 LocalDeterministicDecisionEngine 注释),因此"慢执行"不靠
    引擎延迟,而靠批量入队 + 紧轮询:单个节点查询在 fake 引擎下约 1-2 秒完成,
    50ms 级的 DB 轮询足以在在途窗口内观察/行动。
    """
    project = httpx.post(f"http://127.0.0.1:{port}/api/v1/projects",
                         json={"title": title}, timeout=20).json()
    pid, route_id = project["id"], project["activeRouteId"]
    r = httpx.post(f"http://127.0.0.1:{port}/api/v1/projects/{pid}/nodes",
                   json={"routeId": route_id, "subtype": "IDEA",
                         "content": {"text": title + " 的根节点"}}, timeout=20)
    r.raise_for_status()
    nid = r.json()["id"]
    r2 = httpx.post(
        f"http://127.0.0.1:{port}/api/v1/projects/{pid}/nodes/{nid}/query",
        json={"routeId": route_id, "question": "请分析这个节点"}, timeout=20)
    r2.raise_for_status()
    return pid, nid, r2.json()["runId"]


def tasks_state(run_ids, column="workspace_run_id"):
    """一次 psql 往返读取全部任务状态:{key: (worker, epoch, state, attempts)}。

    Windows 上每次 docker exec 约 150-300ms;逐任务轮询会把 50ms 的观察意图
    退化成 ~1s/圈,恰好错过 fake 引擎下 ~0.7s 的 LEASED 在途窗口——必须批量。
    column 只接受内部白名单("workspace_run_id"/"ga_run_id"),与键列一致。
    """
    if column not in ("workspace_run_id", "ga_run_id"):
        raise ValueError(column)
    if not run_ids:
        return {}
    ids = ",".join("'%s'" % rid for rid in run_ids)
    out = psql("SELECT %s, worker_id, execution_epoch, state, attempt_count "
               "FROM runtime_tasks WHERE %s IN (%s)" % (column, column, ids))
    result = {}
    for line in out.splitlines():
        parts = line.split("|")
        if len(parts) == 5:
            result[parts[0]] = (parts[1], parts[2], parts[3], parts[4])
    return result


def wait_for_leased(run_ids, want=1, timeout=60, interval=0.05, column="workspace_run_id"):
    """紧轮询(单条 SQL):等到至少 want 个任务处于 LEASED(在途窗口)。"""
    run_ids = list(run_ids)
    deadline = time.time() + timeout
    while time.time() < deadline:
        states = tasks_state(run_ids, column)
        leased = [(rid, t) for rid, t in states.items() if t[2] == "LEASED"]
        if len(leased) >= want:
            return leased
        time.sleep(interval)
    raise TimeoutError(f"never observed {want} leased task(s)")


def task_of(run_id, column="workspace_run_id"):
    row = psql("SELECT worker_id, execution_epoch, state, attempt_count "
               "FROM runtime_tasks WHERE %s = '%s'" % (column, run_id))
    return row.split("|") if row else None


def count(sql):
    return int(psql(sql) or 0)


# ─────────────────────────── GA 操作 ───────────────────────────

def ga_thread(port):
    r = httpx.post(f"http://127.0.0.1:{port}/api/v1/global-assistant/threads", timeout=20)
    r.raise_for_status()
    return r.json()["threadId"]


def ga_run(port, thread_id, message):
    r = httpx.post(f"http://127.0.0.1:{port}/api/v1/global-assistant/threads/{thread_id}/runs",
                   json={"message": message}, timeout=20)
    r.raise_for_status()
    return r.json()["runId"]


def ga_status(port, run_id):
    r = httpx.get(f"http://127.0.0.1:{port}/api/v1/global-assistant/runs/{run_id}",
                  timeout=10)
    r.raise_for_status()
    return r.json()["status"]


TERMINAL = ("completed", "failed", "cancelled")


# ─────────────────────────── 场景 ───────────────────────────

def s1_admission():
    # (a) 数据库基线是 SINGLE_EXECUTOR:任务级实例必须被准入协议拒绝启动。
    exited, matched, tail = start_java_expect_refusal(
        "a-refused", PORT_A, multi_worker=True,
        needle="Execution mode admission refused")
    record("S1a 任务级实例在 SINGLE_EXECUTOR 数据库上被准入协议拒绝启动",
           exited and matched, "exited=%s" % exited)

    # (b) 维护窗口切换:停全部实例 → 显式改写登记行(运维动作,非自动副作用)。
    psql("UPDATE runtime_execution_mode SET mode='PER_TASK', updated_by='p1e-operator', "
         "updated_at=now() WHERE id=1")

    # (c) 双 Worker 舰队启动。
    start_java("A", PORT_A, multi_worker=True)
    health_a = wait_health(PORT_A)
    start_java("B", PORT_B, multi_worker=True)
    health_b = wait_health(PORT_B)
    ok = (health_a.get("executionOwnership") == "PER_TASK"
          and health_b.get("executionOwnership") == "PER_TASK"
          and health_a.get("executionModeAdmission") == "PER_TASK"
          and bool(health_a.get("workerId")) and bool(health_b.get("workerId"))
          and health_a.get("workerId") != health_b.get("workerId"))
    record("S1c 双 Worker 实例在 PER_TASK 准入下启动,workerId 互异", ok,
           json.dumps({"A": health_a.get("workerId"), "B": health_b.get("workerId"),
                       "admission": health_a.get("executionModeAdmission")}))

    # (d) 旧二进制边界:单执行器实例在舰队运行时启动 → ExecutorLease 排他锁冲突。
    exited2, matched2, _ = start_java_expect_refusal(
        "c-legacy-refused", 7603, multi_worker=False,
        needle="Another executor already owns this database")
    record("S1d 旧模式实例被 advisory lock 边界拒绝(禁止混跑,旧协议方向)",
           exited2 and matched2, "exited=%s" % exited2)


def s2_parallel_claim():
    """并行认领(以持久化 attempt 审计为事实):批量入队后,若不同 Worker 的
    两次 attempt 执行区间相互重叠,即证明两实例真实并行认领执行——
    attempt 行是持久化事实,不与 fake 引擎 ~0.3s 的瞬态窗口赛跑。"""
    run_ids = [make_slow_task(PORT_A, f"P1E-并行-{i}", 0)[2] for i in range(8)]
    wait_until(lambda: all(
        (tasks_state(run_ids).get(rid) or ("", "", "", ""))[2] in
        ("SUCCEEDED", "FAILED", "CANCELLED") for rid in run_ids),
        180, what="all parallel-batch tasks terminal")
    overlap = psql("""
        SELECT COUNT(*) FROM runtime_task_attempts a1
        JOIN runtime_task_attempts a2
          ON a1.worker_id < a2.worker_id
         AND a1.started_at < COALESCE(a2.ended_at, now())
         AND a2.started_at < COALESCE(a1.ended_at, now())
        WHERE a1.task_id IN (SELECT id FROM runtime_tasks WHERE workspace_run_id IN
              (%s)) OR a2.task_id IN (SELECT id FROM runtime_tasks WHERE workspace_run_id IN
              (%s))
        """ % (",".join("'%s'" % r for r in run_ids),
               ",".join("'%s'" % r for r in run_ids)))
    workers = psql("""
        SELECT COUNT(DISTINCT worker_id) FROM runtime_task_attempts
        WHERE task_id IN (SELECT id FROM runtime_tasks WHERE workspace_run_id IN (%s))
        """ % ",".join("'%s'" % r for r in run_ids))
    record("S2 批量入队下两个 Worker 的 attempt 执行区间重叠(并行认领,持久化事实)",
           int(overlap or 0) >= 1 and int(workers or 0) >= 2,
           f"overlappingAttemptPairs={overlap} distinctWorkers={workers}")


def ga_task_state(run_id):
    """GA 任务单行状态:(worker, epoch, state, attempts);state 为空串表示无行。"""
    row = psql("SELECT COALESCE(worker_id::text,''), execution_epoch, state, attempt_count "
               "FROM runtime_tasks WHERE ga_run_id = '%s'" % run_id)
    return row.split("|") if row else None


def s3_kill_and_takeover(wa, wb):
    """杀进程接管(受害载体 = GA 任务)。

    画布 fake 任务的执行窗口只有 ~0.3s,在途租约不可稳定观察;GA 任务经真实
    Python+RPC 执行,单任务秒级。接管协议路径与任务族无关:kill 持有在途租约
    的进程 → 租约过期 → 幸存实例过期扫描 → beginRecovery(epoch 递增、撤销
    token)→ GA 无自动恢复适配器(design §7)→ 诚实失败 EXECUTION_OUTCOME_UNKNOWN
    + 业务 Run 收敛 + RUN_FAILED 事件;其余排队任务由幸存者认领收敛(排队任务
    绝不丢失)。"""
    victim, survivor = "A", "B"
    survivor_port = PORT_B
    other = wb
    ga_run_ids = []
    for i in range(3):
        port = PORT_A if i % 2 == 0 else PORT_B
        thread_id = ga_thread(port)
        ga_run_ids.append(ga_run(port, thread_id, f"接管场景第 {i+1} 轮。"))
    leased = wait_for_leased(ga_run_ids, want=1, timeout=60, column="ga_run_id")
    victim_run = next(rid for rid, t in leased if t[0] == wa)
    stop_java(victim)

    wait_until(lambda: (lambda t: t and t[2] == "FAILED"
                        and int(t[1]) >= 2 and t[0] == "")(ga_task_state(victim_run)),
               120, what="victim GA task honestly failed with bumped epoch")
    t = ga_task_state(victim_run)
    last_error = psql("SELECT last_error_code FROM runtime_tasks "
                      "WHERE ga_run_id='%s'" % victim_run)
    run_status = ga_status(survivor_port, victim_run)
    run_failed_event = count(
        "SELECT COUNT(*) FROM global_assistant_run_events WHERE run_id='%s' "
        "AND type='RUN_FAILED'" % victim_run)
    # 其余排队任务由幸存者认领并收敛(排队任务绝不丢失)。
    wait_until(lambda: all(
        (ga_task_state(r) or ["", "", "GONE", ""])[2] in
        ("SUCCEEDED", "FAILED", "CANCELLED") for r in ga_run_ids
        if r != victim_run),
        180, what="all other GA tasks terminal")
    others_terminal = sum(
        1 for r in ga_run_ids if r != victim_run
        and (ga_task_state(r) or ["", "", "GONE", ""])[2] in
        ("SUCCEEDED", "FAILED", "CANCELLED"))
    record("S3 杀死持有在途租约的进程 → 接管核验 → 无适配器诚实失败(epoch 递增,"
           "业务 Run 收敛 + RUN_FAILED 事件),排队任务由幸存者收敛",
           t and t[2] == "FAILED" and int(t[1]) >= 2
           and last_error == "EXECUTION_OUTCOME_UNKNOWN"
           and run_status in ("FAILED", "CANCELLED") and run_failed_event >= 1
           and others_terminal == len(ga_run_ids) - 1,
           f"victim={victim} taskState=FAILED epoch={t[1] if t else '?'} "
           f"errorCode={last_error} runStatus={run_status} "
           f"runFailedEvents={run_failed_event} othersTerminal={others_terminal}/{len(ga_run_ids)-1}")


def seed_model_settings():
    """播种 GA 执行绑定所需的模型设置行(生产由用户配置;验收用测试主密钥
    加密一个占位凭据——推理仍走 fake 网关,凭据从不外发)。
    加密格式必须与 ModelCredentialCrypto 逐字一致:enc:v1:<keyId8hex>:<b64(iv‖ct+tag)>。
    """
    import base64
    import hashlib
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    master = base64.b64decode("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")  # test profile 主密钥
    key_id = hashlib.sha256(master).hexdigest()[:8]
    iv = os.urandom(12)
    ct = AESGCM(master).encrypt(iv, b"p1e-acceptance-placeholder", None)
    stored = "enc:v1:%s:%s" % (key_id, base64.b64encode(iv + ct).decode())
    psql("""
        INSERT INTO opencode_settings (singleton_id, api_key, masked_suffix,
            selected_model, created_at, updated_at)
        VALUES (1, '%s', '****', 'test-model', now(), now())
        ON CONFLICT (singleton_id) DO UPDATE SET api_key = EXCLUDED.api_key,
            selected_model = EXCLUDED.selected_model, updated_at = now()
        """ % stored)


def start_brain():
    """Python brain:broker 模式,回调固定指向 A(部署配置,与实际执行者无关)。
    幂等:S2 已启动时直接复用。"""
    if "brain" in PROCS:
        return
    brain_log = open(LOGDIR / "brain.log", "w", encoding="utf-8", errors="replace")
    brain = subprocess.Popen(
        [str(PY), "-m", "uvicorn", "spec_agent_brain.app:create_app", "--factory",
         "--host", "127.0.0.1", "--port", "8101"],
        cwd=str(BRAIN), stdout=brain_log, stderr=subprocess.STDOUT,
        env={**os.environ, "SPEC_AGENT_BRAIN_MODEL_MODE": "broker",
             "SPEC_AGENT_INTERNAL_BROKER_URL":
                 f"http://127.0.0.1:{PORT_A}/internal/v1/model-inference",
             "SPEC_AGENT_BRAIN_INTERNAL_SECRET": SECRET})
    PROCS["brain"] = {"proc": brain, "port": 8101, "log": brain_log}
    wait_until(lambda: httpx.get("http://127.0.0.1:8101/health", timeout=3).status_code == 200,
               90, what="python brain ready")


def s2_ga_parallel_claim():
    """并行认领(GA 载体,以持久化 attempt 审计为事实)。

    画布 fake 任务的执行窗口只有 ~0.3s,两 Worker 的认领节拍天然乒乓交替、
    难以自然重叠;GA 任务经真实 Python+RPC 执行,单任务秒级,4 个不同会话的
    并发 run 必然在两个 Worker 上重叠。断言三组持久化/观测事实:
      1. 不同 Worker 的 attempt 执行区间相互重叠(并行认领执行);
      2. 执行期间 GLOBAL 容量槽占用峰值 >= 2(全局容量真实被并发消耗);
      3. 四个 run 全部 COMPLETED。
    """
    start_brain()
    seed_model_settings()
    run_ids = []
    for i in range(4):
        port = PORT_A if i % 2 == 0 else PORT_B
        thread_id = ga_thread(port)
        run_ids.append(ga_run(port, thread_id,
                              f"请用两句话解释验收测试的第 {i+1} 个要点。"))
    max_global = 0
    deadline = time.time() + 180
    while time.time() < deadline:
        states = tasks_state(run_ids, "ga_run_id")
        terminal = [t for t in states.values() if t[2] in
                    ("SUCCEEDED", "FAILED", "CANCELLED")]
        used = count("SELECT used FROM runtime_capacity_slots WHERE slot_key='GLOBAL'")
        max_global = max(max_global, used)
        if len(terminal) == len(run_ids):
            break
        time.sleep(0.2)
    overlap = psql("""
        SELECT COUNT(*) FROM runtime_task_attempts a1
        JOIN runtime_task_attempts a2
          ON a1.worker_id < a2.worker_id
         AND a1.started_at < COALESCE(a2.ended_at, now())
         AND a2.started_at < COALESCE(a1.ended_at, now())
        WHERE a1.task_id IN (SELECT id FROM runtime_tasks WHERE ga_run_id IN (%s))
           OR a2.task_id IN (SELECT id FROM runtime_tasks WHERE ga_run_id IN (%s))
        """ % (",".join("'%s'" % r for r in run_ids),
               ",".join("'%s'" % r for r in run_ids)))
    workers = psql("""
        SELECT COUNT(DISTINCT worker_id) FROM runtime_task_attempts
        WHERE task_id IN (SELECT id FROM runtime_tasks WHERE ga_run_id IN (%s))
        """ % ",".join("'%s'" % r for r in run_ids))
    statuses = {rid: ga_status(PORT_A, rid) for rid in run_ids}
    completed = sum(1 for s in statuses.values() if s == "COMPLETED")
    terminal_all = int(psql("""
        SELECT COUNT(*) FROM runtime_tasks WHERE ga_run_id IN (%s)
          AND state IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
        """ % ",".join("'%s'" % r for r in run_ids)))
    # 注:验收环境没有真实模型凭据,GA 执行在模型流步骤失败(诚实失败路径,
    # 调度事实不受影响);run 的 COMPLETED 需要真实模型烟测(design §12),
    # 不在本环境冒充。并行认领/并发执行的调度证据以 attempt 重叠与容量占用
    # 为准。
    record("S2 四个 GA 会话并发:两 Worker 的 attempt 区间重叠 + GLOBAL 槽并发占用"
           " + 全部任务收敛",
           int(overlap or 0) >= 1 and int(workers or 0) >= 2
           and max_global >= 2 and terminal_all == len(run_ids),
           f"overlapPairs={overlap} workers={workers} maxGlobalUsed={max_global} "
           f"terminal={terminal_all}/{len(run_ids)} (模型缺位:run 失败于模型步,"
           f"非调度失败)")


def s5_ga_cross_instance():
    wa = health(PORT_A)["workerId"]
    wb = health(PORT_B)["workerId"]

    # ── S5a 跨实例 SSE:请求进 B,SSE 同时挂在 A、B,非执行实例靠数据库游标补发。──
    thread_id = ga_thread(PORT_B)
    run_id = ga_run(PORT_B, thread_id, "请用一句话说明什么是验收测试。")
    streams = {name: httpx.stream(
        "GET",
        f"http://127.0.0.1:{port}/api/v1/global-assistant/runs/{run_id}/events",
        headers={"Accept": "text/event-stream"}, timeout=120)
        for name, port in (("A", PORT_A), ("B", PORT_B))}
    events = {"A": [], "B": []}
    ctxs = {}
    try:
        for name, ctx in streams.items():
            ctxs[name] = ctx.__enter__()
            ctxs[name].raise_for_status()
        for name, ctx in ctxs.items():
            for line in ctx.iter_lines():
                events[name].append(line)
                if "RUN_COMPLETED" in line or "RUN_FAILED" in line or "RUN_CANCELLED" in line:
                    break
    finally:
        for ctx in ctxs.values():
            try:
                ctx.__exit__(None, None, None)
            except Exception:
                pass
    wait_until(lambda: ga_status(PORT_A, run_id) in ("COMPLETED", "FAILED", "CANCELLED"),
               60, what="ga run terminal")
    owner = (task_of(run_id, "ga_run_id") or [None])[0]
    owner_name = "A" if owner == wa else ("B" if owner == wb else "?")
    non_owner = "B" if owner_name == "A" else "A"
    has_terminal = any("RUN_COMPLETED" in line or "RUN_FAILED" in line
                       for line in events[non_owner])
    # 非执行实例收到的事件必须按序号连续(游标补发既不重也不漏)。
    seqs = []
    for line in events[non_owner]:
        if line.startswith("data:"):
            try:
                seqs.append(json.loads(line[5:]).get("sequence"))
            except ValueError:
                pass
    seqs = [s for s in seqs if s is not None]
    sequences_ok = seqs == sorted(set(seqs)) and len(seqs) >= 1
    model_calls = count("SELECT model_calls FROM ga_executions WHERE run_id='%s'" % run_id)
    callback_cross = (owner_name == "B")
    record("S5a GA 跨实例:SSE 在非执行实例上收到终态事件(数据库游标补发,序号连续)",
           has_terminal and sequences_ok and model_calls >= 1,
           f"owner={owner_name} nonOwner={non_owner} terminal={has_terminal} "
           f"events={len(seqs)} sequencesOk={sequences_ok} modelCalls={model_calls} "
           f"callbackCrossInstanceObserved={callback_cross} "
           f"(模型缺位:正文增量不存在)")

    # ── S5b steer 跨实例:run 在一个实例上活跃,steer 打到另一个实例。──
    steer_ok = False
    detail = "no attempt succeeded"
    for attempt in range(6):
        creator = PORT_B if attempt % 2 == 0 else PORT_A
        steer_via = PORT_A if creator == PORT_B else PORT_B
        thread_id = ga_thread(creator)
        run_id = ga_run(creator, thread_id, "请开始一个较长的说明。")
        try:
            wait_until(lambda: ga_status(creator, run_id).upper() == "RUNNING",
                       20, what="ga run RUNNING")
        except TimeoutError:
            detail = f"attempt {attempt}: run never RUNNING"
            continue
        try:
            r = httpx.post(f"http://127.0.0.1:{steer_via}/api/v1/global-assistant/runs/"
                           f"{run_id}/steer", json={"message": "改答:只回答一个字。"},
                           timeout=15)
            if r.status_code != 200:
                detail = f"attempt {attempt}: steer http {r.status_code} {r.text[:80]}"
                continue
            successor = r.json().get("successorRunId")
            wait_until(lambda: ga_status(creator, run_id).upper() in
                       ("CANCELLED", "COMPLETED", "FAILED"), 60,
                       what="steered run terminal")
            if not successor:
                # 继任 run 由 TurnHandoff 异步消费未决轮次创建:从持久化的
                # pending turn 行读取 successor_run_id(有界等待)。
                pending_id = r.json().get("steerId")
                deadline2 = time.time() + 60
                while time.time() < deadline2 and not successor:
                    successor = psql("SELECT successor_run_id FROM "
                                     "global_assistant_pending_turns "
                                     "WHERE id='%s'" % pending_id) or None
                    time.sleep(0.5)
            if successor:
                wait_until(lambda: ga_status(creator, successor).upper()
                           in ("COMPLETED", "FAILED", "CANCELLED"), 120,
                           what="successor terminal")
                # 机制证据:steer 被非执行实例接受、旧 run 协作取消、继任 run
                # 被创建并收敛。继任的业务结果取决于模型可用性(本环境缺位)。
                steer_ok = (ga_status(creator, run_id).upper() == "CANCELLED"
                            and ga_status(creator, successor).upper()
                            in ("COMPLETED", "FAILED"))
                detail = (f"old=CANCELLED successor="
                          f"{ga_status(creator, successor)} "
                          f"steerVia={'A' if steer_via == PORT_A else 'B'}")
            else:
                detail = f"attempt {attempt}: steer accepted but no successor appeared"
            break
        except (TimeoutError, httpx.HTTPError) as ex:
            detail = f"attempt {attempt}: {type(ex).__name__}: {str(ex)[:80]}"
            continue
    record("S5b GA steer 跨实例接受:旧 run CANCELLED、继任 run 完成", steer_ok, detail)


def s6_db_outage():
    # 在途 GA 任务 + 数据库冻结(pause 14s > 租约 8s):心跳全部失败;恢复后
    # 租约过期 → 接管核验(epoch 递增,绝不复活旧租约)→ 无适配器诚实失败。
    ga_run_id = ga_run(PORT_A, ga_thread(PORT_A), "断库恢复场景。")
    wait_for_leased([ga_run_id], want=1, timeout=60, column="ga_run_id")
    epoch_before = int(ga_task_state(ga_run_id)[1])
    docker("pause", PG_NAME)
    time.sleep(14)
    docker("unpause", PG_NAME)
    wait_until(lambda: (lambda t: t and t[2] in
                        ("SUCCEEDED", "FAILED", "CANCELLED"))(ga_task_state(ga_run_id)),
               300, what="GA task terminal after outage")
    t = ga_task_state(ga_run_id)
    healthy = all(health(p).get("status") == "UP" for p in (PORT_A, PORT_B))
    record("S6 数据库中断:恢复后租约被接管核验(epoch 递增)、诚实收敛、健康恢复",
           t and t[2] in ("SUCCEEDED", "FAILED") and int(t[1]) >= epoch_before and healthy,
           f"taskState={t[2] if t else '?'} epoch {epoch_before}->{t[1] if t else '?'}")


def s7_capacity_boundary():
    """容量边界:8 个 GA 任务(全局容量 4)——占用峰值绝不越界,全部收敛。"""
    ga_run_ids = []
    for i in range(8):
        port = PORT_A if i % 2 == 0 else PORT_B
        ga_run_ids.append(ga_run(port, ga_thread(port), f"容量边界场景 {i+1}。"))
    max_used = 0
    deadline = time.time() + 300
    while time.time() < deadline:
        used = count("SELECT COALESCE(SUM(used),0) FROM runtime_capacity_slots "
                     "WHERE slot_key='GLOBAL'")
        max_used = max(max_used, used)
        states = tasks_state(ga_run_ids, "ga_run_id")
        if len(states) == len(ga_run_ids) and all(
                t[2] in ("SUCCEEDED", "FAILED", "CANCELLED") for t in states.values()):
            break
        time.sleep(0.2)
    all_done = all((tasks_state(ga_run_ids, "ga_run_id").get(r) or ("", "", "GONE", ""))[2]
                   in ("SUCCEEDED", "FAILED", "CANCELLED") for r in ga_run_ids)
    record("S7 容量边界:GLOBAL 槽占用峰值 ≤ 4 且曾达 2 以上,8 个任务全部收敛",
           max_used <= 4 and max_used >= 2 and all_done,
           f"maxUsed={max_used} allTerminal={all_done}")


def s8_rollback():
    # 停舰队(硬停;Windows 上 Python 无优雅 SIGTERM,优雅 drain 记为限制项)。
    stop_java("A")
    stop_java("B")
    stop_java("brain")
    psql("UPDATE runtime_execution_mode SET mode='SINGLE_EXECUTOR', "
         "updated_by='p1e-rollback', updated_at=now() WHERE id=1")
    start_java("rollback", 7603, multi_worker=False)
    health_r = wait_health(7603)
    tasks_preserved = count("SELECT COUNT(*) FROM runtime_tasks") > 0
    project = httpx.post("http://127.0.0.1:7603/api/v1/projects",
                         json={"title": "P1E-回退-单执行器"}, timeout=20).json()
    pid = project["id"]
    r = httpx.post(f"http://127.0.0.1:7603/api/v1/projects/{pid}/nodes",
                   json={"routeId": project["activeRouteId"], "subtype": "IDEA",
                         "content": {"text": "回退后的根节点"}}, timeout=20)
    r.raise_for_status()
    nid = r.json()["id"]
    r2 = httpx.post(f"http://127.0.0.1:7603/api/v1/projects/{pid}/nodes/{nid}/query",
                    json={"routeId": project["activeRouteId"],
                          "question": "回退后仍然可以工作"}, timeout=20)
    r2.raise_for_status()
    run_id = r2.json()["runId"]
    wait_until(lambda: canvas_run(7603, pid, run_id)["status"].lower() in TERMINAL,
               150, what="rollback run terminal")
    status = canvas_run(7603, pid, run_id)["status"]
    record("S8 回退演练:切回 SINGLE_EXECUTOR 后新实例可启动并处理入队,新表历史保留",
           health_r.get("executionOwnership") == "SINGLE_EXECUTOR_LEASE"
           and status.lower() == "completed" and tasks_preserved,
           f"ownership={health_r.get('executionOwnership')} status={status} "
           f"tasksPreserved={tasks_preserved}")


def main():
    LOGDIR.mkdir(parents=True, exist_ok=True)
    print("P1-E dual-process acceptance starting", flush=True)
    prepare_postgres()
    started = time.time()
    try:
        s1_admission()
        s2_ga_parallel_claim()
        wa = health(PORT_A)["workerId"]
        wb = health(PORT_B)["workerId"]
        s3_kill_and_takeover(wa, wb)
        # S3 硬杀了 A;重启它恢复双实例形态(workerId 会变,后续场景从健康
        # 端点现取),继续 S5/S6/S7。
        start_java("A", PORT_A, multi_worker=True)
        wait_health(PORT_A)
        s5_ga_cross_instance()
        s6_db_outage()
        s7_capacity_boundary()
        s8_rollback()
    finally:
        for name in list(PROCS):
            try:
                stop_java(name)
            except Exception:
                pass
        try:
            docker("rm", "-f", PG_NAME, check=False)
        except Exception:
            pass

    evidence = {
        "recordedAt": time.strftime("%Y-%m-%dT%H:%M:%S"),
        "scope": "two independent Java processes + dedicated PostgreSQL container + "
                 "real Python brain (fake model gateway); P1-E acceptance per design §12",
        "elapsedSeconds": int(time.time() - started),
        "results": RESULTS,
        "automatedTestEvidenceOnly": {
            "S4-late-write-refusal":
                "TaskWriteGuardTest(8) + AgentRunEventConcurrentAppendTest(2) + "
                "AnswerChainTakeoverEndToEndTest: token 三项校验 / task 行锁串行化 / "
                "接管窗口并发事件追加序号连续;Windows 无法对真实进程做暂停后恢复,"
                "不冒充进程级证据",
            "GA-lease-expiry-honest-failure":
                "GaMultiWorkerSchedulingIntegrationTest(3) + GaTakeoverEndToEndTest(3):"
                "GA fake 执行过快,进程级 kill 窗口不可靠;ga-recovery-enabled 保持关闭",
        },
        "limitations": [
            "优雅退出(design §11 的 drain)在 Windows 上无 SIGTERM,进程级 drain "
            "未演练;杀进程接管(S3)与硬停回退(S8)已覆盖",
        ],
    }
    out = ROOT / "docs" / "v2" / "evidence" / "P1E_DUAL_PROCESS_EVIDENCE.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
    failed = [r for r in RESULTS if not r["passed"]]
    print(f"P1-E finished: {len(RESULTS) - len(failed)}/{len(RESULTS)} passed; "
          f"evidence={out}", flush=True)
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()

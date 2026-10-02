"""Static precheck only: never runs queries, tunes config, or approves human labels."""
import hashlib
import json
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / 'contracts/retrieval/evaluation'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def check():
    candidate = json.loads((DATA / 'acceptance-candidate.v1.json').read_text(encoding='utf-8'))
    development = json.loads((DATA / 'chinese-queries.v1.json').read_text(encoding='utf-8'))
    freeze = json.loads((DATA / 'acceptance-freeze.v1.json').read_text(encoding='utf-8'))
    assert development['datasetRole'] == 'DEVELOPMENT_CALIBRATION'
    assert not development['calibrationHistory']['independentAcceptanceEligible']
    assert candidate['datasetRole'] == 'INDEPENDENT_ACCEPTANCE_CANDIDATE'
    assert candidate['humanReviewStatus'] == 'PENDING'
    assert candidate['executionStatus'] == 'NOT_RUN' and candidate['tuningUse'] == 'NEVER_USED'
    assert len(candidate['queries']) >= 50
    assert freeze['datasetSha256'] == sha(DATA / freeze['datasetFile'])
    assert freeze['requirementsLockSha256'] == sha(ROOT / 'agent-brain/requirements.lock')
    assert (freeze['maxVectorDistance'], freeze['rrfK'], freeze['maxItems'], freeze['maxChars']) == (0.50, 60, 8, 8000)
    assert freeze['modelTag'] == 'qwen3-embedding:0.6b'
    assert freeze['profileId'] == '26810df4b6b4e7d0d2977bcc57175fcf7e944ebe99f447b5c4e690cb08b8c047'
    for path, value in freeze['codeHashes'].items():
        assert sha(ROOT / path) == value, f'Frozen implementation changed: {path}'
    assert len({s['alias'] for s in candidate['sources']}) == len(candidate['sources'])
    assert len({q['id'] for q in candidate['queries']}) == len(candidate['queries'])
    normalize = lambda value: ''.join(value.split()).casefold()
    old_queries = {normalize(q['query']) for q in development['queries']}
    new_queries = [normalize(q['query']) for q in candidate['queries']]
    assert len(set(new_queries)) == len(new_queries) and not old_queries.intersection(new_queries)
    assert not {s['text'] for s in development['sources']}.intersection(s['text'] for s in candidate['sources'])
    aliases = {s['alias']: s for s in candidate['sources']}
    for query in candidate['queries']:
        assert query['labelReview'] == 'PENDING_HUMAN_REVIEW'
        assert set(query['scopes']) <= {'PROJECT', 'RESOURCE'} and query['scopes']
        assert set(query['expectedAliases']) <= aliases.keys()
        assert set(query['forbiddenAliases']) <= aliases.keys()
        assert not set(query['expectedAliases']).intersection(query['forbiddenAliases'])
        assert all(aliases[a]['kind'] not in {'STALE_NODE', 'PRIVATE_NODE'} for a in query['expectedAliases'])
        if query['category'] == 'MULTI_SOURCE':
            assert len(query['expectedAliases']) >= 2
        if any(aliases[a]['kind'] == 'RESOURCE' for a in query['expectedAliases']):
            assert 'RESOURCE' in query['scopes']
    report = {
        'recordedAt': datetime.now(timezone.utc).isoformat(), 'status': 'STATIC_PRECHECK_PASS',
        'queryCount': len(candidate['queries']), 'sourceCount': len(aliases),
        'categories': dict(Counter(q['category'] for q in candidate['queries'])),
        'exactQueryOverlapWithDevelopment': 0, 'exactSourceOverlapWithDevelopment': 0,
        'candidateSha256': freeze['datasetSha256'], 'freezeSha256': sha(DATA / 'acceptance-freeze.v1.json'),
        'executedQueries': 0, 'labelsApproved': False, 'humanReviewStatus': 'PENDING',
        'semanticPrecheck': [
            'Near-no-answer asks unsupported quantities/policies; related context is not sufficient to answer.',
            'Multi-source labels are all-of, not any-hit; reviewers must check each source is necessary.',
            'Repository-grounded expressions are AI paraphrases, not captured customer/user transcripts.',
            'Shared Question/re-answer, Focus/route and restart/UNKNOWN topics overlap semantically; humans must check alternative relevant sources.',
            'Eight near-no-answer labels and eight multi-source labels require particular human attention.',
            'No exact overlap is not proof of statistical independence; same project domain and AI author remain limitations.'
        ],
        'rootLimit': 'AI static/semantic precheck is not human annotation or retrieval-quality acceptance.'
    }
    (ROOT / 'docs/v2/evidence/SHARED_RAG_ACCEPTANCE_PRECHECK.json').write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    lines = ['# 独立候选验收集人工审阅', '',
             '状态：PENDING；68 条 AI 编写候选，尚未运行、未参与调参。52 查询属于校准开发集。', '',
             '来源基于真实项目仓库文档转述，标识符是合成夹具；不是生产数据或用户对话。',
             '请检查来源事实、问题意图、可替代来源和拒答边界；不得根据以后运行的命中结果倒推标签。',
             'MULTI_SOURCE 是 all-of：缺少任一必需来源均不算完整覆盖。NEAR_NO_ANSWER 主题接近但无足够答案，需特别审阅。', '',
             '先实际人工审阅并记录身份/时间，冻结修正后的数据 hash 和首轮验收门槛，再运行。',
             '本工具不修改 HUMAN_REVIEWED 或 APPROVED。', '', '## 来源', '',
             '| Alias | 类型 | 来源文字 | 文档依据 |', '|---|---|---|---|']
    def cell(value):
        return str(value).replace('|', '\\|').replace('\r', '').replace('\n', '<br>')
    for source in candidate['sources']:
        lines.append(f"| {source['alias']} | {source['kind']} | {cell(source['text'])} | {cell(source.get('provenance', {}).get('repositoryPath', '隔离夹具'))} |")
    lines += ['', '## 查询', '', '| ID | 类别/范围 | 查询 | 初始预期 Alias | 审阅提示 | 人工结论 |', '|---|---|---|---|---|---|']
    for query in candidate['queries']:
        lines.append(f"| {query['id']} | {query['category']} / {','.join(query['scopes'])} | {cell(query['query'])} | {','.join(query['expectedAliases']) or '空（不应返回）'} | {cell(query['reviewNotes'])} | 待审 |")
    (DATA / 'ACCEPTANCE_HUMAN_REVIEW.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    print('Static precheck passed; 0 queries executed; human approval remains pending.')


if __name__ == '__main__':
    check()

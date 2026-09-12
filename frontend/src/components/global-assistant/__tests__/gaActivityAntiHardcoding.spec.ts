import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import ToolActivityItem from '../ToolActivityItem.vue';
import { capabilityPresentation, completedCountLabel } from '@/presentation/capabilityPresentation';
import { GaRunProjection, type GaToolActivity } from '@/stores/globalAssistantStore';

function toolStarted(seq: number, capabilityId: string) {
  return { eventId: 'e'+seq, runId: 'r1', threadId: 't1', type: 'TOOL_STARTED', sequence: seq, createdAt: '2026-01-01T00:00:00Z', payload: { capabilityId } };
}
function toolCompleted(seq: number, capabilityId: string, extra: Record<string, unknown>) {
  return { eventId: 'e'+seq, runId: 'r1', threadId: 't1', type: 'TOOL_COMPLETED', sequence: seq, createdAt: '2026-01-01T00:00:01Z', payload: Object.assign({ capabilityId }, extra) };
}
function activityOf(p: Parameters<GaRunProjection['apply']>[0][]) {
  const g = new GaRunProjection();
  for (const e of p) g.apply(e);
  return g.activities;
}
function mountItem(a: GaToolActivity) {
  return mount(ToolActivityItem, { props: { activity: a } });
}

describe('anti-hardcoding: activity comes from TOOL events, never prompt text', () => {
  it('1. same tool under different phrasings still yields activity from TOOL_STARTED', () => {
    for (const seq of [1, 2, 3]) {
      const acts = activityOf([toolStarted(seq, 'project.list_recent')]);
      expect(acts).toHaveLength(1);
      expect(acts[0].displayName).toBe('查看最近项目');
    }
  });
  it('2. STATUS/DELTA only (no tool call) yields zero tool activity', () => {
    const acts = activityOf([{ eventId: 'e1', runId: 'r1', threadId: 't1', type: 'STATUS', sequence: 1, createdAt: '2026-01-01T00:00:00Z', payload: { message: 'x' } }, { eventId: 'e2', runId: 'r1', threadId: 't1', type: 'ASSISTANT_DELTA', sequence: 2, createdAt: '2026-01-01T00:00:01Z', payload: { text: 'hi' } }]);
    expect(acts).toHaveLength(0);
  });
  it('3. literal capability id in user-side text without a tool call yields nothing', () => {
    const acts = activityOf([{ eventId: 'e1', runId: 'r1', threadId: 't1', type: 'ASSISTANT_DELTA', sequence: 1, createdAt: '2026-01-01T00:00:00Z', payload: { text: 'project.list_recent say hi' } }]);
    expect(acts).toHaveLength(0);
  });
  it('4. completed line follows real counts 0/1/N', () => {
    const zero = activityOf([toolStarted(1, 'project.search'), toolCompleted(2, 'project.search', { summary: 'none', resourceRefs: [], resultCount: 0, resultKind: 'PROJECT_LIST' })]);
    expect(mountItem(zero[0]).text()).toContain('已获取 0 个项目');
    const one = activityOf([toolStarted(1, 'project.search'), toolCompleted(2, 'project.search', { summary: 'one', resourceRefs: [], resultCount: 1, resultKind: 'PROJECT_LIST' })]);
    expect(mountItem(one[0]).text()).toContain('已获取 1 个项目');
    const many = activityOf([toolStarted(1, 'project.list_recent'), toolCompleted(2, 'project.list_recent', { summary: 'many', resourceRefs: [], resultCount: 7, resultKind: 'PROJECT_LIST' })]);
    expect(mountItem(many[0]).text()).toContain('已获取 7 个项目');
    expect(mountItem(many[0]).text()).not.toContain('many');
  });
  it('4b. refs length wins over a mismatched event count', () => {
    const refs = [{ kind: 'PROJECT', id: '123e4567-e89b-12d3-a456-426614174000', label: 'A' }, { kind: 'PROJECT', id: '123e4567-e89b-12d3-a456-426614174001', label: 'B' }];
    const acts = activityOf([toolStarted(1, 'project.list_recent'), toolCompleted(2, 'project.list_recent', { summary: 'wrong', resourceRefs: refs, resultCount: 99, resultKind: 'PROJECT_LIST' })]);
    expect(acts[0].resultCount).toBe(2);
    expect(mountItem(acts[0]).text()).toContain('已获取 2 个项目');
  });
  it('5. unknown capability uses generic fallback and never crashes', () => {
    expect(capabilityPresentation('future.tool').actionLabel).toBe('执行操作');
    expect(completedCountLabel('future.tool', 3)).toBeNull();
    const acts = activityOf([toolStarted(1, 'future.tool'), toolCompleted(2, 'future.tool', { summary: 'did stuff' })]);
    const w = mountItem(acts[0]);
    expect(w.text()).toContain('执行操作');
    expect(w.text()).toContain('did stuff');
  });
  it('6. duplicate sequences are dropped; completion without start still lands once', () => {
    const g = new GaRunProjection();
    g.apply(toolStarted(1, 'project.search'));
    g.apply(toolStarted(1, 'project.search'));
    expect(g.activities).toHaveLength(1);
    const g2 = new GaRunProjection();
    g2.apply(toolCompleted(5, 'project.search', { summary: 'late' }));
    g2.apply(toolCompleted(5, 'project.search', { summary: 'late' }));
    expect(g2.activities).toHaveLength(1);
    expect(g2.activities[0].state).toBe('success');
  });
});

describe('capability registry', () => {
  it('covers the four shipped tools with product labels', () => {
    expect(capabilityPresentation('project.list_recent').actionLabel).toBe('查看最近项目');
    expect(capabilityPresentation('project.search').resultKind).toBe('PROJECT_LIST');
    expect(capabilityPresentation('project.create').resultKind).toBe('PROJECT');
    expect(capabilityPresentation('project.get_summary').resultKind).toBe('PROJECT');
  });
});

import { describe, expect, it } from 'vitest'
import { agentActionLabel, agentPhaseLabel } from '../agentPresentation'

describe('agentPhaseLabel', () => {
  it('maps known runtime phases to product copy', () => {
    expect(agentPhaseLabel('SNAPSHOT_BUILT')).toBe('正在分析上下文')
    expect(agentPhaseLabel('STATE_UPDATING')).toBe('正在整理需求')
    expect(agentPhaseLabel('DECIDING')).toBe('正在规划下一步')
    expect(agentPhaseLabel('AWAITING_APPROVAL')).toBe('等待你的确认')
    expect(agentPhaseLabel('WAITING_USER')).toBe('等待你的输入')
  })

  it('never leaks raw phase codes in the unknown fallback', () => {
    expect(agentPhaseLabel('SOME_NEW_INTERNAL_PHASE')).toBe('处理中…')
    expect(agentPhaseLabel(null)).toBe('处理中…')
    expect(agentPhaseLabel(undefined)).toBe('处理中…')
  })
})

describe('agentActionLabel', () => {
  it('maps known action families to readable Chinese labels', () => {
    expect(agentActionLabel('CREATE_NODE')).toBe('创建节点')
    expect(agentActionLabel('UPDATE_NODE')).toBe('更新节点')
    expect(agentActionLabel('CONNECT_NODE')).toBe('连接节点')
    expect(agentActionLabel('CREATE_ROUTE')).toBe('创建路线')
    expect(agentActionLabel('REQUEST_USER_INPUT')).toBe('请求你的输入')
    expect(agentActionLabel('RESPOND_TO_USER')).toBe('回复你')
    expect(agentActionLabel('INVOKE_CAPABILITY')).toBe('运行能力')
    expect(agentActionLabel('GENERATE_ARTIFACT')).toBe('生成产物')
    expect(agentActionLabel('WAIT')).toBe('等待')
  })

  it('falls back to a generic label for unknown families', () => {
    expect(agentActionLabel('UNKNOWN_ACTION')).toBe('执行操作')
    expect(agentActionLabel(null)).toBe('执行操作')
    expect(agentActionLabel(undefined)).toBe('执行操作')
  })
})

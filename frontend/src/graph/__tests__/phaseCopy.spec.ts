import { describe, it, expect } from 'vitest'
import { phaseToCopy, isTerminalPhase } from '../phaseCopy'

describe('phaseToCopy', () => {
  it('maps known phases to Chinese copy', () => {
    expect(phaseToCopy('CREATED')).toBe('正在准备…')
    expect(phaseToCopy('SNAPSHOT_BUILT')).toBe('正在分析上下文')
    expect(phaseToCopy('STATE_UPDATING')).toBe('正在整理需求')
    expect(phaseToCopy('DECIDING')).toBe('正在规划下一步')
    expect(phaseToCopy('AWAITING_APPROVAL')).toBe('等待你的确认')
    expect(phaseToCopy('WAITING_USER')).toBe('等待你的输入')
    expect(phaseToCopy('EXECUTING')).toBe('正在执行')
    expect(phaseToCopy('COMPLETED')).toBe('已完成')
    expect(phaseToCopy('FAILED')).toBe('需要处理')
  })

  it('returns generic copy for null/undefined', () => {
    expect(phaseToCopy(null)).toBe('处理中…')
    expect(phaseToCopy(undefined)).toBe('处理中…')
  })

  it('never leaks raw phase names for unknown phases', () => {
    expect(phaseToCopy('CUSTOM_PHASE')).toBe('处理中…')
    expect(phaseToCopy('CUSTOM_PHASE')).not.toContain('CUSTOM_PHASE')
  })
})

describe('isTerminalPhase', () => {
  it('recognizes terminal phases', () => {
    expect(isTerminalPhase('COMPLETED')).toBe(true)
    expect(isTerminalPhase('FAILED')).toBe(true)
    expect(isTerminalPhase('STALE')).toBe(true)
  })

  it('rejects non-terminal phases', () => {
    expect(isTerminalPhase('CREATED')).toBe(false)
    expect(isTerminalPhase('DECIDING')).toBe(false)
    expect(isTerminalPhase(null)).toBe(false)
  })
})

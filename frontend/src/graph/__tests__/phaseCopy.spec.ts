import { describe, it, expect } from 'vitest'
import { phaseToCopy, isTerminalPhase } from '../phaseCopy'

describe('phaseToCopy', () => {
  it('maps known phases to Chinese copy', () => {
    expect(phaseToCopy('CREATED')).toBe('正在准备…')
    expect(phaseToCopy('STATE_UPDATING')).toBe('正在更新需求状态…')
    expect(phaseToCopy('DECIDING')).toBe('正在规划下一步…')
    expect(phaseToCopy('EXECUTING')).toBe('正在生成…')
    expect(phaseToCopy('COMPLETED')).toBe('已完成')
    expect(phaseToCopy('FAILED')).toBe('执行失败')
  })

  it('returns generic copy for null/undefined', () => {
    expect(phaseToCopy(null)).toBe('处理中…')
    expect(phaseToCopy(undefined)).toBe('处理中…')
  })

  it('includes phase name for unknown phases', () => {
    expect(phaseToCopy('CUSTOM_PHASE')).toContain('CUSTOM_PHASE')
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

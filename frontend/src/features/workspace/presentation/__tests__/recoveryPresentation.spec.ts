import { describe, expect, it } from 'vitest'
import { recoveryNoticeFromState } from '@/features/workspace/presentation/recoveryPresentation'

describe('recoveryNoticeFromState', () => {
  it('returns null when there is nothing to recover', () => {
    expect(recoveryNoticeFromState({})).toBeNull()
  })

  it('prioritizes unknown outcome over every other flag', () => {
    const model = recoveryNoticeFromState({
      answerOutcomeUnknown: true,
      repairableAnswerId: 'a1',
      resubmitAnswerPayload: { selectedOptionId: 'o1', freeText: null },
      manualRetryState: 'ready',
    })
    expect(model).toMatchObject({
      kind: 'unknown',
      title: '提交结果暂时无法确认',
      message: '为了避免重复操作，请先同步最新状态',
      action: 'reconcile-answer',
      actionLabel: '同步状态',
    })
  })

  it('offers resume when the answer is saved but continuation failed', () => {
    const model = recoveryNoticeFromState({
      repairableAnswerId: 'a1',
      resubmitAnswerPayload: { selectedOptionId: 'o1', freeText: null },
      manualRetryState: 'ready',
    })
    expect(model).toMatchObject({
      kind: 'saved',
      title: '回答已经保存',
      message: '后续生成没有完成，不需要重新填写回答',
      action: 'resume-answer',
      actionLabel: '继续生成',
    })
  })

  it('identifies the owning route when an inherited historical answer blocks generation', () => {
    const model = recoveryNoticeFromState({
      historicalAnswerRecovery: {
        routeLabel: '源路线',
        question: '会议最长多久？',
      },
    })
    expect(model).toMatchObject({
      kind: 'saved',
      title: '历史回答需要恢复',
      message: '回答“会议最长多久？”已保存在路线“源路线”，请先恢复该回答，再生成规格',
      action: 'resume-answer',
      actionLabel: '恢复该回答',
    })
  })

  it('offers safe resubmit only when no answer was saved', () => {
    const model = recoveryNoticeFromState({
      resubmitAnswerPayload: { selectedOptionId: 'o1', freeText: null },
    })
    expect(model).toMatchObject({
      kind: 'resubmit',
      title: '回答尚未保存',
      message: '已确认可以安全地再次提交',
      action: 'resubmit-answer',
      actionLabel: '再次提交',
    })
  })

  it('offers retry for an explicit ready manual retry', () => {
    const model = recoveryNoticeFromState({ manualRetryState: 'ready' })
    expect(model?.kind).toBe('retryable')
    expect(model?.action).toBe('retry-model-operation')
  })

  it('reconciles first for ambiguous manual retry state', () => {
    const model = recoveryNoticeFromState({ manualRetryState: 'ambiguous' })
    expect(model?.kind).toBe('stale')
    expect(model?.action).toBe('refresh-workspace')
  })

  it('explains policy errors without a meaningless retry CTA', () => {
    const model = recoveryNoticeFromState({ errorCode: 'POLICY_DENIED' })
    expect(model?.kind).toBe('blocked')
    expect(model?.action).toBeNull()
  })
})

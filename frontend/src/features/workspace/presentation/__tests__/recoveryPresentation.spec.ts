// 文件名:recoveryPresentation.spec.ts
// 用途:恢复提示模型的单元测试:验证无恢复场景返回 null 以及各恢复场景(unknown/historical/resubmit/stale/blocked)的优先级与文案;并锁定旧的全局入口(全局"继续生成"/"重新请求")不再产生任何提示。
import { describe, expect, it } from 'vitest'
import { recoveryNoticeFromState } from '@/features/workspace/presentation/recoveryPresentation'

describe('recoveryNoticeFromState', () => {
  it('returns null when there is nothing to recover', () => {
    expect(recoveryNoticeFromState({})).toBeNull()
  })

  it('prioritizes unknown outcome over every other flag', () => {
    const model = recoveryNoticeFromState({
      answerOutcomeUnknown: true,
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

  it('no longer offers the global "继续生成" entry (deleted 2026-09-27)', () => {
    // Answer 已保存但处理未完成的恢复统一走任务级失败恢复(服务端未解决
    // 失败清单 + 节点恢复栏);全局入口必须返回 null,不得换名字回归。
    expect(recoveryNoticeFromState({
      resubmitAnswerPayload: null,
      manualRetryState: null,
    })).toBeNull()
  })

  it('no longer offers the global "重新请求" entry (deleted 2026-09-27)', () => {
    // manualRetryState 'ready' 的全局重试按钮已删除:安全重试从对应失败
    // 位置的任务级入口或正常业务动作发起。
    expect(recoveryNoticeFromState({
      manualRetryState: 'ready',
    })).toBeNull()
  })

  it('still offers reconcile-only guidance for the manual retry reconcile states', () => {
    const model = recoveryNoticeFromState({ manualRetryState: 'needs_reconcile' })
    expect(model).toMatchObject({
      kind: 'stale',
      action: 'refresh-workspace',
      actionLabel: '同步状态',
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

  it('never offers a global retry for a ready manual retry (entry deleted 2026-09-27)', () => {
    // 全局"重新请求"入口已删除;安全重试从对应失败位置的任务级入口发起。
    expect(recoveryNoticeFromState({ manualRetryState: 'ready' })).toBeNull()
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

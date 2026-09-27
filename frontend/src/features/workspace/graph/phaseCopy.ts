// 文件名:phaseCopy.ts
// 用途:AgentRun 运行阶段到用户可见中文进度文案的映射薄封装,保持既有导出 API 并委托给 agentPresentation 的稳定映射。
/*
 * 把真实的 AgentRun 阶段映射为用户可见的中文进度文案。
 * 这些都是可验证的运行时阶段,绝不伪造思维链。
 *
 * 文案本体位于 presentation/agentPresentation.ts;本模块为既有调用方
 * 保留导出 API,并委托给那份稳定映射。
 */
import { agentPhaseLabel } from '@/features/workspace/presentation/agentPresentation'

/*
 * 返回给定 run 阶段的用户可见进度文案。
 * 未知阶段回退到通用提示(绝不泄漏原始阶段名)。
 */
export function phaseToCopy(phase: string | null | undefined): string {
  return agentPhaseLabel(phase)
}

/*
 * 返回 run 是否已到达终态。
 */
export function isTerminalPhase(phase: string | null | undefined): boolean {
  return phase === 'COMPLETED' || phase === 'FAILED' || phase === 'STALE'
}

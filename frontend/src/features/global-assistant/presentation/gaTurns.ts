import type { GaMessage } from '../api/globalAssistant'
import type { GaToolActivity } from '../state/globalAssistantStore'

export interface GaTurn {
  key: string
  requests: GaMessage[]
  answers: GaMessage[]
  activities: GaToolActivity[]
}

/** Run identity comes from persisted host messages/events, never from text or tool name. */
export function groupGaTurns(messages: GaMessage[], activities: GaToolActivity[]): GaTurn[] {
  const turns: GaTurn[] = []
  const byRun = new Map<string, GaTurn>()
  for (const message of messages) {
    const key = message.runId ?? 'message:' + message.id
    let turn = byRun.get(key)
    if (!turn) {
      turn = { key, requests: [], answers: [], activities: [] }
      byRun.set(key, turn)
      turns.push(turn)
    }
    ;(message.role === 'USER' ? turn.requests : turn.answers).push(message)
  }
  for (const activity of activities) {
    // Old fixtures without identity remain visible separately, never assigned to a guessed request.
    const key = activity.runId ?? 'unassociated-activities'
    let turn = byRun.get(key)
    if (!turn) {
      turn = { key, requests: [], answers: [], activities: [] }
      byRun.set(key, turn)
      turns.push(turn)
    }
    if (!turn.activities.some(item => item.key === activity.key)) turn.activities.push(activity)
  }
  for (const turn of turns) turn.activities.sort((a, b) => (a.sequence ?? 0) - (b.sequence ?? 0))
  return turns
}

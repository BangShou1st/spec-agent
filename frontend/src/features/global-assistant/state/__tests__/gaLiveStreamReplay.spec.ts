// Explicit evidence gate: actual configured model/product events, replayed through product projection.
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { GaRunProjection } from '@/features/global-assistant/state/globalAssistantStore'
import type { GaEventEnvelope } from '@/features/global-assistant/api/globalAssistant'

describe('configured model product stream evidence', () => {
  it.runIf(process.env.SPEC_AGENT_GA_LIVE_REPLAY === 'true')('reconciles real tool activities, deltas and one immutable terminal', () => {
    const evidence = JSON.parse(readFileSync(resolve(process.cwd(), '../docs/v2/evidence/GLOBAL_ASSISTANT_PRODUCT_DISPATCH_INTEGRATION.json'), 'utf8'))
    expect(evidence.status).toBe('PASS')
    expect(evidence.providerTokenStreaming).toBe('PASS_PRODUCT_DELTA_BEFORE_MODEL_COMPLETION')
    const projection = new GaRunProjection()
    const events = evidence.productEvents as GaEventEnvelope[]
    let deltas = 0
    for (const event of events) {
      projection.apply(event)
      if (event.type === 'ANSWER_DELTA') deltas += 1
      if (event.type === 'ASSISTANT_COMPLETED') expect(projection.streamingText).toBe(evidence.finalText)
    }
    expect(deltas).toBeGreaterThan(1)
    expect(projection.activities.some(a => a.capabilityId === 'project.list_recent' && a.state === 'success')).toBe(true)
    expect(events.filter(e => ['RUN_COMPLETED', 'RUN_FAILED', 'RUN_CANCELLED'].includes(e.type))).toHaveLength(1)
    expect(projection.terminal?.type).toBe('RUN_COMPLETED')
    const answer = projection.streamingText
    expect(projection.apply({ ...events.at(-1)!, sequence: events.at(-1)!.sequence + 1,
      type: 'ANSWER_DELTA', payload: { generation: projection.streamGeneration, text: 'late' } })).toBe(false)
    expect(projection.streamingText).toBe(answer)
  })
})

import { describe, expect, it } from 'vitest'
import router from '@/router'

describe('settings routes', () => {
  it('redirects settings root to models and exposes skills and connections shells', () => {
    const routes = router.getRoutes()
    const byName = new Map(routes.map((r) => [r.name, r]))
    expect(byName.has('settings-models')).toBe(true)
    expect(byName.has('settings-skills')).toBe(true)
    expect(byName.has('settings-skill-detail')).toBe(true)
    expect(byName.has('settings-connections')).toBe(true)
    expect(byName.has('settings-connection-detail')).toBe(true)
    const paths = routes.map((r) => r.path)
    expect(paths).toContain('/settings/models')
    expect(paths).toContain('/settings/skills')
    expect(paths).toContain('/settings/skills/:skillId')
    expect(paths).toContain('/settings/connections')
    expect(paths).toContain('/settings/connections/:connectionId')
  })

  it('keeps workspace routes untouched', () => {
    const routes = router.getRoutes()
    const paths = routes.map((r) => r.path)
    expect(paths).toContain('/projects')
    expect(paths).toContain('/projects/:projectId')
  })
})

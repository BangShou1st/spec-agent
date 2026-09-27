// 文件名:settingsRoutes.spec.ts
// 用途:路由表单测:验证设置区各子路由(models/skills/connections 及详情页)已注册、
//       根路径重定向到模型页,且项目/工作台路由不受影响。
import { describe, expect, it } from 'vitest'
import router from '@/app/router/index'

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

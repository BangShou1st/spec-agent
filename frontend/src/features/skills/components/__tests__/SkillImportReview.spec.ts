import { accessSession } from '@/features/access/accessSession'
// 文件名:SkillImportReview.spec.ts
// 用途:Skill 导入评审弹窗组件测试:验证名称/元信息/manifest 展示与安装、拒绝操作。
import { describe, expect, it, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import SkillImportReview from '@/features/skills/components/SkillImportReview.vue'

const staged = { stagedImportId: 'st1', name: 'Helper', description: 'helps', contentHash: 'abc', fileCount: 3, totalBytes: 2048 }
const detail = { stagedImportId: 'st1', sourceKind: 'UPLOAD_ZIP', sourceIdentity: 'skill.zip', manifest: '# Helper', totalBytes: 2048, fileCount: 3, contentHash: 'abc', status: 'STAGED', rejectedReason: null, createdAt: '2026-01-01' }

describe('SkillImportReview', () => {
  it('keeps a staged manifest readable but removes approval controls for a member', () => {
    accessSession.identity!.orgCapabilities = []
    const w = mount(SkillImportReview, { props: { open: true, staged, detail, working: false, error: null } })
    expect(w.get('[data-test="staged-manifest"]').text()).toContain('# Helper')
    expect(w.find('[data-test="install-staged"]').exists()).toBe(false)
    expect(w.find('[data-test="reject-staged"]').exists()).toBe(false)
  })
  it('shows name, meta, and manifest with install and reject', async () => {
    const w = mount(SkillImportReview, { props: { open: true, staged, detail, working: false, error: null } })
    expect(w.get('[data-test="staged-name"]').text()).toContain('Helper')
    expect(w.get('[data-test="staged-manifest"]').text()).toContain('# Helper')
    await w.get('[data-test="install-staged"]').trigger('click')
    await w.get('[data-test="reject-staged"]').trigger('click')
    expect(w.emitted('install')).toHaveLength(1)
    expect(w.emitted('reject')).toHaveLength(1)
  })

  it('falls back to the source identity when opened from the staged list', () => {
    const w = mount(SkillImportReview, { props: { open: true, staged: null, detail, working: false, error: null } })
    expect(w.get('[data-test="staged-name"]').text()).toContain('skill')
  })
})

beforeEach(() => { accessSession.identity = { mode: 'OIDC', authenticated: true, userId: 'editor', orgId: 'org', displayName: 'Editor', orgName: 'Org', orgRole: 'ADMIN', orgCapabilities: ['ORG_MANAGE'], memberships: [] } })

import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import SkillImportReview from '@/components/skills/SkillImportReview.vue'

const staged = { stagedImportId: 'st1', name: 'Helper', description: 'helps', contentHash: 'abc', fileCount: 3, totalBytes: 2048 }
const detail = { stagedImportId: 'st1', sourceKind: 'UPLOAD_ZIP', sourceIdentity: 'skill.zip', manifest: '# Helper', totalBytes: 2048, fileCount: 3, contentHash: 'abc', status: 'STAGED', rejectedReason: null, createdAt: '2026-01-01' }

describe('SkillImportReview', () => {
  it('shows name, meta, and manifest with install and reject', async () => {
    const w = mount(SkillImportReview, { props: { open: true, staged, detail, working: false, error: null } })
    expect(w.get('[data-test="staged-name"]').text()).toContain('Helper')
    expect(w.get('[data-test="staged-manifest"]').text()).toContain('# Helper')
    await w.get('[data-test="install-staged"]').trigger('click')
    await w.get('[data-test="reject-staged"]').trigger('click')
    expect(w.emitted('install')).toHaveLength(1)
    expect(w.emitted('reject')).toHaveLength(1)
  })
})

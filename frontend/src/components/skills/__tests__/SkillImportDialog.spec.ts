import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import SkillImportDialog from '@/components/skills/SkillImportDialog.vue'

describe('SkillImportDialog', () => {
  it('disables zip staging until a file is picked', async () => {
    const w = mount(SkillImportDialog, { props: { open: true, staging: false, error: null } })
    expect((w.get('[data-test="stage-zip"]').element as HTMLButtonElement).disabled).toBe(true)
    const file = new File(['x'], 'skill.zip', { type: 'application/zip' })
    const input = w.get('[data-test="zip-file"]')
    Object.defineProperty(input.element, 'files', { value: [file] })
    await input.trigger('change')
    expect((w.get('[data-test="stage-zip"]').element as HTMLButtonElement).disabled).toBe(false)
    await w.get('[data-test="stage-zip"]').trigger('click')
    expect(w.emitted('submit-zip')?.[0]?.[0]).toBe(file)
  })

  it('submits git url with optional ref', async () => {
    const w = mount(SkillImportDialog, { props: { open: true, staging: false, error: null } })
    await w.get('[data-test="import-tab-git"]').trigger('click')
    await w.get('[data-test="git-url"]').setValue('https://example.com/skill.git')
    await w.get('[data-test="git-ref"]').setValue('main')
    await w.get('[data-test="stage-git"]').trigger('click')
    expect(w.emitted('submit-git')).toEqual([[ 'https://example.com/skill.git', 'main' ]])
  })

  it('renders nothing when closed', () => {
    const w = mount(SkillImportDialog, { props: { open: false, staging: false, error: null } })
    expect(w.find('[data-test="import-dialog"]').exists()).toBe(false)
  })
})

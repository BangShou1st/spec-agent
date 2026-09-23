import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import SkillImportDialog from '@/features/skills/components/SkillImportDialog.vue'

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
    expect(w.emitted('submit-git')).toEqual([[ 'https://example.com/skill.git', 'main', undefined ]])
  })

  it('carries the selected Skill subdirectory', async () => {
    const w = mount(SkillImportDialog, { props: { open: true, staging: false, error: null } })
    await w.get('[data-test="import-tab-git"]').trigger('click')
    await w.get('[data-test="git-url"]').setValue('https://example.com/library.git')
    await w.get('[data-test="git-sub-path"]').setValue('skills/brainstorming')
    await w.get('[data-test="stage-git"]').trigger('click')
    expect(w.emitted('submit-git')?.[0]).toEqual([
      'https://example.com/library.git', undefined, 'skills/brainstorming',
    ])
  })

  it('lists repository Skills and fills the subdirectory when one is picked', async () => {
    const w = mount(SkillImportDialog, {
      props: {
        open: true,
        staging: false,
        error: null,
        candidates: [
          { path: 'skills/brainstorming', name: 'brainstorming', description: 'd', kind: 'NESTED', declaredBy: null, fileCount: 3, parseable: true },
          { path: 'docs/broken', name: 'docs/broken', description: '', kind: 'NESTED', declaredBy: null, fileCount: 1, parseable: false },
        ],
      },
    })
    await w.get('[data-test="import-tab-git"]').trigger('click')
    await w.get('[data-test="git-url"]').setValue('https://example.com/library.git')

    await w.get('[data-test="discover-git"]').trigger('click')
    expect(w.emitted('discover-git')?.[0]).toEqual(['https://example.com/library.git', undefined])

    const options = w.findAll('[data-test="candidate-option"]')
    expect(options).toHaveLength(2)
    expect((options[1].element as HTMLButtonElement).disabled).toBe(true)
    await options[0].trigger('click')
    expect((w.get('[data-test="git-sub-path"]').element as HTMLInputElement).value)
      .toBe('skills/brainstorming')
  })

  it('states the single-skill package expectation for git import', async () => {
    const w = mount(SkillImportDialog, { props: { open: true, staging: false, error: null } })
    await w.get('[data-test="import-tab-git"]').trigger('click')
    expect(w.text()).toContain('仓库根目录有 SKILL.md')
  })

  it('closes on window Escape even when focus is outside', async () => {
    const w = mount(SkillImportDialog, { props: { open: true, staging: false, error: null }, attachTo: document.body })
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    expect(w.emitted('close')).toHaveLength(1)
    w.unmount()
  })

  it('renders nothing when closed', () => {
    const w = mount(SkillImportDialog, { props: { open: false, staging: false, error: null } })
    expect(w.find('[data-test="import-dialog"]').exists()).toBe(false)
  })
})

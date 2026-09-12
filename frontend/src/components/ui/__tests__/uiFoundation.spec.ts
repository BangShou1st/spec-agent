import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import UiFilePicker from '../UiFilePicker.vue'
import UiDialogShell from '../UiDialogShell.vue'
import UiConfirmDialog from '../UiConfirmDialog.vue'
import UiFormField from '../UiFormField.vue'

describe('UiFormField', () => {
  it('renders label hint and error', () => {
    const w = mount(UiFormField, { props: { label: '名称', hint: '提示', error: null }, slots: { default: '<input />' } })
    expect(w.text()).toContain('名称')
    expect(w.text()).toContain('提示')
  })
  it('error uses alert role', () => {
    const w = mount(UiFormField, { props: { label: '名称', error: '必填' }, slots: { default: '<input />' } })
    expect(w.find('[role=alert]').exists()).toBe(true)
  })
})

describe('UiFilePicker', () => {
  it('keeps native file input for semantics but hides default chrome', () => {
    const w = mount(UiFilePicker, { props: { accept: '.zip' } })
    const input = w.find('input[type=file]')
    expect(input.exists()).toBe(true)
    expect(input.attributes('accept')).toBe('.zip')
  })
  it('emits picked file and shows name', async () => {
    const w = mount(UiFilePicker, { props: {} })
    const f = new File(['x'], 'a.zip', { type: 'application/zip' })
    const input = w.find('input[type=file]').element as unknown as HTMLInputElement
    Object.defineProperty(input, 'files', { value: [f] })
    await w.find('input[type=file]').trigger('change')
    expect(w.emitted('pick')?.[0]?.[0]).toBe(f)
    expect(w.text()).toContain('a.zip')
  })
})

describe('UiDialogShell', () => {
  it('renders title description and actions with accessible dialog', () => {
    const w = mount(UiDialogShell, { props: { open: true, title: '标题', description: '说明' }, slots: { default: '<p>body</p>', actions: '<button>ok</button>' } })
    const dlg = w.find('[role=dialog]')
    expect(dlg.exists()).toBe(true)
    expect(dlg.attributes('aria-modal')).toBe('true')
    expect(w.text()).toContain('标题')
  })
  it('emits close on escape', async () => {
    const w = mount(UiDialogShell, { props: { open: true, title: 't' } })
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }))
    expect(w.emitted('close')).toBeTruthy()
  })
})

describe('UiConfirmDialog', () => {
  it('destructive confirm has cancel and solid danger', () => {
    const w = mount(UiConfirmDialog, { props: { open: true, title: '删除？', description: '不可撤销', confirmLabel: '删除项目' } })
    expect(w.find('[data-test=ui-confirm-cancel]').exists()).toBe(true)
    const ok = w.find('[data-test=ui-confirm-ok]')
    expect(ok.classes()).toContain('btn-danger--solid')
  })
})

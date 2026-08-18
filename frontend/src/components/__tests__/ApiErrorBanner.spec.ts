import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ApiErrorBanner from '@/components/ApiErrorBanner.vue'

describe('ApiErrorBanner', () => {
  it('renders the safe backend message and stable code only', () => {
    const wrapper = mount(ApiErrorBanner, {
      props: { message: 'The model provider is temporarily rate limited', code: 'MODEL_PROVIDER_RATE_LIMITED' },
    })
    expect(wrapper.text()).toContain('MODEL_PROVIDER_RATE_LIMITED')
    expect(wrapper.text()).toContain('The model provider is temporarily rate limited')
  })

  it('emits retry when the retry button is clicked', async () => {
    const wrapper = mount(ApiErrorBanner, {
      props: { message: 'Something went wrong. Please try again.', retryLabel: 'Retry' },
    })
    await wrapper.find('button').trigger('click')
    expect(wrapper.emitted('retry')).toHaveLength(1)
  })

  it('does not render a retry button when no retry label is given', () => {
    const wrapper = mount(ApiErrorBanner, { props: { message: 'Safe message' } })
    expect(wrapper.find('button').exists()).toBe(false)
  })

  it('relabels the retry button while retrying', () => {
    const wrapper = mount(ApiErrorBanner, {
      props: { message: 'Safe message', retryLabel: 'Retry', retrying: true },
    })
    expect(wrapper.find('button').text()).toBe('Retrying…')
  })
})
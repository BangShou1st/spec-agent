import { computed, ref, watch, type Ref } from 'vue'
import { apiClient } from '@/shared/http/client'
import { accessSession } from './accessSession'

// Experimental UI refresh timings, not an authorization or availability guarantee.
export const PROJECT_ACCESS_REFRESH_MS = 5_000
export const PROJECT_ACCESS_TIMEOUT_MS = 5_000

/** Presentation only: the backend still authorizes every operation. */
export function useProjectAccess(projectId: Ref<string>) {
  const capabilities = ref<string[]>([])
  const loading = ref(true)
  const failed = ref(false)
  watch(
    () => [projectId.value, accessSession.identity?.userId,
      accessSession.identity?.orgId, accessSession.revision] as const,
    ([project], _previous, onCleanup) => {
      let current = true
      let inFlight = false
      let poll: ReturnType<typeof setTimeout> | undefined
      let deadline: ReturnType<typeof setTimeout> | undefined
      let controller: AbortController | undefined
      capabilities.value = []
      loading.value = true
      failed.value = false
      async function renew(clear = false) {
        if (!current) return
        if (clear) { loading.value = true; capabilities.value = [] }
        if (inFlight) return
        clearTimeout(poll)
        inFlight = true
        controller = new AbortController()
        try {
          const timeout = new Promise<never>((_resolve, reject) => {
            deadline = setTimeout(() => {
              controller?.abort()
              reject(new Error('Project access check expired'))
            }, PROJECT_ACCESS_TIMEOUT_MS)
          })
          const result = await Promise.race([
            apiClient.get<{ capabilities: string[] }>(`/projects/${project}/access`, { signal: controller.signal }),
            timeout,
          ])
          if (!current) return
          if (!Array.isArray(result.capabilities) || result.capabilities.some(c => typeof c !== 'string')) {
            throw new Error('Invalid project access response')
          }
          capabilities.value = result.capabilities
          failed.value = false
        } catch {
          if (current) { capabilities.value = []; failed.value = true }
        } finally {
          clearTimeout(deadline)
          inFlight = false
          if (current) {
            loading.value = false
            poll = setTimeout(() => { void renew() }, PROJECT_ACCESS_REFRESH_MS)
          }
        }
      }
      const onFocus = () => { void renew(true) }
      const onVisibility = () => { if (document.visibilityState === 'visible') onFocus() }
      window.addEventListener('focus', onFocus)
      document.addEventListener('visibilitychange', onVisibility)
      onCleanup(() => {
        current = false
        controller?.abort()
        clearTimeout(poll)
        clearTimeout(deadline)
        window.removeEventListener('focus', onFocus)
        document.removeEventListener('visibilitychange', onVisibility)
      })
      void renew()
    },
    { immediate: true, flush: 'sync' },
  )
  return {
    loading, failed,
    canRead: computed(() => capabilities.value.includes('PROJECT_READ')),
    canWrite: computed(() => capabilities.value.includes('PROJECT_WRITE')),
    canManage: computed(() => capabilities.value.includes('PROJECT_MANAGE')),
  }
}

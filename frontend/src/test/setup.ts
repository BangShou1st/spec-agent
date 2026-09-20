import { afterEach, beforeEach } from 'vitest'
import { enableAutoUnmount } from '@vue/test-utils'

// Automatically unmount mounted components after each test so event
// listeners and DOM from previous tests never leak into the next one.
enableAutoUnmount(afterEach)

// Each test starts in a fresh browser tab. Reload tests deliberately create
// another Pinia within the same test so session draft persistence is exercised.
beforeEach(() => sessionStorage.removeItem('spec-agent:input-drafts:v1'))

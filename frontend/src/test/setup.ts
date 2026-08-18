import { afterEach } from 'vitest'
import { enableAutoUnmount } from '@vue/test-utils'

// Automatically unmount mounted components after each test so event
// listeners and DOM from previous tests never leak into the next one.
enableAutoUnmount(afterEach)
// 文件名:setup.ts
// 用途:vitest 全局测试装配:每个用例后自动卸载已挂载组件,并在用例开始前清空会话草稿存储,保证用例之间互不泄漏。
import { afterEach, beforeEach } from 'vitest'
import { enableAutoUnmount } from '@vue/test-utils'

// 每个用例结束后自动卸载已挂载的组件,让上一个用例的事件监听器与 DOM
// 绝不泄漏到下一个用例。
enableAutoUnmount(afterEach)

// 每个用例都从全新的浏览器标签页开始。刷新类用例刻意在同一用例内再建
// 一个 Pinia,以覆盖会话草稿持久化的场景。
beforeEach(() => sessionStorage.removeItem('spec-agent:input-drafts:v1'))

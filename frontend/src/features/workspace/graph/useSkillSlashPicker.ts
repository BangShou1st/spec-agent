// 文件名:useSkillSlashPicker.ts
// 用途:纯 textarea 的"/"技能斜杠选择器 composable:检测光标前的 /token、管理技能菜单状态、把 token 替换为 @skill/<name> 提及并返回 skillId 供调用方随文本持久化。
import { computed, ref } from 'vue'
import { useSkillsStore } from '@/features/skills/state/skillsStore'
import type { SkillSummary } from '@/features/skills/api/skillTypes'

const TOKEN_PATTERN = /(?:^|\s)\/([^\s]*)$/
export const MAX_VISIBLE_SKILLS = 8

export interface SlashToken {
  start: number
  query: string
}

/*
 * 面向纯 textarea 的"/"技能选择器:在 token 起始处(行首或空白之后)输入
 * "/"会打开已启用技能菜单;选中一项会把 token 替换为可见的
 * `@skill/<name>` 提及,并记录调用方随文本一起持久化的 skillId 绑定。
 *
 * 刻意保持轻框架:本 composable 负责检测/菜单状态与 token 替换,宿主组件
 * 持有 textarea 元素并应用返回的文本。仅启用技能的过滤与 agent 侧的
 * SkillVisibilityService 保持一致,选择器绝不可能绑定被禁用的技能。
 */
export function useSkillSlashPicker() {
  const skills = useSkillsStore()
  const open = ref(false)
  const query = ref('')
  const activeIndex = ref(0)
  const tokenStart = ref(-1)

  const enabledSkills = computed(() => skills.list.filter((skill) => skill.enabled))
  const filtered = computed(() => {
    const needle = query.value.trim().toLowerCase()
    const pool = enabledSkills.value
    const matches = needle
      ? pool.filter((skill) => skill.name.toLowerCase().includes(needle)
        || (skill.description ?? '').toLowerCase().includes(needle))
      : pool
    return matches.slice(0, MAX_VISIBLE_SKILLS)
  })

  function close(): void {
    open.value = false
    query.value = ''
    tokenStart.value = -1
    activeIndex.value = 0
  }

  /*
   * 检测光标前的"/query" token 并同步菜单状态。
   * 返回检测到的 token(菜单关闭时返回 null)供宿主响应。
   */
  function syncWithCaret(text: string, caret: number): SlashToken | null {
    const match = TOKEN_PATTERN.exec(text.slice(0, Math.max(0, caret)))
    if (!match) {
      close()
      return null
    }
    const tokenQuery = match[1]
    const start = caret - 1 - tokenQuery.length
    if (!open.value) {
      // 每次打开都刷新,让刚安装的技能立即可见;store 自己会把错误吞进状态。
      void skills.loadList()
    }
    if (start !== tokenStart.value || tokenQuery !== query.value) {
      activeIndex.value = 0
    }
    tokenStart.value = start
    query.value = tokenQuery
    open.value = true
    return { start, query: tokenQuery }
  }

  function move(delta: number): void {
    if (!filtered.value.length) return
    activeIndex.value = (activeIndex.value + delta + filtered.value.length) % filtered.value.length
  }

  function activeSkill(): SkillSummary | null {
    return filtered.value[activeIndex.value] ?? null
  }

  /** 把"/query" token 替换为可见的技能提及。 */
  function applySkill(text: string, caret: number, skill: SkillSummary): { text: string; caret: number } {
    const next = text.slice(caret, caret + 1)
    const mention = `@skill/${skill.name}${next && /\s/.test(next) ? '' : ' '}`
    const start = tokenStart.value >= 0 ? tokenStart.value : Math.max(0, caret - 1 - query.value.length)
    return {
      text: text.slice(0, start) + mention + text.slice(caret),
      caret: start + mention.length,
    }
  }

  return { open, query, activeIndex, filtered, enabledSkills, close, syncWithCaret, move, activeSkill, applySkill }
}

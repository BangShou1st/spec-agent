import { computed, ref } from 'vue'
import { useSkillsStore } from '@/features/skills/state/skillsStore'
import type { SkillSummary } from '@/features/skills/api/skillTypes'

const TOKEN_PATTERN = /(?:^|\s)\/([^\s]*)$/
export const MAX_VISIBLE_SKILLS = 8

export interface SlashToken {
  start: number
  query: string
}

/**
 * "/" skill picker for plain textareas: typing "/" at a token start (line
 * start or after whitespace) opens the enabled-skill menu; picking one
 * replaces the token with a visible `@skill/<name>` mention and records the
 * skillId binding the caller persists next to the text.
 *
 * Deliberately framework-light: the composable owns detection/menu state and
 * token replacement, while the host component owns the textarea element and
 * applies returned text. Enabled-only filtering mirrors the agent-side
 * SkillVisibilityService, so the picker can never bind a disabled skill.
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

  /**
   * Detects a "/query" token right before the caret and syncs menu state.
   * Returns the detected token (or null when closed) so hosts can react.
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
      // Refresh on every open so freshly installed skills appear; the store
      // swallows its own errors into state.
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

  /** Replaces the "/query" token with the visible skill mention. */
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

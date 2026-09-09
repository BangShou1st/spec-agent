<script setup lang="ts">
import { ref } from 'vue'
import type { SkillSummary } from '@/api/skillTypes'

withDefaults(defineProps<{
  skills: SkillSummary[]
  loading: boolean
  busyId?: string | null
}>(), { busyId: null })

const emit = defineEmits<{
  (e: 'select', skillId: string): void
  (e: 'enable', skillId: string): void
  (e: 'disable', skillId: string): void
  (e: 'remove', skillId: string): void
}>()

const confirmingId = ref<string | null>(null)

function mark(name: string): string {
  const t = (name ?? '').trim()
  return t ? t.slice(0, 1).toUpperCase() : 'S'
}
</script>

<template>
  <div class="skills-list" data-test="skills-list">
    <p v-if="loading" class="muted" data-test="skills-loading">Loading skills...</p>
    <ul v-else class="skills-rows">
      <li
        v-for="skill in skills"
        :key="skill.skillId"
        class="skills-row"
        :data-test="`skill-row-${skill.skillId}`"
      >
        <span class="skills-mark" aria-hidden="true">{{ mark(skill.name) }}</span>
        <button
          type="button"
          class="skills-main"
          :data-test="`skill-select-${skill.skillId}`"
          @click="emit('select', skill.skillId)"
        >
          <span class="skills-name">{{ skill.name }}</span>
          <span class="skills-desc">{{ skill.description }}</span>
        </button>
        <span
          class="skills-status"
          :class="skill.enabled ? 'skills-status--on' : 'skills-status--off'"
          :data-test="`skill-status-${skill.skillId}`"
        >
          <span class="skills-status__dot" aria-hidden="true"></span>{{ skill.enabled ? '已启用' : '已禁用' }}</span>
        <details class="skills-more" :data-test="`skill-more-${skill.skillId}`">
          <summary aria-label="More actions">...</summary>
          <div class="skills-more__actions">
            <button
              v-if="!skill.enabled"
              type="button"
              class="btn"
              :disabled="busyId === skill.skillId"
              :data-test="`skill-enable-${skill.skillId}`"
              @click="emit('enable', skill.skillId)"
            >启用</button>
            <button
              v-else
              type="button"
              class="btn"
              :disabled="busyId === skill.skillId"
              :data-test="`skill-disable-${skill.skillId}`"
              @click="emit('disable', skill.skillId)"
            >禁用</button>
            <button
              v-if="confirmingId !== skill.skillId"
              type="button"
              class="btn"
              :disabled="busyId === skill.skillId"
              :data-test="`skill-delete-${skill.skillId}`"
              @click="confirmingId = skill.skillId"
            >删除</button>
            <span v-else class="skills-confirm">
              <span>删除该 Skill 及其全部版本？</span>
              <button
                type="button"
                class="btn btn-danger"
                :disabled="busyId === skill.skillId"
                :data-test="`skill-delete-confirm-${skill.skillId}`"
                @click="emit('remove', skill.skillId); confirmingId = null"
              >确认删除</button>
              <button
                type="button"
                class="btn"
                :data-test="`skill-delete-cancel-${skill.skillId}`"
                @click="confirmingId = null"
              >取消</button>
            </span>
          </div>
        </details>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.skills-list { width: 100%; }
.skills-rows { list-style: none; margin: 0; padding: 0; background: var(--color-surface); border: 1px solid var(--color-border); border-radius: 12px; }
.skills-row { display: flex; align-items: center; gap: 12px; padding: 13px 16px; transition: background 120ms ease; }
.skills-row:hover { background: var(--color-surface-subtle); }
.skills-row + .skills-row { border-top: 1px solid var(--color-border); }
.skills-mark { flex: none; width: 32px; height: 32px; border-radius: 8px; background: var(--color-subdued); color: var(--color-text-secondary); font-weight: 700; display: inline-flex; align-items: center; justify-content: center; }
.skills-main { flex: 1; min-width: 0; display: flex; flex-direction: column; align-items: flex-start; gap: 2px; background: none; border: none; padding: 0; text-align: left; cursor: pointer; }
.skills-main:focus-visible { outline: none; box-shadow: var(--focus-ring); border-radius: 6px; }
.skills-name { font-weight: 600; color: var(--color-text); }
.skills-desc { width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--color-text-secondary); font-size: 13px; }
.skills-status { flex: none; display: inline-flex; align-items: center; gap: 6px; font-size: 12px; font-weight: 600; white-space: nowrap; }
.skills-status--on { color: var(--color-success); }
.skills-status--off { color: var(--color-text-secondary); }
.skills-status__dot { width: 7px; height: 7px; border-radius: 50%; background: currentColor; }
.skills-more { flex: none; position: relative; }
.skills-more summary { cursor: pointer; list-style: none; padding: 4px 8px; border-radius: 6px; color: var(--color-text-secondary); font-weight: 700; letter-spacing: 0.1em; }
.skills-more summary:focus-visible { outline: none; box-shadow: var(--focus-ring); }
.skills-more__actions { position: absolute; right: 0; top: calc(100% + 4px); z-index: 5; display: flex; flex-direction: column; gap: 8px; min-width: 220px; padding: 12px; background: var(--color-surface); border: 1px solid var(--color-border); border-radius: 10px; box-shadow: var(--shadow-card); }
.skills-confirm { display: flex; flex-direction: column; gap: 8px; font-size: 13px; color: var(--color-text-secondary); }
</style>

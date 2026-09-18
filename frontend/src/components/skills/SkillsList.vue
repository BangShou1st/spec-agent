<script setup lang="ts">
import { ref } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import ToggleSwitch from '@/components/ui/ToggleSwitch.vue'
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

        <span v-if="confirmingId === skill.skillId" class="skills-confirm">
          <span class="skills-confirm__text">删除该 Skill 及其全部版本？</span>
          <button
            type="button"
            class="btn btn-danger"
            :disabled="busyId === skill.skillId"
            :data-test="`skill-delete-confirm-${skill.skillId}`"
            @click="emit('remove', skill.skillId); confirmingId = null"
          >删除</button>
          <button
            type="button"
            class="btn"
            :data-test="`skill-delete-cancel-${skill.skillId}`"
            @click="confirmingId = null"
          >取消</button>
        </span>

        <template v-else>
          <ToggleSwitch
            :checked="skill.enabled"
            :disabled="busyId === skill.skillId"
            :title="skill.enabled ? '停用该 Skill' : '启用该 Skill'"
            :data-test="skill.enabled ? `skill-disable-${skill.skillId}` : `skill-enable-${skill.skillId}`"
            :status-test-id="`skill-status-${skill.skillId}`"
            @toggle="skill.enabled ? emit('disable', skill.skillId) : emit('enable', skill.skillId)"
          />
          <button
            type="button"
            class="skill-icon-btn"
            :disabled="busyId === skill.skillId"
            title="删除该 Skill"
            :data-test="`skill-delete-${skill.skillId}`"
            @click="confirmingId = skill.skillId"
          >
            <AppIcon name="trash" />
          </button>
        </template>
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

.skill-icon-btn { flex: none; display: inline-flex; align-items: center; justify-content: center; width: 30px; height: 30px; border-radius: 8px; background: none; border: 1px solid var(--color-border); color: var(--color-danger); cursor: pointer; transition: background 120ms ease, border-color 120ms ease; }
.skill-icon-btn:hover:not(:disabled) { border-color: var(--color-danger); background: rgb(220 38 38 / 10%); }
.skill-icon-btn:focus-visible { outline: none; box-shadow: var(--focus-ring); }
.skill-icon-btn:disabled { opacity: 0.5; cursor: progress; }

.skills-confirm { flex: none; display: inline-flex; align-items: center; gap: 8px; }
.skills-confirm__text { font-size: 13px; color: var(--color-danger); white-space: nowrap; }
</style>

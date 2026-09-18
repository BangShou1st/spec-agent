<script setup lang="ts">
import type { SkillSummary } from '@/api/skillTypes'

/**
 * Candidate menu for the "/" skill picker: bounded list of enabled skills
 * (name + description). Purely presentational — selection and highlighting
 * state come from the host via props/events.
 */
defineProps<{
  items: SkillSummary[]
  activeIndex: number
}>()

const emit = defineEmits<{
  select: [skill: SkillSummary]
  hover: [index: number]
  /** 菜单内任意 mousedown(含滚动条):宿主用它豁免本次 blur 关闭。 */
  press: []
}>()
</script>

<template>
  <ul
    class="skill-slash-menu"
    data-test="skill-slash-menu"
    role="listbox"
    aria-label="已启用的 Skill"
    @mousedown="emit('press')"
  >
    <li v-if="!items.length" class="skill-slash-menu__empty meta-text" data-test="skill-slash-empty">
      没有匹配的已启用 Skill
    </li>
    <li
      v-for="(skill, index) in items"
      :key="skill.skillId"
      class="skill-slash-menu__item"
      :class="{ 'skill-slash-menu__item--active': index === activeIndex }"
      role="option"
      :aria-selected="index === activeIndex"
      :data-test="`skill-slash-option-${skill.skillId}`"
      @mousedown.prevent="emit('select', skill)"
      @mouseenter="emit('hover', index)"
    >
      <span class="skill-slash-menu__name">{{ skill.name }}</span>
      <span v-if="skill.description" class="skill-slash-menu__desc meta-text">{{ skill.description }}</span>
    </li>
  </ul>
</template>

<style scoped>
.skill-slash-menu {
  position: absolute;
  top: calc(100% + 4px);
  left: 0;
  right: 0;
  z-index: 30;
  margin: 0;
  padding: var(--space-1, 4px);
  list-style: none;
  background: var(--surface-raised, #fff);
  border: 1px solid var(--border-default, #d8d4e8);
  border-radius: 8px;
  box-shadow: 0 8px 24px rgb(30 20 60 / 0.14);
  max-height: 220px;
  overflow-y: auto;
}

.skill-slash-menu__item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 6px 8px;
  border-radius: 6px;
  cursor: pointer;
}

.skill-slash-menu__item--active {
  background: var(--surface-hover, #efeafd);
}

.skill-slash-menu__name {
  font-size: 13px;
  font-weight: 600;
}

.skill-slash-menu__desc {
  font-size: 12px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.skill-slash-menu__empty {
  padding: 8px;
  font-size: 12px;
}
</style>

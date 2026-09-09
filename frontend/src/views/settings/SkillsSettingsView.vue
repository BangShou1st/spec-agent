<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, RouterLink } from 'vue-router'

const route = useRoute()
const skillId = computed(() => (route.params.skillId as string | undefined) ?? null)
</script>

<template>
  <section data-test="skills-settings">
    <h2>Skills</h2>
    <p data-test="skills-placeholder">Skills management lands after the visual direction is chosen.</p>
    <p v-if="skillId" data-test="skill-detail-placeholder">Skill: {{ skillId }}</p>
    <RouterLink v-if="skillId" to="/settings/skills" data-test="back-to-skills">Back to Skills</RouterLink>
  </section>
</template>

<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'
</script>

<template>
  <section class="settings-shell" data-test="settings-shell">
    <header class="settings-shell__heading">
      <p class="eyebrow">SETUP</p>
      <h1>Settings</h1>
    </header>
    <nav class="settings-shell__nav" aria-label="Settings sections" data-test="settings-nav">
      <RouterLink to="/settings/models" data-test="settings-nav-models">Models</RouterLink>
      <RouterLink to="/settings/skills" data-test="settings-nav-skills">Skills</RouterLink>
      <RouterLink to="/settings/connections" data-test="settings-nav-connections">Connections</RouterLink>
    </nav>
    <RouterView />
  </section>
</template>

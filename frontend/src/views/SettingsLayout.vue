<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'
</script>

<template>
  <section class="settings-shell" data-test="settings-shell">
    <header class="settings-shell__heading">
      <p class="eyebrow">SETTINGS</p>
      <h1>设置</h1>
      <p class="page-desc">模型、Skills 与连接共用同一套设置语言与状态表达。</p>
    </header>
    <nav class="settings-shell__nav" aria-label="Settings sections" data-test="settings-nav">
      <RouterLink to="/settings/models" data-test="settings-nav-models">模型</RouterLink>
      <RouterLink to="/settings/skills" data-test="settings-nav-skills">Skills</RouterLink>
      <RouterLink to="/settings/connections" data-test="settings-nav-connections">Connections</RouterLink>
    </nav>
    <RouterView />
  </section>
</template>

<style scoped>
.settings-shell { width: 100%; max-width: 880px; margin: 0 auto; padding: 32px 0 64px; }
.settings-shell__heading .eyebrow { margin: 0 0 6px; color: var(--color-text-muted); font-size: 12px; font-weight: 600; letter-spacing: 0.08em; }
.settings-shell__heading h1 { margin: 0; font-size: 30px; line-height: 1.2; letter-spacing: -0.02em; }
.settings-shell__nav { display: flex; gap: 2px; margin: 16px 0 20px; padding: 0 0 8px; border-bottom: 1px solid var(--color-border); width: 100%; }
 .settings-shell__nav a { position: relative; padding: 7px 12px; border-radius: 8px; color: var(--color-text-secondary); text-decoration: none; font-size: 13.5px; }
 .settings-shell__nav a:hover { background: var(--color-surface-subtle); color: var(--color-text); }
 .settings-shell__nav a.router-link-active { color: var(--color-text); font-weight: 650; }
 .settings-shell__nav a.router-link-active::after { content: ''; position: absolute; left: 12px; right: 12px; bottom: -9px; height: 2px; border-radius: 2px; background: var(--color-accent); }
.settings-shell__nav a:focus-visible { outline: none; box-shadow: var(--focus-ring); }
</style>

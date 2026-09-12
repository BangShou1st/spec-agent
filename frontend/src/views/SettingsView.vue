<script setup lang="ts">
import ProviderSettingsSection from '@/components/providers/ProviderSettingsSection.vue'
</script>

<template>
  <section class="settings-page" data-test="settings-page">
    <header class="settings-page__heading">
      <h2>模型</h2>
      <p class="settings-page__subtitle">管理 Spec Agent 使用的 AI Provider。切换 Tab 只是在编辑配置，不会切换运行时。</p>
    </header>
    <ProviderSettingsSection />
  </section>
</template>

<style scoped>
.settings-page {
  width: 100%;
  max-width: 840px;
  margin: 0 auto;
  padding: 32px 0 64px;
}
.settings-page__heading {
  margin-bottom: 16px;
}
.settings-page__heading h2 {
  margin: 0 0 4px;
  font-size: 20px;
  line-height: 1.3;
  letter-spacing: -0.01em;
  font-weight: 650;
}
.settings-page__subtitle {
  margin: 8px 0 0;
  color: var(--color-text-secondary);
  font-size: 14px;
}
@media (max-width: 700px) {
  .settings-page {
    padding: 24px 0 40px;
  }
}
</style>

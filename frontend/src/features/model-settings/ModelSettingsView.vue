<!--
  文件名:ModelSettingsView.vue
  用途:设置区的"模型"页面:页面标题与副标题,内部承载 ProviderSettingsSection
       完成各 Provider 的配置管理。
-->
<script setup lang="ts">
import ProviderSettingsSection from '@/features/model-settings/components/ProviderSettingsSection.vue'
</script>

<template>
  <div data-test="settings-models-section">
    <section class="settings-page" data-test="settings-page">
      <header class="settings-page__heading">
        <h2>模型</h2>
        <p class="settings-page__subtitle">管理 Spec Agent 使用的 AI Provider</p>
      </header>
      <ProviderSettingsSection />
    </section>
  </div>
</template>

<style scoped>
/* 页面容器样式统一收口在 providerSettings.css（.settings-page），
   此处仅保留组件自身结构性样式，避免双处维护。 */
</style>

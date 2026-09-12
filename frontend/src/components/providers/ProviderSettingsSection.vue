<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useProviderSettingsStore } from '@/stores/providerSettingsStore'
import { providerDisplayName } from '@/presentation/providerPresentation'
import type { ModelProvider } from '@/api/modelProviders'
import OpenCodeProviderSettings from './OpenCodeProviderSettings.vue'
import OpenRouterProviderSettings from './OpenRouterProviderSettings.vue'
import CustomProviderSettings from './CustomProviderSettings.vue'

const providers = useProviderSettingsStore()

const tabs: Array<{ value: ModelProvider; label: string }> = [
  { value: 'OPENCODE_ZEN', label: 'OpenCode Zen' },
  { value: 'OPENROUTER', label: 'OpenRouter' },
  { value: 'CUSTOM', label: 'Custom' },
]

const activeLabel = computed(() => providerDisplayName(providers.activeProvider))

function selectTab(value: ModelProvider): void {
  providers.view(value)
}

onMounted(() => {
  void providers.loadActive()
})
</script>

<template>
  <section class="provider-section" data-test="provider-section">
    <div class="provider-active-banner" data-test="active-provider-banner">
      <span class="provider-active-banner__label">当前使用</span>
      <strong data-test="active-provider-name">{{ activeLabel }}</strong>
    </div>

    <div class="provider-tabs" role="tablist" aria-label="AI Provider" data-test="provider-tabs">
      <button
        v-for="tab in tabs"
        :key="tab.value"
        role="tab"
        type="button"
        class="provider-tab"
        :class="{ 'provider-tab--selected': providers.viewing === tab.value }"
        :aria-selected="providers.viewing === tab.value"
        :data-test="`provider-tab-${tab.value.toLowerCase()}`"
        @click="selectTab(tab.value)"
      >
        {{ tab.label }}
        <span
          v-if="providers.activeProvider === tab.value"
          class="provider-tab__badge"
          data-test="provider-active-badge"
        >当前</span>
      </button>
    </div>
    <p class="provider-tabs__hint">切换 Tab 只是在编辑配置，不会切换运行时。</p>

    <div v-if="providers.viewing === 'OPENCODE_ZEN'" role="tabpanel">
      <OpenCodeProviderSettings />
    </div>
    <div v-else-if="providers.viewing === 'OPENROUTER'" role="tabpanel">
      <OpenRouterProviderSettings />
    </div>
    <div v-else role="tabpanel">
      <CustomProviderSettings />
    </div>
  </section>
</template>

<style scoped>
.provider-section {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.provider-active-banner {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 10px;
  font-size: 13px;
}
.provider-active-banner__label {
  color: var(--color-text-secondary);
}
.provider-tabs {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.provider-tab {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  border: 1px solid var(--color-border);
  border-radius: 999px;
  background: var(--color-surface);
  color: var(--color-text-secondary);
  font-weight: 600;
  font-size: 13px;
}
.provider-tab--selected {
  color: var(--color-accent-strong);
  background: var(--color-accent-soft);
  border-color: var(--color-accent);
}
.provider-tab:focus-visible {
  outline: none;
  box-shadow: var(--focus-ring);
}
.provider-tab__badge {
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--color-success-soft);
  color: var(--color-success);
  font-size: 11px;
  font-weight: 700;
}
.provider-tabs__hint {
  margin: 0;
  color: var(--color-text-muted);
  font-size: 12px;
}
</style>

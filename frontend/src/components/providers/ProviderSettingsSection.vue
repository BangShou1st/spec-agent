<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useProviderSettingsStore } from '@/stores/providerSettingsStore'
import { useCustomProviderStore } from '@/stores/customProviderStore'
import { providerDisplayName } from '@/presentation/providerPresentation'
import type { ModelProvider } from '@/api/modelProviders'
import OpenCodeProviderSettings from './OpenCodeProviderSettings.vue'
import OpenRouterProviderSettings from './OpenRouterProviderSettings.vue'
import CustomProviderSettings from './CustomProviderSettings.vue'
import CustomProviderDialog from './CustomProviderDialog.vue'

/**
 * The provider registry surface. Pills only select which configuration is
 * being edited — they never switch the runtime provider; that stays an
 * explicit 「设为当前 Provider」 action on each card.
 */
const providers = useProviderSettingsStore()
const custom = useCustomProviderStore()

// 预设 Provider 是常驻 Tab；Custom 是用户自建项：未配置时通过末尾 “+” 创建，
// 配置完成后以胶囊加入（与预设同样式），点击胶囊进入编辑卡片。
const presetTabs: Array<{ value: ModelProvider; label: string }> = [
  { value: 'OPENCODE_ZEN', label: 'OpenCode Zen' },
  { value: 'OPENROUTER', label: 'OpenRouter' },
]

const activeLabel = computed(() => providerDisplayName(providers.activeProvider))
const customConfigured = computed(() => custom.configured)
const customLabel = computed(() => custom.displayName?.trim() || 'Custom')
const viewingCustom = computed(() => providers.viewing === 'CUSTOM')

const dialogOpen = ref(false)
const dialogMode = ref<'create' | 'edit'>('create')

function selectTab(value: ModelProvider): void {
  providers.view(value)
}

/**
 * 打开创建弹窗时**不能**改动当前正在查看的配置。
 *
 * 之前这里调了 providers.view('CUSTOM')，而未配置时 Custom 面板的 v-if 与
 * 两个预设面板都不成立 —— 整个卡片区被卸载，看起来就成了「弹窗把底下的东西
 * 盖没了」。弹窗只是浮层，底下的卡片必须留在原地。
 * 视图的切换推迟到创建成功、胶囊真正出现之后。
 */
function openCreate(): void {
  custom.clearError()
  dialogMode.value = 'create'
  dialogOpen.value = true
}

/** Editing reuses the create dialog, so 显示名称 stays editable after creation. */
function openEdit(): void {
  custom.clearError()
  dialogMode.value = 'edit'
  dialogOpen.value = true
}

/**
 * 关闭弹窗：只有 Custom 确实已配置（新建成功，或本来就在编辑它）才切过去
 * 展示新卡片；否则保持用户原本正在看的那张卡不变。
 */
function closeDialog(): void {
  dialogOpen.value = false
  if (customConfigured.value) {
    providers.view('CUSTOM')
  }
}

onMounted(() => {
  void providers.loadActive()
  // 胶囊需要知道 Custom 是否已配置及其显示名。
  void custom.loadStatus()
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
        v-for="tab in presetTabs"
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
      <button
        v-if="customConfigured"
        role="tab"
        type="button"
        class="provider-tab"
        :class="{ 'provider-tab--selected': viewingCustom }"
        :aria-selected="viewingCustom"
        data-test="provider-tab-custom"
        @click="selectTab('CUSTOM')"
      >
        {{ customLabel }}
        <span
          v-if="providers.activeProvider === 'CUSTOM'"
          class="provider-tab__badge"
          data-test="provider-active-badge"
        >当前</span>
      </button>
      <button
        v-if="!customConfigured"
        type="button"
        class="provider-tab provider-tab--add"
        aria-label="添加自定义 Provider"
        title="添加自定义 Provider"
        data-test="provider-add-custom"
        @click="openCreate"
      >
        +
      </button>
    </div>

    <div v-if="viewingCustom && customConfigured" role="tabpanel">
      <CustomProviderSettings @edit="openEdit" />
    </div>
    <div v-else-if="providers.viewing === 'OPENCODE_ZEN'" role="tabpanel">
      <OpenCodeProviderSettings />
    </div>
    <div v-else-if="providers.viewing === 'OPENROUTER'" role="tabpanel">
      <OpenRouterProviderSettings />
    </div>

    <CustomProviderDialog :open="dialogOpen" :mode="dialogMode" @close="closeDialog" />
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
  align-items: center;
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
.provider-tab--add {
  padding: 8px 12px;
  color: var(--color-text-muted);
}
</style>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import ApiErrorBanner from '@/components/ApiErrorBanner.vue'
import { useModelSettingsStore } from '@/stores/modelSettingsStore'

const store = useModelSettingsStore()
const apiKey = ref('')
const probed = ref(false)

const canProbe = computed(() => apiKey.value.trim().length > 0 && !store.probing && !store.saving)
const canSave = computed(() => probed.value && apiKey.value.trim().length > 0
  && store.selectedModel !== null && !store.saving && !store.probing)

onMounted(() => {
  void store.loadStatus()
})

async function probe(): Promise<void> {
  const models = await store.probe(apiKey.value)
  probed.value = models.length > 0
}

async function save(): Promise<void> {
  if (!store.selectedModel) return
  const saved = await store.save(apiKey.value, store.selectedModel)
  if (saved) {
    apiKey.value = ''
    probed.value = false
  }
}
</script>

<template>
  <section class="settings-page" data-test="settings-page">
    <div class="settings-page__heading">
      <div>
        <p class="eyebrow">设置</p>
        <h1>模型设置</h1>
      </div>
      <span class="badge" :class="store.status?.configured ? 'badge-open' : 'badge-warn'">
        {{ store.status?.configured ? '已配置' : '尚未配置' }}
      </span>
    </div>

    <ApiErrorBanner
      v-if="store.error"
      :message="store.error.message"
      :code="store.error.code"
      retry-label="重试"
      :retrying="store.probing || store.saving"
      @retry="store.clearError()"
    />

    <div class="settings-card panel">
      <h2>OpenCode</h2>
      <p class="muted">全局模型设置会用于下一次需要模型的操作。</p>

      <label class="field-label" for="opencode-api-key">OpenCode API Key</label>
      <input
        id="opencode-api-key"
        v-model="apiKey"
        class="answer-input settings-key-input"
        type="password"
        autocomplete="off"
        data-test="opencode-api-key"
        placeholder="输入你的 API Key"
      />
      <p v-if="store.status?.configured" class="meta-text" data-test="masked-key">
        当前工作配置：{{ store.status.maskedKey }} · {{ store.status.selectedModel }}
      </p>

      <button class="btn btn-primary" type="button" data-test="probe-opencode" :disabled="!canProbe" @click="probe">
        {{ store.probing ? '正在验证…' : '验证并获取模型' }}
      </button>

      <label class="field-label" for="opencode-model">可用模型</label>
      <select
        id="opencode-model"
        v-model="store.selectedModel"
        class="answer-input"
        data-test="opencode-model"
        :disabled="!probed || store.freeModels.length === 0 || store.saving"
      >
        <option :value="null" disabled>请选择一个 free model</option>
        <option v-for="model in store.freeModels" :key="model" :value="model">{{ model }}</option>
      </select>
      <p v-if="probed && store.freeModels.length === 0" class="muted">当前没有可用的 free model。</p>

      <button class="btn btn-primary" type="button" data-test="save-opencode" :disabled="!canSave" @click="save">
        {{ store.saving ? '正在保存…' : '保存' }}
      </button>
    </div>
  </section>
</template>

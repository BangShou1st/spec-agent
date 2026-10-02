<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import SettingsCard from '@/shared/ui/SettingsCard.vue'
import ApiErrorBanner from '@/shared/ui/ApiErrorBanner.vue'
import Summary from '@/features/model-settings/components/ProviderSummaryItem.vue'
import { services, type Probe, type SearchSettings } from './api'
import { serviceError, serviceMessage } from './presentation'

const saved = ref<SearchSettings | null>(null)
const enabled = ref(false), key = ref(''), replacing = ref(false), confirming = ref(false)
const busy = ref('load'), error = ref<{ code: string } | null>(null), result = ref<Probe | null>(null), notice = ref('')
const state = computed(() => !saved.value?.configured ? '未配置' : saved.value.enabled ? '已启用' : '已停用')
const dirty = computed(() => enabled.value !== saved.value?.enabled || !!key.value)
watch([enabled, key], () => { result.value = null; notice.value = '' }, { flush: 'sync' })
async function action(name: string, work: () => Promise<void>) {
  if (busy.value && name !== 'load') return
  busy.value = name; error.value = null; notice.value = ''
  try { await work() } catch (e) { error.value = serviceError(e) } finally { busy.value = '' }
}
function hydrate(value: SearchSettings) { saved.value = value; enabled.value = value.enabled; key.value = ''; replacing.value = false; confirming.value = false }
async function load() { await action('load', async () => hydrate(await services.search())) }
async function save(importEnvironment = false) {
  await action('save', async () => {
    hydrate(await services.saveSearch({ enabled: enabled.value, revision: saved.value?.revision ?? 0, ...(key.value ? { apiKey: key.value } : {}), importEnvironment }))
    result.value = null; notice.value = importEnvironment ? '已显式导入数据库。后续配置以数据库为准。' : '已保存。新请求使用此配置；已有请求保持原配置。'
  })
}
async function test() {
  await action('test', async () => {
    result.value = await services.testSearch(key.value || undefined)
    if (!result.value.success) error.value = { code: result.value.code }
    if (result.value.target === 'SAVED') saved.value = await services.search()
  })
}
async function clear() {
  await action('clear', async () => { hydrate(await services.clearSearch(saved.value?.revision ?? 0)); result.value = null; notice.value = '已清除凭据并停用。启动环境中的旧密钥不会自动恢复。' })
}
onMounted(load)
</script>

<template>
  <section class="settings-page service-settings" aria-labelledby="search-title" :aria-busy="!!busy">
    <header class="settings-page__heading"><h2 id="search-title">联网搜索</h2><p class="settings-page__subtitle">为全局助手配置网页搜索与正文提取服务</p></header>
    <SettingsCard title="Tavily" description="按需搜索公开网页。保存设置不会发起联网测试。" card-test-id="search-card">
      <template #status><span class="settings-status" :class="saved?.configured && saved.enabled ? 'settings-status--configured' : 'settings-status--empty'">{{ busy === 'load' ? '正在加载…' : state }}</span></template>
      <template #error><ApiErrorBanner v-if="error" class="settings-error" :message="serviceMessage(error.code)" :code="error.code" :retry-label="error.code === 'SETTINGS_CHANGED' || !saved ? '重新加载' : undefined" :retrying="!!busy" @retry="load" /></template>
      <template #summary>
        <Summary label="凭据" :value="saved?.maskedKey ?? '尚未配置'" />
        <Summary label="配置来源" :value="saved?.source === 'DATABASE' ? '数据库' : saved?.configured ? '当前来自启动环境' : '尚未配置'" />
        <Summary label="当前状态" :value="state" />
        <Summary label="最近验证" :value="dirty ? '配置已修改，需重新测试' : saved?.testCode === 'OK' ? '已验证（仅代表测试时可用）' : saved?.testCode ? '上次验证未通过' : '尚未验证'" />
      </template>
      <form class="settings-form" @submit.prevent="save()">
        <label class="service-settings__toggle"><input v-model="enabled" type="checkbox" :disabled="!!busy || !saved" />启用联网搜索</label>
        <div class="settings-field">
          <label class="settings-field__label" for="search-key">API key</label>
          <button v-if="saved?.configured && !replacing" class="btn" type="button" :disabled="!!busy" @click="replacing = true">更换密钥</button>
          <input v-else id="search-key" v-model="key" class="settings-control" type="password" autocomplete="new-password" placeholder="输入 Tavily API key" :disabled="!!busy || !saved" aria-describedby="search-key-hint" />
          <span id="search-key-hint" class="settings-field__hint">留空保存会保留已有凭据。清除凭据请使用下方明确操作。</span>
        </div>
        <p class="settings-field__hint">测试会调用搜索服务，可能消耗额度。{{ key ? '本次测试使用未保存的密钥。' : '本次测试使用当前已保存或启动环境的密钥，即使服务已停用。' }}</p>
        <p v-if="busy === 'test'" role="status" class="service-settings__notice">正在测试搜索连接…</p>
        <p v-if="result?.success" role="status" class="service-settings__notice">连接测试成功。{{ result.target === 'DRAFT' ? '此密钥尚未保存。' : '仅代表本次测试可用。' }}</p>
        <p v-if="notice" role="status" class="service-settings__notice">{{ notice }}</p>
      </form>
      <template #footer>
        <div v-if="confirming" class="service-settings__confirmation" role="group" aria-label="确认清除搜索配置"><p>清除凭据并停用搜索？启动环境配置不会自动恢复。</p><button class="btn btn-danger settings-action" :disabled="!!busy" @click="clear">确认清除</button><button class="btn settings-action" :disabled="!!busy" @click="confirming = false">取消</button></div>
        <template v-else>
          <button class="btn service-settings__danger settings-action" :disabled="!!busy || !saved" @click="confirming = true">清除配置</button>
          <button v-if="saved?.environmentAvailable" class="btn settings-action" :disabled="!!busy" @click="save(true)">导入并保存</button>
          <button class="btn settings-action" :disabled="!!busy || !saved" @click="test">{{ busy === 'test' ? '正在测试…' : '测试连接' }}</button>
          <button class="btn btn-primary settings-action" :disabled="!!busy || !saved" @click="save()">{{ busy === 'save' ? '正在保存…' : '保存' }}</button>
        </template>
      </template>
    </SettingsCard>
  </section>
</template>

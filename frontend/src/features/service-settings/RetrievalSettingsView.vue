<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import SettingsCard from '@/shared/ui/SettingsCard.vue'
import ApiErrorBanner from '@/shared/ui/ApiErrorBanner.vue'
import Summary from '@/features/model-settings/components/ProviderSummaryItem.vue'
import { services, type EmbeddingConfig, type Probe, type RetrievalSettings } from './api'
import { serviceError, serviceMessage } from './presentation'

const saved = ref<RetrievalSettings | null>(null)
const form = reactive<EmbeddingConfig>({ provider: 'OLLAMA', baseUrl: 'http://127.0.0.1:11434', model: '', timeoutSeconds: 30, batchSize: 8, queryStrategy: 'raw-text.v1' })
const key = ref(''), replacing = ref(false), confirming = ref(false), selected = ref(true)
const busy = ref('load'), error = ref<{ code: string } | null>(null), indexError = ref<{ code: string } | null>(null)
const result = ref<Probe | null>(null), notice = ref('')
const job = computed(() => saved.value?.index.job)
const pendingCandidate = computed(() => !!saved.value?.service.candidateProfile && saved.value.service.candidateProfile !== saved.value.index.activeProfile)
const building = computed(() => job.value?.state === 'QUEUED' || job.value?.state === 'RUNNING')
const jobLabel = computed(() => job.value?.state === 'ACTIVE' && pendingCandidate.value ? '新配置待重建' : ({ QUEUED: '等待开始', RUNNING: '索引准备中', READY: '已准备好，等待启用', FAILED: '重建失败', ACTIVE: '已生效' })[job.value?.state ?? 'ACTIVE'])
const candidateState = computed(() => !saved.value?.service.config ? '未配置' : saved.value.service.candidateProfile ? '已验证' : '已保存，待验证')
const isDraft = computed(() => !!key.value || JSON.stringify(form) !== JSON.stringify(saved.value?.service.config))
let timer: ReturnType<typeof setInterval> | undefined
let polling = false, disposed = false
watch([form, key], () => { result.value = null; notice.value = '' }, { deep: true, flush: 'sync' })
watch(() => form.provider, (provider, old) => {
  if (provider === old) return
  key.value = ''; replacing.value = false
  form.baseUrl = provider === 'OLLAMA' ? 'http://127.0.0.1:11434' : ''
  form.model = ''; form.queryStrategy = 'raw-text.v1'
}, { flush: 'sync' })
watch(() => form.baseUrl, () => { key.value = '' }, { flush: 'sync' })
async function action(name: string, work: () => Promise<void>, index = false) {
  if (busy.value && name !== 'load') return
  busy.value = name; error.value = null; indexError.value = null; notice.value = ''
  try { await work() } catch (e) { if (index) indexError.value = serviceError(e); else error.value = serviceError(e) } finally { busy.value = '' }
}
function hydrate(value: RetrievalSettings) {
  saved.value = value
  if (value.service.config) Object.assign(form, value.service.config)
  key.value = ''; replacing.value = false; confirming.value = false
}
async function load() { await action('load', async () => hydrate(await services.retrieval())) }
function payload() { return { config: { ...form }, revision: saved.value?.service.revision ?? 0, ...(key.value ? { apiKey: key.value } : {}) } }
async function test() {
  await action('test', async () => {
    result.value = await services.testRetrieval(payload())
    if (!result.value.success) error.value = { code: result.value.code }
    if (result.value.target === 'SAVED') saved.value = await services.retrieval()
  })
}
async function save() {
  await action('save', async () => {
    const value = await services.saveRetrieval({ ...payload(), ...(result.value?.success && result.value.testId ? { testId: result.value.testId } : {}) })
    hydrate(value); result.value = null
    notice.value = value.service.candidateProfile ? '已保存并验证候选配置。当前检索保持不变，请重建所选帮助索引。' : '已保存候选配置。请测试连接，通过后再重建所选索引。'
  })
}
async function clear() {
  await action('clear', async () => { hydrate(await services.clearEmbeddingCredential(saved.value?.service.revision ?? 0)); notice.value = '已清除远程凭据。相关远程调用停止；已有索引数据保留。' })
}
async function rebuild() {
  await action('rebuild', async () => {
    if (!saved.value?.service.candidateProfile || !selected.value) return
    saved.value = await services.rebuild(saved.value.index.corpusId, saved.value.service.candidateProfile)
  }, true)
}
async function retry() { await action('retry', async () => { if (job.value) saved.value = await services.retry(job.value.id) }, true) }
async function activate() { await action('activate', async () => { if (job.value) saved.value = await services.activate(job.value.id) }, true) }
async function refresh() { await action('refresh', async () => { saved.value = await services.retrieval() }, true) }
onMounted(async () => {
  await load()
  if (disposed) return
  timer = setInterval(async () => {
    if (busy.value || polling || !building.value) return
    polling = true
    try { const next = await services.retrieval(); if (!disposed) saved.value = next } catch (e) { indexError.value = serviceError(e) } finally { polling = false }
  }, 1500)
})
onUnmounted(() => { disposed = true; if (timer) clearInterval(timer); key.value = '' })
</script>

<template>
  <section class="settings-page service-settings" aria-labelledby="retrieval-title" :aria-busy="!!busy">
    <header class="settings-page__heading"><h2 id="retrieval-title">知识检索</h2><p class="settings-page__subtitle">配置文本向量服务，并按语料准备新索引</p></header>
    <SettingsCard title="Embedding 服务" description="新配置先保存为候选；重建并启用索引后才用于所选语料。" card-test-id="embedding-card">
      <template #status><span class="settings-status" :class="saved?.service.candidateProfile ? 'settings-status--configured' : 'settings-status--empty'">{{ busy === 'load' ? '正在加载…' : candidateState }}</span></template>
      <template #error><ApiErrorBanner v-if="error" class="settings-error" :message="serviceMessage(error.code)" :code="error.code" :retry-label="error.code === 'SETTINGS_CHANGED' || !saved ? '重新加载' : undefined" :retrying="!!busy" @retry="load" /></template>
      <template #summary>
        <Summary label="已保存的候选模型" :value="saved?.service.config?.model ?? '尚未配置'" ellipsis />
        <Summary label="实际向量维度" :value="saved?.service.dimensions ? `${saved.service.dimensions} 维（真实接口检测）` : '测试后检测'" />
        <Summary label="凭据" :value="saved?.service.maskedKey ?? (saved?.service.config?.provider === 'OPENAI_COMPATIBLE' ? '未配置' : '本地 Ollama 通常无需密钥')" />
        <Summary label="最近验证" :value="saved?.service.testCode === 'OK' ? '已验证（仅代表测试时可用）' : saved?.service.testCode ? '上次验证未通过' : '尚未验证'" />
      </template>
      <form class="settings-form" @submit.prevent="save">
        <div class="settings-field"><label class="settings-field__label" for="embedding-provider">服务类型</label><select id="embedding-provider" v-model="form.provider" class="settings-control" :disabled="!!busy || !saved"><option value="OLLAMA">Ollama</option><option value="OPENAI_COMPATIBLE">OpenAI-compatible Embeddings API</option></select></div>
        <div class="settings-field"><label class="settings-field__label" for="embedding-url">服务地址</label><input id="embedding-url" v-model="form.baseUrl" class="settings-control" type="url" :disabled="!!busy || !saved" :placeholder="form.provider === 'OLLAMA' ? 'http://127.0.0.1:11434' : 'https://你的服务地址/v1'" aria-describedby="embedding-url-hint" /><span id="embedding-url-hint" class="settings-field__hint">{{ form.provider === 'OLLAMA' ? '请自行启动 Ollama 并安装模型。容器内 localhost 指向容器自身；远程地址需在启动配置中设为可信。' : '填写 API 基础地址，应用会调用其 /embeddings 接口。服务会接收所选语料文本，请使用你信任的供应商。' }}</span></div>
        <div class="settings-field"><label class="settings-field__label" for="embedding-model">Embedding 模型</label><input id="embedding-model" v-model="form.model" class="settings-control" :disabled="!!busy || !saved" placeholder="填写该服务实际提供的向量模型名称" required maxlength="240" /><span class="settings-field__hint">不会自动下载模型。聊天模型测试成功不代表可以生成文本向量。</span></div>
        <div v-if="form.provider === 'OPENAI_COMPATIBLE'" class="settings-field"><label class="settings-field__label" for="embedding-key">API key</label><button v-if="saved?.service.configured && !replacing && saved.service.config?.baseUrl === form.baseUrl && saved.service.config.provider === form.provider" class="btn" type="button" :disabled="!!busy" @click="replacing = true">更换密钥</button><input v-else id="embedding-key" v-model="key" class="settings-control" type="password" autocomplete="new-password" :disabled="!!busy || !saved" placeholder="输入此 Embeddings 服务的密钥" aria-describedby="embedding-key-hint" /><span id="embedding-key-hint" class="settings-field__hint">地址不变时留空保留已有凭据；更换地址不会携带旧凭据。本机兼容服务可无需鉴权。</span></div>
        <details class="service-settings__advanced"><summary>高级设置</summary><div class="settings-form">
          <div class="settings-field"><label class="settings-field__label" for="embedding-timeout">请求超时（秒）</label><input id="embedding-timeout" v-model.number="form.timeoutSeconds" class="settings-control" type="number" min="3" max="60" :disabled="!!busy" /></div>
          <div class="settings-field"><label class="settings-field__label" for="embedding-batch">每批文本数量</label><input id="embedding-batch" v-model.number="form.batchSize" class="settings-control" type="number" min="1" max="16" :disabled="!!busy" /></div>
          <div class="settings-field"><label class="settings-field__label" for="embedding-strategy">查询编码方式</label><select id="embedding-strategy" v-model="form.queryStrategy" class="settings-control" :disabled="!!busy"><option value="raw-text.v1">原始文本（通用）</option><option value="qwen-instruct.v1">Qwen 检索指令（仅适用模型）</option></select><span class="settings-field__hint">维度由实际返回决定，当前支持 1–4096 维，不截断向量。</span></div>
        </div></details>
        <p class="settings-field__hint">{{ isDraft ? '测试当前表单，不会自动保存。' : '测试已保存配置，不会自动切换索引。' }}API 测试可能消耗额度，仅发送隔离测试文本。</p>
        <p v-if="busy === 'test'" role="status" class="service-settings__notice">正在调用真实 Embedding 接口，检测文档、查询向量与实际维度…</p>
        <p v-if="result?.success" role="status" class="service-settings__notice">测试成功，实际返回 {{ result.dimensions }} 维。{{ result.target === 'DRAFT' ? '请保存候选配置。' : '可以重建所选索引。' }}</p>
        <p v-if="notice" role="status" class="service-settings__notice">{{ notice }}</p>
      </form>
      <template #footer>
        <div v-if="confirming" class="service-settings__confirmation" role="group" aria-label="确认清除 Embedding 凭据"><p>清除远程凭据后，使用它的远程向量请求将停止。索引数据会保留。</p><button class="btn btn-danger settings-action" :disabled="!!busy" @click="clear">确认清除</button><button class="btn settings-action" :disabled="!!busy" @click="confirming = false">取消</button></div>
        <template v-else><button v-if="saved?.service.configured" class="btn service-settings__danger settings-action" :disabled="!!busy" @click="confirming = true">清除凭据</button><button class="btn settings-action" :disabled="!!busy || !saved || !form.model || !form.baseUrl" @click="test">{{ busy === 'test' ? '正在测试…' : '测试连接' }}</button><button class="btn btn-primary settings-action" :disabled="!!busy || !saved || !form.model || !form.baseUrl" @click="save">{{ busy === 'save' ? '正在保存…' : '保存候选配置' }}</button></template>
      </template>
    </SettingsCard>
    <SettingsCard title="索引状态" description="仅重建选定的全局助手帮助语料。项目 Agent、项目索引和历史输入保持原有配置。" card-test-id="index-card">
      <template #status><span class="settings-status" :class="job?.state === 'FAILED' ? 'settings-status--error' : building || job?.state === 'READY' ? 'settings-status--warning' : saved?.index.readyEntries ? 'settings-status--active' : 'settings-status--empty'">{{ job ? jobLabel : saved?.index.readyEntries ? '当前索引可用' : '尚未建立索引' }}</span></template>
      <template #error><ApiErrorBanner v-if="indexError || job?.state === 'FAILED'" class="settings-error" :message="serviceMessage(indexError?.code ?? job?.error_code ?? 'UNKNOWN_ERROR')" :code="indexError?.code ?? job?.error_code ?? undefined" /></template>
      <template #summary>
        <Summary label="当前使用" :value="saved?.index.activeModel ?? '尚无生效配置'" ellipsis />
        <Summary label="当前索引" :value="saved?.index.readyEntries ? `${saved.index.readyEntries} 个可用片段 · ${saved.index.activeDimensions} 维` : '尚无可用向量，基础功能仍可使用'" />
        <Summary label="候选配置" :value="saved?.service.config?.model ?? '尚未保存'" ellipsis />
        <Summary label="待处理事项" :value="building ? '等待索引准备完成' : job?.state === 'READY' ? '显式启用新索引' : job?.state === 'FAILED' ? '修复服务后重试；仍使用原索引' : saved?.service.candidateProfile && saved.service.candidateProfile !== saved.index.activeProfile ? '新配置待建立索引' : saved?.service.config && !saved.service.candidateProfile ? '测试已保存配置' : '无需处理'" />
      </template>
      <label class="service-settings__toggle"><input v-model="selected" type="checkbox" :disabled="!!busy || building" />全局助手产品帮助</label>
      <p class="settings-field__hint">来自应用随包帮助文档，不包括你的项目内容。不会自动迁移全部项目。</p>
      <p v-if="building || job?.state === 'READY'" class="service-settings__notice" role="status">{{ jobLabel }} · 已处理 {{ job?.processed ?? 0 }}{{ job?.total ? ` / ${job.total}` : '' }} 个片段。关闭页面后任务仍由后端继续处理。</p>
      <p v-if="job?.state === 'FAILED'" class="settings-field__hint">重建失败，仍使用原索引。修复服务后可重试同一任务。</p>
      <p v-if="job?.state === 'ACTIVE'" class="service-settings__notice" role="status">{{ pendingCandidate ? '候选配置尚未启用，帮助检索仍使用当前索引。请重建并启用所选索引。' : saved?.service.config && !saved.service.candidateProfile ? '当前索引保持生效。候选配置需先通过测试，再判断是否需要重建。' : '新索引已启用，后续帮助检索使用新配置。' }}</p>
      <template #footer>
        <button class="btn settings-action" :disabled="!!busy" @click="refresh">刷新状态</button>
        <button v-if="job?.state === 'FAILED'" class="btn settings-action" :disabled="!!busy" @click="retry">{{ busy === 'retry' ? '正在重试…' : '重试失败任务' }}</button>
        <button v-if="job?.state === 'READY'" class="btn btn-primary settings-action" :disabled="!!busy" @click="activate">{{ busy === 'activate' ? '正在启用…' : '启用新索引' }}</button>
        <button v-else class="btn btn-primary settings-action" :disabled="!!busy || !selected || !saved?.service.candidateProfile || building" @click="rebuild">{{ building ? '索引准备中…' : '重建所选索引' }}</button>
      </template>
    </SettingsCard>
  </section>
</template>

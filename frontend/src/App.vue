<!--
  文件名:App.vue
  用途:应用根组件,渲染全局顶栏(应用名、项目/设置导航、全局助手入口)与当前路由页面出口,
       并根据是否处于工作台路由调整顶栏与主区域的样式。
-->
<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from 'vue'
import { RouterLink, RouterView } from 'vue-router'
import { useRoute } from 'vue-router'
import GlobalAssistantShell from '@/features/global-assistant/components/GlobalAssistantShell.vue'
import AccountBar from '@/features/access/AccountBar.vue'
import { accessSession, refreshSession } from '@/features/access/accessSession'
import { apiClient } from '@/shared/http/client'
import { useInputDraftStore } from '@/features/workspace/state/inputDraftStore'
import { useGlobalAssistantStore } from '@/features/global-assistant/state/globalAssistantStore'
import { useProjectStore } from '@/features/projects/state/projectStore'
import { bindPrivateSettingsIdentity } from '@/app/privateSettingsIdentity'

const route = useRoute()
const isWorkspaceRoute = computed(() => route.name === 'workspace')
const available = computed(() => !accessSession.loading && accessSession.identity?.authenticated && !!accessSession.identity.orgId)
const privateIdentity = computed(() => {
  const actor = accessSession.identity
  return actor?.authenticated && actor.userId && actor.orgId
    ? JSON.stringify([actor.userId, actor.orgId]) : null
})
const settingsIdentity = computed(() => privateIdentity.value === null ? null
  : JSON.stringify([privateIdentity.value, accessSession.identity?.orgRole,
    [...(accessSession.identity?.orgCapabilities ?? [])].sort()]))
const routeIdentity = computed(() => route.path.startsWith('/settings')
  ? settingsIdentity.value : privateIdentity.value)
// Bind persisted drafts before route rendering. Initial unknown identity must
// not erase drafts that belong to the same user after a page reload.
watch(privateIdentity, identity => {
  useInputDraftStore().bindIdentity(identity)
  useGlobalAssistantStore().bindIdentity(identity)
  useProjectStore().bindIdentity(identity)
}, { flush: 'sync' })
watch(settingsIdentity, identity => bindPrivateSettingsIdentity(identity), { flush: 'sync' })
const organizationName = ref('')
const creating = ref(false)
const createError = ref('')
async function createOrganization() {
  creating.value = true; createError.value = ''
  try { await apiClient.post('/organizations', { name: organizationName.value }); await refreshSession() }
  catch (e) { createError.value = e instanceof Error ? e.message : '创建失败，请重试。' }
  finally { creating.value = false }
}
function expired() { void refreshSession() }
onMounted(() => { void refreshSession(); window.addEventListener('spec-agent-session-expired', expired) })
onBeforeUnmount(() => window.removeEventListener('spec-agent-session-expired', expired))
</script>

<template>
  <header class="app-header" :class="{ 'app-header--workspace': isWorkspaceRoute }">
    <span class="app-name" aria-label="Spec Agent">Spec Agent</span>
    <nav v-if="available" class="app-nav" aria-label="全局导航">
      <RouterLink to="/projects">项目</RouterLink>
      <RouterLink to="/settings">设置</RouterLink>
    </nav>
    <span class="app-header__spacer" />
    <AccountBar />
    <GlobalAssistantShell v-if="available" :key="privateIdentity ?? undefined" />
  </header>
  <main class="page" :class="{ 'page--workspace': isWorkspaceRoute }">
    <section v-if="accessSession.loading" class="access-state" role="status">正在确认账户…</section>
    <section v-else-if="accessSession.error" class="access-state">
      <h1>暂时无法连接工作台</h1><p role="alert">{{ accessSession.error }}</p>
      <button class="btn btn--primary" @click="refreshSession">重试</button>
    </section>
    <section v-else-if="!accessSession.identity?.authenticated" class="access-state">
      <h1>登录 Spec Agent</h1><p>使用组织账户进入你的项目与需求工作台。</p>
      <a v-if="accessSession.loginUrl" class="btn btn--primary" :href="accessSession.loginUrl">使用组织账户登录</a>
      <p v-else role="alert">登录入口尚未配置，请联系部署管理员。</p>
    </section>
    <section v-else-if="!available" class="access-state">
      <h1>{{ accessSession.identity.memberships.length ? '选择一个组织' : '开始使用工作台' }}</h1>
      <p v-if="accessSession.identity.memberships.length">从顶栏选择组织，进入对应的项目。</p>
      <template v-else>
        <p>你还没有加入组织。可以创建自己的组织，或请管理员邀请你加入。</p>
        <p class="account-id">你的用户 ID：{{ accessSession.identity.userId }}</p>
        <form @submit.prevent="createOrganization">
          <label for="organization-name">组织名称</label>
          <input id="organization-name" v-model="organizationName" maxlength="120" required autocomplete="organization" />
          <button class="btn btn--primary" :disabled="creating || !organizationName.trim()">{{ creating ? '正在创建…' : '创建组织' }}</button>
        </form>
        <p v-if="createError" role="alert">{{ createError }}</p>
      </template>
    </section>
    <RouterView v-else :key="routeIdentity ?? undefined" />
  </main>
</template>

<style scoped>
.app-header__spacer { flex: 1; }
.app-header { flex-shrink: 0; }
.app-nav { display: flex; align-items: center; gap: 2px; }
.access-state { max-width: 560px; margin: 12vh auto; padding: 24px; color: var(--color-text); }
.access-state h1 { margin: 0 0 12px; font-size: 28px; line-height: 1.3; }
.access-state p { line-height: 1.6; margin: 0 0 20px; }
.access-state form { display: flex; flex-direction: column; align-items: flex-start; gap: 12px; }
.access-state input { width: 100%; min-height: 44px; padding: 8px 12px; border: 1px solid var(--color-border); border-radius: 6px; font: inherit; color: var(--color-text); background: var(--color-surface); }
.account-id { overflow-wrap: anywhere; color: var(--color-text-secondary); font-size: 14px; }
@media (max-width: 640px) {
  .app-header { flex-wrap: wrap; height: auto; padding-top: 8px; padding-bottom: 8px; }
  .page--workspace { height: 0; flex: 1 1 auto; }
  .access-state { margin-top: 6vh; padding: 16px; }
}
</style>

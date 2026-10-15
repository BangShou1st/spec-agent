<script setup lang="ts">
import { ref } from 'vue'
import { accessSession, logout, switchOrganization } from './accessSession'
const error = ref('')
const busy = ref(false)
function select(event: Event) { switchOrganization((event.target as HTMLSelectElement).value) }
async function exit() {
  busy.value = true; error.value = ''
  try { await logout() } catch (e) { error.value = e instanceof Error ? e.message : '退出失败' }
  finally { busy.value = false }
}
</script>
<template>
  <div v-if="accessSession.identity" class="account-bar">
    <label v-if="accessSession.identity.memberships.length" class="account-bar__organization">
      <span>组织</span>
      <select aria-label="当前组织" :value="accessSession.identity.orgId ?? ''" @change="select">
        <option disabled value="">请选择组织</option>
        <option v-for="org in accessSession.identity.memberships" :key="org.orgId" :value="org.orgId">{{ org.orgName }}</option>
      </select>
    </label>
    <details class="account-bar__account">
      <summary :title="accessSession.identity.displayName ?? ''">{{ accessSession.identity.displayName }}</summary>
      <div class="account-bar__details"><span>用户 ID</span><code>{{ accessSession.identity.userId }}</code></div>
    </details>
    <button v-if="accessSession.identity.mode === 'OIDC'" class="btn btn--ghost" :disabled="busy" @click="exit">{{ busy ? '正在退出…' : '退出' }}</button>
    <span v-if="error" role="alert">{{ error }}</span>
  </div>
</template>
<style scoped>
.account-bar { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; min-width: 0; }
.account-bar__organization { display: flex; align-items: center; gap: 6px; }
.account-bar__organization span { color: var(--color-text-secondary); font-size: 14px; white-space: nowrap; }
.account-bar select { max-width: 220px; min-height: 36px; font: inherit; color: var(--color-text); background: var(--color-surface); border: 1px solid var(--color-border); border-radius: 6px; padding: 4px 8px; }
.account-bar__name { max-width: 160px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 14px; }
.account-bar__account { position: relative; font-size: 14px; }
.account-bar__account summary { cursor: pointer; min-height: 36px; display: flex; align-items: center; gap: 6px; max-width: 160px; overflow-wrap: anywhere; }
.account-bar__details { position: absolute; top: 100%; right: 0; z-index: 50; width: min(320px, 80vw); padding: 16px; background: var(--color-surface); border: 1px solid var(--color-border); border-radius: 8px; box-shadow: var(--shadow-float); }
.account-bar__details span { display: block; margin-bottom: 8px; color: var(--color-text-secondary); }
.account-bar__details code { display: block; overflow-wrap: anywhere; user-select: all; }
@media (max-width: 640px) { .account-bar select { max-width: 140px; font-size: 16px; } .account-bar__account summary { max-width: 100px; } }
</style>

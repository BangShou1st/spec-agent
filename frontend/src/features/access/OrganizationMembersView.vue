<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { apiClient } from '@/shared/http/client'
import UiConfirmDialog from '@/shared/ui/UiConfirmDialog.vue'
import { accessSession } from './accessSession'
import { useOrganizationCapability } from './useOrganizationCapability'
import { useMemberRequestScope } from './useMemberRequestScope'
interface Member { userId: string; displayName: string; role: string; status: string }
const members = ref<Member[]>([])
const loading = ref(false)
const busy = ref<string | null>(null)
const error = ref('')
const userId = ref('')
const role = ref('MEMBER')
const pending = ref<Member | null>(null)
const canManage = useOrganizationCapability('ORG_MANAGE')
const canOwn = useOrganizationCapability('ORG_OWNER')
const base = computed(() => `/organizations/${encodeURIComponent(accessSession.identity?.orgId ?? '')}/members`)
let listRequest = 0
function reset() {
  listRequest++; members.value = []; loading.value = false; busy.value = null
  error.value = ''; userId.value = ''; role.value = 'MEMBER'; pending.value = null
}
const scope = useMemberRequestScope(() => accessSession.identity?.orgId ?? null,
  () => canManage.value, () => !canManage.value, reset)
const allowedRole = (value: string) => value === 'MEMBER' || value === 'ADMIN' || (value === 'OWNER' && canOwn.value)
const editable = (member: Member) => member.status === 'ACTIVE' && (member.role !== 'OWNER' || canOwn.value)
async function load() {
  const request = scope.capture()
  if (!request.current()) return
  const path = base.value
  const sequence = ++listRequest
  loading.value = true; error.value = ''
  try {
    const rows = await request.run(() => apiClient.get<Member[]>(path))
    if (request.current() && sequence === listRequest) members.value = rows
  } catch (e) {
    if (request.current() && sequence === listRequest) error.value = e instanceof Error ? e.message : '成员列表加载失败。'
  } finally { if (request.current() && sequence === listRequest) loading.value = false }
}
async function add() {
  const request = scope.capture()
  if (!request.current() || busy.value || !userId.value.trim() || !allowedRole(role.value)) return
  const path = base.value
  const body = { userId: userId.value.trim(), role: role.value }
  busy.value = 'add'; error.value = ''
  try {
    await request.run(() => apiClient.post(path, body))
    if (!request.current()) return
    userId.value = ''; await load()
  } catch (e) { if (request.current()) error.value = e instanceof Error ? e.message : '添加失败。' }
  finally { if (request.current()) busy.value = null }
}
async function change(member: Member, event: Event) {
  const input = event.target as HTMLSelectElement
  const selected = input.value
  const request = scope.capture()
  if (!request.current() || busy.value || !editable(member) || !allowedRole(selected)) { input.value = member.role; return }
  const path = `${base.value}/${encodeURIComponent(member.userId)}`
  busy.value = member.userId; error.value = ''
  try { await request.run(() => apiClient.patch(path, { role: selected })); if (request.current()) await load() }
  catch (e) { if (request.current()) { error.value = e instanceof Error ? e.message : '更新失败。'; input.value = member.role } }
  finally { if (request.current()) busy.value = null }
}
function askRemove(member: Member) {
  if (canManage.value && !busy.value && editable(member)) pending.value = member
}
async function remove() {
  const request = scope.capture()
  const member = pending.value
  if (!request.current() || !member || busy.value || !editable(member)) return
  const path = `${base.value}/${encodeURIComponent(member.userId)}`
  busy.value = member.userId; error.value = ''
  try { await request.run(() => apiClient.delete(path)); if (request.current()) { pending.value = null; await load() } }
  catch (e) { if (request.current()) error.value = e instanceof Error ? e.message : '移除失败。' }
  finally { if (request.current()) busy.value = null }
}
watch(() => [scope.key.value, canManage.value] as const, () => { void load() }, { immediate: true })
</script>
<template>
  <section class="members-page">
    <h2>组织成员</h2>
    <p>管理 {{ accessSession.identity?.orgName }} 的成员资格。项目内容仍由各项目角色决定。</p>
    <p v-if="!canManage">你没有组织成员管理权限。</p>
    <template v-else>
      <p v-if="error" role="alert">{{ error }}</p>
      <p v-if="loading" role="status">正在加载成员…</p>
      <ul v-else class="members-list">
        <li v-for="member in members" :key="member.userId">
          <div class="member-info"><strong>{{ member.displayName }}</strong><span>{{ member.status === 'ACTIVE' ? '有效成员' : '已移除' }}</span></div>
          <label><span class="sr-only">{{ member.displayName }} 的角色</span>
            <select :value="member.role" :disabled="!!busy || (!canOwn && member.role === 'OWNER') || member.status !== 'ACTIVE'" @change="change(member, $event)">
              <option value="MEMBER">成员</option><option value="ADMIN">管理员</option><option v-if="canOwn || member.role === 'OWNER'" value="OWNER">所有者</option>
            </select>
          </label>
          <button class="btn btn--ghost" :disabled="!!busy || (!canOwn && member.role === 'OWNER') || member.status !== 'ACTIVE'" @click="askRemove(member)">移除</button>
        </li>
      </ul>
      <form class="member-add" @submit.prevent="add">
        <h3>添加已注册用户</h3>
        <p>请对方先登录，并提供账户页面中的用户 ID。</p>
        <label for="member-user-id">用户 ID</label>
        <input id="member-user-id" v-model="userId" required autocomplete="off" placeholder="用户 UUID" />
        <label for="member-role">组织角色</label>
        <select id="member-role" v-model="role"><option value="MEMBER">成员</option><option value="ADMIN">管理员</option><option v-if="canOwn" value="OWNER">所有者</option></select>
        <button class="btn btn--primary" :disabled="!!busy || !userId.trim()">{{ busy === 'add' ? '正在添加…' : '添加成员' }}</button>
      </form>
    </template>
    <UiConfirmDialog :open="canManage && !!pending" title="移除组织成员"
      :description="`移除 ${pending?.displayName ?? ''} 后，对方将失去此组织的访问权限。`"
      confirm-label="移除" cancel-label="保留成员" :loading="!!busy" :error="error"
      @confirm="remove" @cancel="pending = null" />
  </section>
</template>
<style scoped>
.members-page { color: var(--color-text); }
.members-page h2 { margin: 0 0 8px; font-size: 22px; }
.members-page p { line-height: 1.6; color: var(--color-text-secondary); }
.members-list { list-style: none; padding: 0; margin: 24px 0; }
.members-list li { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; padding: 16px 0; border-bottom: 1px solid var(--color-border); }
.member-info { flex: 1; min-width: 140px; overflow-wrap: anywhere; }
.member-info span { display: block; margin-top: 4px; font-size: 14px; color: var(--color-text-secondary); }
.member-add { display: grid; gap: 10px; max-width: 480px; margin-top: 32px; }
.member-add h3, .member-add p { margin: 0 0 4px; }
.member-add button { justify-self: start; margin-top: 8px; }
.members-page input, .members-page select { min-height: 40px; border: 1px solid var(--color-border); border-radius: 6px; padding: 8px; font: inherit; color: var(--color-text); background: var(--color-surface); }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip-path: inset(50%); }
@media (max-width: 640px) { .members-page input, .members-page select { font-size: 16px; } }
</style>

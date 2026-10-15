<script setup lang="ts">
import { computed, ref, toRef, watch } from 'vue'
import { RouterLink } from 'vue-router'
import { apiClient } from '@/shared/http/client'
import UiConfirmDialog from '@/shared/ui/UiConfirmDialog.vue'
import { useProjectAccess } from './useProjectAccess'
import { useMemberRequestScope } from './useMemberRequestScope'
interface Member { userId: string; displayName: string; role: 'VIEWER' | 'EDITOR' | 'MANAGER' }
const props = defineProps<{ projectId: string }>()
const access = useProjectAccess(toRef(props, 'projectId'))
const canManage = computed(() => !access.loading.value && access.canManage.value)
const members = ref<Member[]>([])
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const userId = ref('')
const role = ref<Member['role']>('VIEWER')
const pending = ref<Member | null>(null)
const base = computed(() => `/projects/${encodeURIComponent(props.projectId)}/members`)
let listRequest = 0
function reset() {
  listRequest++; members.value = []; loading.value = false; busy.value = false
  error.value = ''; userId.value = ''; role.value = 'VIEWER'; pending.value = null
}
watch(canManage, allowed => { if (!allowed) { busy.value = false; loading.value = false; pending.value = null } }, { flush: 'sync' })
const scope = useMemberRequestScope(() => props.projectId, () => canManage.value,
  () => !access.loading.value && !canManage.value, reset)
async function load() {
  const request = scope.capture()
  if (!request.current()) return
  const path = base.value
  const sequence = ++listRequest
  loading.value = true; error.value = ''
  try {
    const rows = await request.run(() => apiClient.get<Member[]>(path))
    if (request.current() && sequence === listRequest) members.value = rows
  } catch (e) { if (request.current() && sequence === listRequest) error.value = e instanceof Error ? e.message : '无法读取项目成员。' }
  finally { if (request.current() && sequence === listRequest) loading.value = false }
}
async function grant(id: string, selected: Member['role']) {
  const request = scope.capture()
  if (!request.current() || busy.value || !id || !['VIEWER', 'EDITOR', 'MANAGER'].includes(selected)) return
  const path = `${base.value}/${encodeURIComponent(id)}`
  busy.value = true; error.value = ''
  try {
    await request.run(() => apiClient.put(path, { role: selected }))
    if (!request.current()) return
    userId.value = ''; await load()
  } catch (e) { if (request.current()) error.value = e instanceof Error ? e.message : '无法保存项目角色。' }
  finally { if (request.current()) busy.value = false }
}
async function change(member: Member, event: Event) {
  const request = scope.capture()
  const input = event.target as HTMLSelectElement
  await grant(member.userId, input.value as Member['role'])
  if (request.current()) input.value = members.value.find(m => m.userId === member.userId)?.role ?? member.role
}
function askRemove(member: Member) { if (canManage.value && !busy.value) pending.value = member }
async function remove() {
  const request = scope.capture()
  const member = pending.value
  if (!request.current() || !member || busy.value) return
  const path = `${base.value}/${encodeURIComponent(member.userId)}`
  busy.value = true; error.value = ''
  try { await request.run(() => apiClient.delete(path)); if (request.current()) { pending.value = null; await load() } }
  catch (e) { if (request.current()) error.value = e instanceof Error ? e.message : '无法移除项目成员。' }
  finally { if (request.current()) busy.value = false }
}
watch(() => [scope.key.value, canManage.value] as const, () => { void load() }, { immediate: true })
</script>
<template>
  <section class="project-members">
    <RouterLink :to="`/projects/${projectId}`">返回项目</RouterLink>
    <h1>项目成员</h1>
    <p>查看者可以阅读；编辑者可以修改画布；管理者还可以管理项目与成员。</p>
    <p v-if="access.loading.value" role="status">正在确认项目权限…</p>
    <p v-else-if="!canManage">你没有项目成员管理权限。</p>
    <template v-else>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="loading" role="status">正在加载成员…</p>
    <ul v-else>
      <li v-for="member in members" :key="member.userId">
        <strong>{{ member.displayName }}</strong>
        <label><span class="sr-only">{{ member.displayName }} 的项目角色</span>
          <select :value="member.role" :disabled="busy" @change="change(member, $event)">
            <option value="VIEWER">查看者</option><option value="EDITOR">编辑者</option><option value="MANAGER">管理者</option>
          </select>
        </label>
        <button class="btn btn--ghost" :disabled="busy" @click="askRemove(member)">移除</button>
      </li>
    </ul>
    <form @submit.prevent="grant(userId.trim(), role)">
      <h2>添加组织成员</h2>
      <p>对方须已加入当前组织。用户 ID 可在顶部账户菜单中找到。</p>
      <label for="project-member-user">用户 ID</label>
      <input id="project-member-user" v-model="userId" required autocomplete="off" />
      <label for="project-member-role">项目角色</label>
      <select id="project-member-role" v-model="role">
        <option value="VIEWER">查看者</option><option value="EDITOR">编辑者</option><option value="MANAGER">管理者</option>
      </select>
      <button class="btn btn--primary" :disabled="busy || loading || !userId.trim()">{{ busy ? '正在保存…' : '添加成员' }}</button>
    </form>
    <UiConfirmDialog :open="canManage && !!pending" title="移除项目成员" :description="`移除 ${pending?.displayName ?? ''} 后，对方将无法访问此项目。`"
      confirm-label="移除" cancel-label="保留成员" :loading="busy" :error="error" @confirm="remove" @cancel="pending = null" />
    </template>
  </section>
</template>
<style scoped>
.project-members { max-width: 760px; margin: 0 auto; padding: 28px 0 48px; color: var(--color-text); }
.project-members h1 { margin: 20px 0 8px; font-size: 24px; }
.project-members p { color: var(--color-text-secondary); line-height: 1.6; }
.project-members ul { list-style: none; padding: 0; margin: 24px 0; }
.project-members li { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; padding: 16px 0; border-bottom: 1px solid var(--color-border); }
.project-members strong { flex: 1; min-width: 140px; overflow-wrap: anywhere; }
.project-members form { display: grid; max-width: 480px; gap: 10px; margin-top: 32px; }
.project-members h2, .project-members form p { margin: 0 0 4px; }
.project-members h2 { font-size: 18px; }
.project-members input, .project-members select { min-height: 40px; font: inherit; padding: 8px; color: var(--color-text); background: var(--color-surface); border: 1px solid var(--color-border); border-radius: 6px; }
.project-members form button { justify-self: start; margin-top: 8px; }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip-path: inset(50%); }
@media (max-width: 640px) { .project-members input, .project-members select { font-size: 16px; } }
</style>

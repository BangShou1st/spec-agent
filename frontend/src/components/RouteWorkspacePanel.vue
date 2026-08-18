<script setup lang="ts">
import { watch } from 'vue'
import type { RouteResponse } from '@/api/types'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import RouteActionMenu from './RouteActionMenu.vue'
import RouteLineage from './RouteLineage.vue'

/**
 * Left panel: the route workspace. Routes are grouped for readability —
 * open + superseded routes are visible normally, archived/deleted routes sit
 * under a collapsed section but remain inspectable and recoverable. Backend
 * remains authoritative: lifecycle and active state come from read responses,
 * lineages are loaded lazily through the store from the backend route-lineage
 * read endpoint, and every action goes through the route command API.
 */
const props = defineProps<{
  projectTitle: string | null
  routes: RouteResponse[]
  selectedRouteId: string | null
  selectedNodeId: string | null
  commandPending: boolean
  pendingRouteCommand: string | null
}>()

const emit = defineEmits<{
  selectRoute: [routeId: string | null]
  selectNode: [nodeId: string]
  activate: [routeId: string]
  restore: [routeId: string]
  archive: [routeId: string]
  delete: [routeId: string]
}>()

const store = useWorkspaceStore()

// Lazy lineage loading for the currently selected route.
watch(
  () => store.selectedRouteId,
  (routeId) => {
    if (routeId) {
      void store.ensureRouteLineage(routeId)
    }
  },
  { immediate: true },
)

const lifecycleLabel: Record<string, string> = {
  open: 'Open',
  superseded: 'Superseded',
  archived: 'Archived',
  deleted: 'Deleted',
}

const primaryRoutes = (): RouteResponse[] =>
  props.routes.filter(
    (route) => route.lifecycleStatus === 'open' || route.lifecycleStatus === 'superseded',
  )

const hiddenRoutes = (): RouteResponse[] =>
  props.routes.filter(
    (route) => route.lifecycleStatus === 'archived' || route.lifecycleStatus === 'deleted',
  )

function pendingLabel(command: string | null): string {
  const labels: Record<string, string> = {
    activate: 'Activating…',
    restore: 'Restoring…',
    archive: 'Archiving…',
    delete: 'Deleting…',
    fork: 'Forking…',
    regenerate: 'Regenerating…',
  }
  return command ? labels[command] ?? 'Working…' : ''
}
</script>

<template>
  <div class="panel">
    <div class="panel-header">Route Workspace</div>
    <div class="panel-body">
      <p v-if="projectTitle" class="secondary" style="margin-top: 0">{{ projectTitle }}</p>

      <p v-if="routes.length === 0" class="muted">No routes yet.</p>

      <template v-else>
        <ul class="route-list">
          <li
            v-for="route in primaryRoutes()"
            :key="route.id"
            class="route-card"
            :class="{ selected: route.id === selectedRouteId }"
            data-test="route-card"
          >
            <div class="route-head" data-test="select-route" @click="emit('selectRoute', route.id)">
              <span class="route-label">{{ route.label ?? 'Route' }}</span>
              <span class="badge" :class="`badge-${route.lifecycleStatus}`" data-test="lifecycle-badge">
                {{ lifecycleLabel[route.lifecycleStatus] ?? route.lifecycleStatus }}
              </span>
              <span v-if="route.isActive" class="badge badge-active" data-test="active-route">
                Active
              </span>
            </div>
            <div class="meta-text">tip: {{ route.tipNodeId ?? '—' }}</div>
            <RouteActionMenu
              :route="route"
              :disabled="commandPending"
              @activate="emit('activate', $event)"
              @restore="emit('restore', $event)"
              @archive="emit('archive', $event)"
              @delete="emit('delete', $event)"
            />
            <RouteLineage
              v-if="route.id === selectedRouteId && store.routeLineages[route.id]"
              :nodes="store.routeLineages[route.id].nodes"
              :selected-node-id="selectedNodeId"
              @select-node="emit('selectNode', $event)"
            />
            <p
              v-if="route.id === selectedRouteId && !store.routeLineages[route.id]"
              class="muted"
              style="margin: 6px 0 0"
              data-test="lineage-loading"
            >
              Loading lineage…
            </p>
          </li>
        </ul>

        <details v-if="hiddenRoutes().length > 0" class="hidden-section" data-test="archived-deleted-section">
          <summary>
            Archived / Deleted ({{ hiddenRoutes().length }})
          </summary>
          <ul class="route-list">
            <li
              v-for="route in hiddenRoutes()"
              :key="route.id"
              class="route-card"
              :class="{ selected: route.id === selectedRouteId }"
              data-test="route-card"
            >
              <div class="route-head" data-test="select-route" @click="emit('selectRoute', route.id)">
                <span class="route-label">{{ route.label ?? 'Route' }}</span>
                <span class="badge" :class="`badge-${route.lifecycleStatus}`" data-test="lifecycle-badge">
                  {{ lifecycleLabel[route.lifecycleStatus] ?? route.lifecycleStatus }}
                </span>
                <span v-if="route.isActive" class="badge badge-active" data-test="active-route">
                  Active
                </span>
              </div>
              <div class="meta-text">tip: {{ route.tipNodeId ?? '—' }}</div>
              <RouteActionMenu
                :route="route"
                :disabled="commandPending"
                @activate="emit('activate', $event)"
                @restore="emit('restore', $event)"
                @archive="emit('archive', $event)"
                @delete="emit('delete', $event)"
              />
              <RouteLineage
                v-if="route.id === selectedRouteId && store.routeLineages[route.id]"
                :nodes="store.routeLineages[route.id].nodes"
                :selected-node-id="selectedNodeId"
                @select-node="emit('selectNode', $event)"
              />
            </li>
          </ul>
        </details>
      </template>

      <p v-if="commandPending" class="muted" style="margin-top: 8px" data-test="route-command-pending">
        {{ pendingLabel(pendingRouteCommand) }}
      </p>
    </div>
  </div>
</template>

<style scoped>
.route-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.route-card {
  padding: 8px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  margin-bottom: 8px;
  cursor: pointer;
}

.route-card.selected {
  border-color: var(--color-accent);
  background: var(--color-accent-soft);
}

.route-head {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.route-label {
  font-weight: 500;
}

.hidden-section {
  margin-top: 10px;
}

.hidden-section summary {
  cursor: pointer;
  color: var(--color-text-secondary);
  font-size: 13px;
}
</style>
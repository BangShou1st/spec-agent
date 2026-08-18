<script setup lang="ts">
import type { RouteResponse } from '@/api/types'

/**
 * Read-only route/project panel for Phase 7.1. Route state is made visible,
 * not editable: lifecycle badge, backend-derived active indicator, and tip
 * node id for diagnostics. `isActive` comes from Project.activeRouteId via
 * the backend; an OPEN route is not automatically the active route.
 */
defineProps<{
  projectTitle: string | null
  routes: RouteResponse[]
}>()

const lifecycleLabel: Record<string, string> = {
  open: 'Open',
  superseded: 'Superseded',
  archived: 'Archived',
  deleted: 'Deleted',
}
</script>

<template>
  <div class="panel">
    <div class="panel-header">Route / Project</div>
    <div class="panel-body">
      <p v-if="projectTitle" class="secondary" style="margin-top: 0">{{ projectTitle }}</p>

      <p v-if="routes.length === 0" class="muted">No routes yet.</p>

      <ul v-else style="list-style: none; margin: 0; padding: 0">
        <li
          v-for="route in routes"
          :key="route.id"
          style="
            padding: 8px 0;
            border-bottom: 1px solid var(--color-border);
          "
        >
          <div style="display: flex; align-items: center; gap: 8px">
            <span style="font-weight: 500">{{ route.label ?? 'Route' }}</span>
            <span class="badge" :class="`badge-${route.lifecycleStatus}`">
              {{ lifecycleLabel[route.lifecycleStatus] ?? route.lifecycleStatus }}
            </span>
            <span v-if="route.isActive" class="badge badge-active" data-test="active-route">
              Active
            </span>
          </div>
          <div class="meta-text" style="margin-top: 4px">
            tip: {{ route.tipNodeId ?? '—' }}
          </div>
        </li>
      </ul>
    </div>
  </div>
</template>

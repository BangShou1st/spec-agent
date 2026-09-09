<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, RouterLink } from 'vue-router'

const route = useRoute()
const connectionId = computed(() => (route.params.connectionId as string | undefined) ?? null)
</script>

<template>
  <section data-test="connections-settings">
    <h2>Connections</h2>
    <p data-test="connections-placeholder">Connections management lands after the visual direction is chosen.</p>
    <p v-if="connectionId" data-test="connection-detail-placeholder">Connection: {{ connectionId }}</p>
    <RouterLink v-if="connectionId" to="/settings/connections" data-test="back-to-connections">Back to Connections</RouterLink>
  </section>
</template>

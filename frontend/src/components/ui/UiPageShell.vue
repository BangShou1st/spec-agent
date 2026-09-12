<script setup lang="ts">
</script>
<template>
  <section class="ui-page" data-test="ui-page">
    <slot />
  </section>
</template>
<style scoped>
.ui-page { width: 100%; max-width: 880px; margin: 0 auto; padding: 28px 0 48px; }
@media (max-width: 1024px) { .ui-page { padding: 20px 12px 32px; } }
</style>

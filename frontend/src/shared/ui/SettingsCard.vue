<script setup lang="ts">
defineProps<{ title: string; description: string; cardTestId?: string; titleTestId?: string; summaryTestId?: string }>()
</script>

<template>
  <article class="settings-card" :data-test="cardTestId">
    <header class="settings-card__header">
      <div>
        <h3 :data-test="titleTestId">{{ title }}</h3>
        <p class="settings-card__description">{{ description }}</p>
      </div>
      <slot name="status" />
    </header>
    <slot name="error" />
    <section v-if="$slots.summary" class="settings-current-config" :data-test="summaryTestId">
      <slot name="summary" />
    </section>
    <slot />
    <footer v-if="$slots.footer" class="settings-card__footer"><slot name="footer" /></footer>
  </article>
</template>

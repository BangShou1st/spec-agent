<script setup lang="ts">
import { ref } from 'vue'

/**
 * Project creation form. Blank input is validated client-side for UX only;
 * the backend remains authoritative for title validation.
 */
const props = defineProps<{
  creating: boolean
}>()

const emit = defineEmits<{
  create: [title: string]
}>()

const title = ref('')
const touched = ref(false)

const blank = (): boolean => title.value.trim().length === 0

function submit(): void {
  touched.value = true
  if (blank() || props.creating) {
    return
  }
  emit('create', title.value.trim())
  title.value = ''
  touched.value = false
}
</script>

<template>
  <form class="create-form" @submit.prevent="submit">
    <input
      v-model="title"
      type="text"
      maxlength="255"
      placeholder="Project title"
      aria-label="Project title"
    />
    <button class="btn btn-primary" type="submit" :disabled="creating || blank()">
      {{ creating ? 'Creating…' : 'Create project' }}
    </button>
  </form>
  <p v-if="touched && blank()" class="muted">Enter a project title to create a project.</p>
</template>

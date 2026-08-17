<script setup lang="ts">
import { Check, X } from 'lucide-vue-next'

defineProps<{ message: string; type: 'success' | 'error' }>()
</script>

<template>
  <Transition name="toast"><div v-if="message" :class="['toast', type]" role="status" aria-live="polite"><Check v-if="type === 'success'" :size="18" /><X v-else :size="18" />{{ message }}</div></Transition>
</template>

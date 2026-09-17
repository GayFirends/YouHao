<script setup lang="ts">
import { nextTick, shallowRef, watch } from 'vue'
import { Check, X } from '@lucide/vue'

const props = defineProps<{ message: string; type: 'success' | 'error'; modalOpen: boolean }>()
const target = shallowRef<HTMLElement>(document.body)

watch([() => props.message, () => props.modalOpen], async () => {
  // Follow the native dialog's top layer, including when it closes or reopens.
  await nextTick()
  target.value = document.querySelector<HTMLElement>('dialog[open] .modal-footer') || document.body
}, { immediate: true, flush: 'post' })
</script>

<template>
  <Teleport :to="target">
    <Transition name="toast">
      <div v-if="message" :class="['toast', type]" :role="type === 'error' ? 'alert' : 'status'" :aria-live="type === 'error' ? 'assertive' : 'polite'">
        <Check v-if="type === 'success'" :size="18" /><X v-else :size="18" /><span>{{ message }}</span>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'

const props = withDefaults(defineProps<{
  labelledBy: string
  saving?: boolean
  size?: 'record' | 'small' | 'confirm'
  alert?: boolean
}>(), { saving: false, size: 'record', alert: false })
const emit = defineEmits<{ close: [] }>()
const dialog = ref<HTMLDialogElement | null>(null)

function requestClose() {
  if (!props.saving) emit('close')
}

function onBackdropClick(event: MouseEvent) {
  if (event.target !== dialog.value || !dialog.value) return
  const bounds = dialog.value.getBoundingClientRect()
  if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) requestClose()
}

onMounted(() => dialog.value?.showModal())
onBeforeUnmount(() => dialog.value?.close())
</script>

<template>
  <dialog ref="dialog" :class="['modal', size]" :aria-labelledby="labelledBy" :aria-busy="saving" :role="alert ? 'alertdialog' : undefined" @cancel.prevent="requestClose" @click="onBackdropClick">
    <slot />
  </dialog>
</template>

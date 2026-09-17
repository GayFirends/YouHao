<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, useId, watch } from 'vue'
import { Ellipsis, Pencil, Trash2 } from '@lucide/vue'

defineProps<{ label: string }>()
const emit = defineEmits<{ edit: []; remove: [] }>()
const root = ref<HTMLElement | null>(null)
const trigger = ref<HTMLButtonElement | null>(null)
const open = ref(false)
const menuId = 'record-actions-' + useId()

function close(restoreFocus = false) {
  open.value = false
  if (restoreFocus) trigger.value?.focus()
}
function onOutside(event: MouseEvent) {
  if (event.target instanceof Node && !root.value?.contains(event.target)) close()
}
async function toggle() {
  if (open.value) return close(true)
  open.value = true
  await nextTick()
  root.value?.querySelector<HTMLButtonElement>('[role="menuitem"]')?.focus()
}
function onKeydown(event: KeyboardEvent) {
  if (!open.value) return
  if (event.key === 'Escape') {
    event.preventDefault()
    event.stopPropagation()
    close(true)
  } else if (event.key === 'Tab') {
    close(true)
  } else if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
    event.preventDefault()
    const items = [...(root.value?.querySelectorAll<HTMLButtonElement>('[role="menuitem"]') || [])]
    const index = items.findIndex(item => item === document.activeElement)
    const next = event.key === 'Home' ? 0 : event.key === 'End' ? items.length - 1 : (index + (event.key === 'ArrowUp' ? -1 : 1) + items.length) % items.length
    items[next]?.focus()
  }
}
function choose(action: 'edit' | 'remove') {
  close(true)
  if (action === 'edit') emit('edit')
  else emit('remove')
}
watch(open, value => {
  if (value) document.addEventListener('click', onOutside)
  else document.removeEventListener('click', onOutside)
})
onBeforeUnmount(() => document.removeEventListener('click', onOutside))
</script>

<template>
  <div ref="root" class="record-actions" @keydown="onKeydown">
    <button ref="trigger" class="icon-button" :aria-label="label + '的操作'" aria-haspopup="menu" :aria-expanded="open" :aria-controls="menuId" @click="toggle"><Ellipsis :size="21" /></button>
    <div v-if="open" :id="menuId" class="record-menu" role="menu" :aria-label="label + '的操作'">
      <button role="menuitem" tabindex="-1" @click="choose('edit')"><Pencil :size="16" />编辑记录</button>
      <button role="menuitem" tabindex="-1" class="danger" @click="choose('remove')"><Trash2 :size="16" />删除记录</button>
    </div>
  </div>
</template>

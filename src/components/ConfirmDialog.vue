<script setup lang="ts">
import { Trash2 } from 'lucide-vue-next'
import ModalFrame from './ModalFrame.vue'

defineProps<{ target: { kind: 'vehicle' | 'record'; id: string; label: string }; saving: boolean }>()
const emit = defineEmits<{ close: []; confirm: [] }>()
</script>

<template>
  <ModalFrame labelled-by="confirm-title" size="confirm" alert :saving="saving" @close="emit('close')">
    <div class="confirm-content">
      <span class="danger-icon"><Trash2 :size="24" /></span>
      <h2 id="confirm-title">确认删除？</h2>
      <p>“{{ target.label }}”{{ target.kind === 'vehicle' ? '及其全部加油记录' : '' }}将从当前设备移除，并在下次同步时更新到其他设备。</p>
    </div>
    <footer class="modal-footer">
      <button class="button secondary" :disabled="saving" autofocus @click="emit('close')">取消</button>
      <button class="button danger-button" :disabled="saving" @click="emit('confirm')">{{ saving ? '正在删除…' : '确认删除' }}</button>
    </footer>
  </ModalFrame>
</template>

<script setup lang="ts">
import { Trash2 } from 'lucide-vue-next'

defineProps<{ target: { kind: 'vehicle' | 'record'; id: string; label: string }; saving: boolean }>()
const emit = defineEmits<{ close: []; confirm: [] }>()
</script>

<template>
  <div class="modal-backdrop">
    <div class="confirm-dialog" role="alertdialog" aria-modal="true" aria-labelledby="confirm-title">
      <span class="danger-icon"><Trash2 :size="23" /></span><h2 id="confirm-title">确认删除？</h2><p>“{{ target.label }}”{{ target.kind === 'vehicle' ? '及其全部加油记录' : '' }}将从当前设备移除，并在下次同步时更新到其他设备。</p>
      <div class="form-actions"><button class="button secondary" :disabled="saving" @click="emit('close')">取消</button><button class="button danger-button" :disabled="saving" @click="emit('confirm')">{{ saving ? '正在删除…' : '确认删除' }}</button></div>
    </div>
  </div>
</template>

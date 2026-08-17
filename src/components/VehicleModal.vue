<script setup lang="ts">
import { Check, RefreshCw, X } from 'lucide-vue-next'
import type { Vehicle } from '../types'

defineProps<{ vehicle: Vehicle | null; saving: boolean }>()
const emit = defineEmits<{ close: []; submit: [event: Event] }>()
</script>

<template>
  <div class="modal-backdrop" @mousedown.self="!saving && emit('close')">
    <form class="modal small" role="dialog" aria-modal="true" aria-labelledby="vehicle-modal-title" @submit.prevent="emit('submit', $event)">
      <div class="modal-head"><div><span class="eyebrow">车库信息</span><h2 id="vehicle-modal-title">{{ vehicle ? '编辑车辆' : '添加车辆' }}</h2></div><button type="button" class="icon-button" title="关闭" :disabled="saving" @click="emit('close')"><X :size="20" /></button></div>
      <label><span>车辆名称</span><input name="name" :value="vehicle?.name" placeholder="例如：家庭用车" required /></label>
      <div class="form-grid"><label><span>车牌号</span><input name="plate" :value="vehicle?.plate" placeholder="选填" /></label><label><span>燃油标号</span><select name="fuelType" :value="vehicle?.fuelType || '92#'"><option>92#</option><option>95#</option><option>98#</option><option>柴油</option></select></label></div>
      <label><span>起始里程（km）</span><input name="initialOdometer" type="number" min="0" step="0.1" :value="vehicle?.initialOdometer || 0" /></label>
      <div class="form-actions"><button type="button" class="button secondary" :disabled="saving" @click="emit('close')">取消</button><button class="button primary" :disabled="saving"><RefreshCw v-if="saving" :size="17" class="spin" /><Check v-else :size="17" />{{ saving ? '正在保存…' : '保存车辆' }}</button></div>
    </form>
  </div>
</template>

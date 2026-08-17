<script setup lang="ts">
import { Check, RefreshCw, X } from 'lucide-vue-next'
import type { FuelRecord, Vehicle } from '../types'

defineProps<{ record: FuelRecord | null; vehicles: Vehicle[]; selectedVehicleId: string; saving: boolean; today: string }>()
const emit = defineEmits<{ close: []; submit: [event: Event] }>()
</script>

<template>
  <div class="modal-backdrop" @mousedown.self="!saving && emit('close')">
    <form class="modal" role="dialog" aria-modal="true" aria-labelledby="record-modal-title" @submit.prevent="emit('submit', $event)">
      <div class="modal-head"><div><span class="eyebrow">{{ record ? '修改信息' : '新一次补给' }}</span><h2 id="record-modal-title">{{ record ? '编辑加油记录' : '记录加油' }}</h2></div><button type="button" class="icon-button" title="关闭" :disabled="saving" @click="emit('close')"><X :size="20" /></button></div>
      <div class="form-grid">
        <label><span>车辆</span><select name="vehicleId" :value="record?.vehicleId || selectedVehicleId" required><option v-for="vehicle in vehicles" :key="vehicle.id" :value="vehicle.id">{{ vehicle.name }}</option></select></label>
        <label><span>日期</span><input name="date" type="date" :value="record?.date || today" required /></label>
        <label><span>当前里程（km）</span><input name="odometer" type="number" min="0" step="0.1" :value="record?.odometer" placeholder="例如 32680" required /></label>
        <label><span>加油量（L）</span><input name="liters" type="number" min="0.01" step="0.01" :value="record?.liters" placeholder="例如 42.50" required /></label>
        <label><span>实付金额（元）</span><input name="amount" type="number" min="0" step="0.01" :value="record?.amount" placeholder="例如 328.00" required /></label>
        <label><span>加油站</span><input name="station" :value="record?.station" placeholder="选填" /></label>
      </div>
      <label><span>备注</span><textarea name="note" rows="3" :value="record?.note" placeholder="路况、油品或其他备注"></textarea></label>
      <label class="check-row"><input name="isFull" type="checkbox" :checked="record?.isFull ?? true" /><span><b>本次加满油箱</b><small>满箱记录用于准确计算区间油耗</small></span></label>
      <div class="form-actions"><button type="button" class="button secondary" :disabled="saving" @click="emit('close')">取消</button><button class="button primary" :disabled="saving"><RefreshCw v-if="saving" :size="17" class="spin" /><Check v-else :size="17" />{{ saving ? '正在保存…' : '保存记录' }}</button></div>
    </form>
  </div>
</template>

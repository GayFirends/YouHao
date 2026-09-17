<script setup lang="ts">
import { reactive } from 'vue'
import { Check, RefreshCw, X } from '@lucide/vue'
import ModalFrame from './ModalFrame.vue'
import type { Vehicle } from '../types'

const props = defineProps<{ vehicle: Vehicle | null; saving: boolean }>()
const emit = defineEmits<{ close: []; submit: [event: Event] }>()
const draft = reactive({
  name: props.vehicle?.name || '',
  plate: props.vehicle?.plate || '',
  fuelType: props.vehicle?.fuelType || '92#',
  initialOdometer: props.vehicle?.initialOdometer ?? 0,
})
</script>

<template>
  <ModalFrame labelled-by="vehicle-modal-title" size="small" :saving="saving" @close="emit('close')">
    <form @submit.prevent="emit('submit', $event)">
      <header class="modal-head">
        <div><span class="eyebrow">为每一段旅程，留一个位置</span><h2 id="vehicle-modal-title">{{ vehicle ? '编辑车辆' : '添加车辆' }}</h2></div>
        <button type="button" class="icon-button" aria-label="关闭车辆表单" :disabled="saving" @click="emit('close')"><X :size="21" /></button>
      </header>
      <fieldset class="modal-body" :disabled="saving">
        <legend class="visually-hidden">车辆信息</legend>
        <div class="form-grid">
          <label class="field-full"><span>车辆名称</span><input v-model="draft.name" name="name" placeholder="例如：日常通勤" required /></label>
          <label><span>车牌号 <small>选填</small></span><input v-model="draft.plate" name="plate" placeholder="填写车牌号" /></label>
          <label><span>燃油标号</span><select v-model="draft.fuelType" name="fuelType"><option>92#</option><option>95#</option><option>98#</option><option>柴油</option></select></label>
          <label class="field-full"><span>起始里程 <small>km</small></span><input v-model="draft.initialOdometer" name="initialOdometer" type="number" inputmode="decimal" min="0" step="0.1" /></label>
        </div>
      </fieldset>
      <footer class="modal-footer">
        <button type="button" class="button secondary" :disabled="saving" @click="emit('close')">取消</button>
        <button type="submit" class="button primary" :disabled="saving"><RefreshCw v-if="saving" :size="17" class="spin" /><Check v-else :size="17" />{{ saving ? '正在保存…' : '保存车辆' }}</button>
      </footer>
    </form>
  </ModalFrame>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { Check, ChevronDown, RefreshCw, ShieldCheck, Tag, X } from '@lucide/vue'
import ModalFrame from './ModalFrame.vue'
import type { FuelRecord, Vehicle } from '../types'
import { fuelPriceSummary } from '../services/fuel-calculations'
import { createRefuelEntry, effectivePumpAmount, resetPumpAmount, updateRefuelAmount, type AmountField } from '../services/refuel-entry'

const props = defineProps<{ record: FuelRecord | null; vehicles: Vehicle[]; selectedVehicleId: string; saving: boolean; today: string }>()
const emit = defineEmits<{ close: []; submit: [event: Event] }>()
const entry = reactive(createRefuelEntry(props.record))
const draft = reactive({
  date: props.record?.date || props.today,
  vehicleId: props.record?.vehicleId || props.selectedVehicleId,
  odometer: props.record?.odometer ?? '',
  isFull: props.record?.isFull ?? true,
  station: props.record?.station || '',
  note: props.record?.note || '',
})
const discountDetails = ref<HTMLDetailsElement | null>(null)
const prices = computed(() => fuelPriceSummary(Number(entry.liters), Number(entry.amount), effectivePumpAmount(entry)))
const money = (value: number) => '¥' + value.toFixed(2)

function onAmountInput(field: AmountField, event: Event) {
  const value = (event.target as HTMLInputElement).value
  updateRefuelAmount(entry, field, value === '' ? '' : Number(value))
}

function revealInvalidField(event: Event) {
  if (event.target instanceof HTMLElement && discountDetails.value?.contains(event.target)) discountDetails.value.open = true
}
</script>

<template>
  <ModalFrame labelled-by="record-modal-title" :saving="saving" @close="emit('close')">
    <form @submit.prevent="emit('submit', $event)" @invalid.capture="revealInvalidField">
      <header class="modal-head">
        <div><span class="eyebrow">{{ record ? '每一笔，都清楚可查' : '记下这一站的补给' }}</span><h2 id="record-modal-title">{{ record ? '编辑加油记录' : '记一笔加油' }}</h2></div>
        <button type="button" class="icon-button" aria-label="关闭加油表单" :disabled="saving" @click="emit('close')"><X :size="21" /></button>
      </header>
      <fieldset class="modal-body" :disabled="saving">
        <legend class="visually-hidden">加油信息</legend>
        <p class="form-intro">几项信息，就能记好一笔。</p>
        <div class="form-grid">
          <label><span>日期</span><input v-model="draft.date" name="date" type="date" required /></label>
          <label><span>车辆</span><select v-model="draft.vehicleId" name="vehicleId" required><option v-for="vehicle in vehicles" :key="vehicle.id" :value="vehicle.id">{{ vehicle.name }}</option></select></label>
          <label class="field-full"><span>当前里程 <small>km</small></span><input v-model="draft.odometer" name="odometer" type="number" inputmode="decimal" min="0" step="0.1" placeholder="例如 40000" required /></label>
          <label class="amount-field"><span>加油量 <small>L</small></span><input name="liters" type="number" inputmode="decimal" min="0.01" step="0.01" :value="entry.liters" placeholder="0.00" required @input="onAmountInput('liters', $event)" /></label>
          <label class="amount-field"><span>实付金额 <small>元</small></span><input name="amount" type="number" inputmode="decimal" min="0" step="0.01" :value="entry.amount" placeholder="0.00" required @input="onAmountInput('amount', $event)" /></label>
        </div>
        <label class="full-tank-toggle">
          <span><strong>本次加满油箱</strong><small>两次满箱之间，油耗更准确。</small></span>
          <span class="switch"><input v-model="draft.isFull" name="isFull" type="checkbox" /><span aria-hidden="true" /></span>
        </label>
        <input type="hidden" name="pumpAmount" :value="effectivePumpAmount(entry)" />
        <details ref="discountDetails" class="discount-details" :open="record ? record.pumpAmount !== record.amount : false">
          <summary><span><Tag :size="16" />有优惠？补充表显信息</span><ChevronDown :size="16" /></summary>
          <p class="field-hint">默认按实付金额记录。表显金额、单价与加油量填写任意两项，即可计算第三项。</p>
          <div class="form-grid">
            <label><span>加油机表显金额 <small>元</small></span><input type="number" inputmode="decimal" min="0" step="0.01" :value="entry.pumpAmount" placeholder="默认等于实付金额" @input="onAmountInput('pumpAmount', $event)" /></label>
            <label><span>加油机表显单价 <small>元/L</small></span><input type="number" inputmode="decimal" min="0" step="0.01" :value="entry.pumpPrice" placeholder="例如 8.10" @input="onAmountInput('pumpPrice', $event)" /></label>
          </div>
          <dl class="price-summary" aria-label="优惠信息">
            <div><dt>表显单价</dt><dd>{{ money(prices.pumpPricePerLiter) }}<small>/L</small></dd></div>
            <div><dt>实付单价</dt><dd>{{ money(prices.discountedPricePerLiter) }}<small>/L</small></dd></div>
            <div><dt>本次节省</dt><dd class="discount">{{ money(prices.discountAmount) }}</dd></div>
          </dl>
          <button type="button" class="text-button reset-price" @click="resetPumpAmount(entry)">按实付金额计算</button>
        </details>
        <div class="form-grid">
          <label class="field-full"><span>加油站 <small>选填</small></span><input v-model="draft.station" name="station" placeholder="记住这次补给的地方" /></label>
          <label class="field-full"><span>备注 <small>选填</small></span><textarea v-model="draft.note" name="note" rows="2" placeholder="路况、油品或一点随记" /></label>
        </div>
      </fieldset>
      <footer class="modal-footer">
        <span class="save-note"><ShieldCheck :size="14" />保存到本机</span>
        <button type="submit" class="button primary" :disabled="saving"><RefreshCw v-if="saving" :size="17" class="spin" /><Check v-else :size="17" />{{ saving ? '正在保存…' : '保存记录' }}</button>
      </footer>
    </form>
  </ModalFrame>
</template>

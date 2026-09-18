<script setup lang="ts">
import { Car, Check, Pencil, Plus, Trash2 } from '@lucide/vue'
import type { FuelRecord, Vehicle } from '../types'

defineProps<{ vehicles: Vehicle[]; records: FuelRecord[]; selectedVehicleId: string }>()
const emit = defineEmits<{
  add: []
  edit: [vehicle: Vehicle]
  remove: [vehicle: Vehicle]
  select: [vehicleId: string]
}>()
</script>

<template>
  <section class="page">
    <div class="page-title row">
      <div>
        <span class="eyebrow">车库</span>
        <h1>我的车辆</h1>
        <p>分别追踪每辆车的油耗表现</p>
      </div>
      <button class="button primary" @click="emit('add')"><Plus :size="18" />添加车辆</button>
    </div>
    <div class="vehicle-grid">
      <article
        v-for="vehicle in vehicles"
        :key="vehicle.id"
        :class="['vehicle-card', { selected: selectedVehicleId === vehicle.id }]"
        @click="emit('select', vehicle.id)"
      >
        <div class="vehicle-art">
          <Car :size="42" /><span>{{ vehicle.fuelType }}</span>
        </div>
        <div class="vehicle-card-body">
          <div>
            <h2>{{ vehicle.name }}</h2>
            <p>{{ vehicle.plate || '未设置车牌' }}</p>
          </div>
          <span v-if="selectedVehicleId === vehicle.id" class="selected-label"><Check :size="14" />当前车辆</span>
        </div>
        <dl>
          <div>
            <dt>加油次数</dt>
            <dd>{{ records.filter((record) => record.vehicleId === vehicle.id).length }}</dd>
          </div>
          <div>
            <dt>初始里程</dt>
            <dd>{{ vehicle.initialOdometer.toLocaleString() }} km</dd>
          </div>
        </dl>
        <div class="card-actions">
          <button class="button subtle" @click.stop="emit('edit', vehicle)"><Pencil :size="16" />编辑</button
          ><button class="icon-button danger" title="删除车辆" :disabled="vehicles.length === 1" @click.stop="emit('remove', vehicle)">
            <Trash2 :size="17" />
          </button>
        </div>
      </article>
    </div>
  </section>
</template>

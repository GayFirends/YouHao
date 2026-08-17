<script setup lang="ts">
import { computed, ref } from 'vue'
import { History, Pencil, Plus, Search, Trash2 } from 'lucide-vue-next'
import type { FuelRecord } from '../types'

const props = defineProps<{ records: FuelRecord[] }>()
const emit = defineEmits<{ add: []; edit: [record: FuelRecord]; remove: [record: FuelRecord] }>()
const search = ref('')
const filtered = computed(() => props.records.filter((item) => `${item.station} ${item.note} ${item.date}`.toLowerCase().includes(search.value.toLowerCase())))
const money = (value: number) => new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(value)
</script>

<template>
  <section class="page">
    <div class="page-title row"><div><span class="eyebrow">加油日志</span><h1>全部记录</h1><p>每一次补给，都清楚可查</p></div><button class="button primary" @click="emit('add')"><Plus :size="18" />新增记录</button></div>
    <div class="toolbar"><label class="search"><Search :size="18" /><input v-model="search" placeholder="搜索日期、加油站或备注" /></label><span>共 {{ filtered.length }} 条</span></div>
    <div v-if="filtered.length" class="record-table panel"><div class="table-row table-head"><span>日期 / 地点</span><span>里程</span><span>加油量</span><span>单价</span><span>金额</span><span></span></div><div v-for="item in filtered" :key="item.id" class="table-row"><span class="primary-cell"><b>{{ item.date }}</b><small>{{ item.station || '未填写加油站' }}<i v-if="item.isFull">满箱</i></small></span><span data-label="里程">{{ item.odometer.toLocaleString() }} km</span><span data-label="加油量">{{ item.liters.toFixed(2) }} L</span><span data-label="单价">¥{{ item.pricePerLiter.toFixed(2) }}</span><strong data-label="金额">{{ money(item.amount) }}</strong><span class="row-actions"><button class="icon-button" title="编辑" @click="emit('edit', item)"><Pencil :size="16" /></button><button class="icon-button danger" title="删除" @click="emit('remove', item)"><Trash2 :size="16" /></button></span></div></div>
    <div v-else class="panel empty"><History :size="40" /><h2>{{ search ? '没有匹配的记录' : '开始记录第一笔加油' }}</h2><p>{{ search ? '换个关键词试试' : '里程和金额会自动汇总到概览中' }}</p><button v-if="!search" class="button primary" @click="emit('add')"><Plus :size="18" />新增记录</button></div>
  </section>
</template>

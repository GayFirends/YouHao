<script setup lang="ts">
import { computed } from 'vue'
import { BarChart3, Car, Droplets, Fuel, Gauge, Plus } from 'lucide-vue-next'
import { calculateAverageConsumption, calculateConsumptionIntervals } from '../services/fuel-calculations'
import { localMonthKey } from '../services/local-date'
import type { FuelRecord, Vehicle } from '../types'

const props = defineProps<{ vehicle?: Vehicle; records: FuelRecord[] }>()
const emit = defineEmits<{ add: []; edit: [record: FuelRecord]; records: [] }>()
const intervals = computed(() => calculateConsumptionIntervals(props.records))
const average = computed(() => calculateAverageConsumption(intervals.value))
const totalCost = computed(() => props.records.reduce((sum, item) => sum + item.amount, 0))
const totalDistance = computed(() => props.records.length ? Math.max(0, Math.max(...props.records.map((item) => item.odometer)) - (props.vehicle?.initialOdometer || Math.min(...props.records.map((item) => item.odometer)))) : 0)
const monthCost = computed(() => props.records.filter((item) => item.date.startsWith(localMonthKey())).reduce((sum, item) => sum + item.amount, 0))
const recentChart = computed(() => intervals.value.slice(-7))
const chartMax = computed(() => Math.max(12, ...recentChart.value.map((item) => item.consumption)))
const money = (value: number) => new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(value)
const date = (value: string) => new Intl.DateTimeFormat('zh-CN', { month: 'short', day: 'numeric' }).format(new Date(`${value}T00:00:00`))
</script>

<template>
  <section class="page overview">
    <div class="page-title"><div><span class="eyebrow">行驶概览</span><h1>你好，今天也一路顺风</h1><p>{{ records.length ? `已为 ${vehicle?.name} 记录 ${records.length} 次加油` : '从第一笔加油记录开始了解你的爱车' }}</p></div><button class="button primary mobile-add" @click="emit('add')"><Plus :size="18" />记录加油</button></div>
    <div class="metric-grid">
      <article class="metric featured"><div class="metric-icon"><Gauge :size="20" /></div><span>平均油耗</span><strong>{{ average ? average.toFixed(1) : '--' }}<small>L/100km</small></strong><p>{{ intervals.length ? `基于 ${intervals.length} 个满箱区间` : '至少需要两次满箱记录' }}</p></article>
      <article class="metric"><div class="metric-icon amber"><Droplets :size="20" /></div><span>本月油费</span><strong>{{ money(monthCost) }}</strong><p>累计 {{ money(totalCost) }}</p></article>
      <article class="metric"><div class="metric-icon dark"><Car :size="20" /></div><span>记录里程</span><strong>{{ totalDistance.toLocaleString() }}<small>km</small></strong><p>当前 {{ records[0]?.odometer.toLocaleString() || '--' }} km</p></article>
    </div>
    <div class="dashboard-grid">
      <article class="panel chart-panel"><div class="panel-head"><div><h2>油耗趋势</h2><p>最近 {{ recentChart.length }} 个满箱区间</p></div><span class="unit">L/100km</span></div><div v-if="recentChart.length" class="chart"><div v-for="item in recentChart" :key="item.record.id" class="bar-column"><span>{{ item.consumption.toFixed(1) }}</span><div class="bar" :style="{ height: `${Math.max(8, item.consumption / chartMax * 100)}%` }" /><small>{{ date(item.record.date) }}</small></div></div><div v-else class="empty compact"><BarChart3 :size="34" /><strong>趋势正在等待数据</strong><span>连续记录两次满箱加油后显示</span></div></article>
      <article class="panel recent-panel"><div class="panel-head"><div><h2>最近加油</h2><p>最新的行程补给</p></div><button class="text-button" @click="emit('records')">查看全部</button></div><div v-if="records.length" class="recent-list"><button v-for="item in records.slice(0, 4)" :key="item.id" @click="emit('edit', item)"><span class="date-badge"><b>{{ date(item.date).split('月')[1]?.replace('日','') }}</b><small>{{ date(item.date).split('月')[0] }}月</small></span><span class="recent-info"><strong>{{ item.station || '加油记录' }}</strong><small>{{ item.liters.toFixed(2) }} L · {{ item.odometer.toLocaleString() }} km</small></span><b>{{ money(item.amount) }}</b></button></div><div v-else class="empty compact"><Fuel :size="34" /><strong>还没有记录</strong><span>添加第一次加油，数据会出现在这里</span></div></article>
    </div>
  </section>
</template>

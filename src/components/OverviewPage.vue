<script setup lang="ts">
import { computed, ref } from 'vue'
import { BarChart3, ChevronRight, Fuel, Gauge, Leaf, Plus, Route, ShieldCheck, Wallet } from 'lucide-vue-next'
import { calculateAverageConsumption, calculateConsumptionIntervals } from '../services/fuel-calculations'
import { localMonthKey } from '../services/local-date'
import type { FuelRecord, Vehicle } from '../types'

const props = defineProps<{ vehicle?: Vehicle; records: FuelRecord[] }>()
const emit = defineEmits<{ add: []; edit: [record: FuelRecord]; records: [] }>()
const intervals = computed(() => calculateConsumptionIntervals(props.records))
const average = computed(() => calculateAverageConsumption(intervals.value))
const measuredDistance = computed(() => intervals.value.reduce((sum, item) => sum + item.distance, 0))
const totalCost = computed(() => props.records.reduce((sum, item) => sum + item.amount, 0))
const currentOdometer = computed(() => props.records.length ? Math.max(...props.records.map(item => item.odometer)) : null)
const totalDistance = computed(() => currentOdometer.value === null ? 0 : Math.max(0, currentOdometer.value - (props.vehicle?.initialOdometer ?? Math.min(...props.records.map(item => item.odometer)))))
const monthRecords = computed(() => props.records.filter(item => item.date.startsWith(localMonthKey())))
const monthCost = computed(() => monthRecords.value.reduce((sum, item) => sum + item.amount, 0))
const recentRecords = computed(() => [...props.records].sort((a, b) => b.date.localeCompare(a.date) || b.odometer - a.odometer).slice(0, 4))
const recentChart = computed(() => intervals.value.slice(-7))
const selectedId = ref('')
const selectedInterval = computed(() => recentChart.value.find(item => item.record.id === selectedId.value) || recentChart.value[recentChart.value.length - 1])
const chartBounds = computed(() => {
  const values = recentChart.value.map(item => item.consumption)
  const min = Math.max(0, Math.floor(Math.min(...values, 7) - 0.7))
  const max = Math.max(min + 2, Math.ceil(Math.max(...values, 8) + 0.6))
  return { min, max }
})
const chartY = (value: number) => 150 - (value - chartBounds.value.min) / (chartBounds.value.max - chartBounds.value.min) * 124
const chartTicks = computed(() => [chartBounds.value.min, (chartBounds.value.min + chartBounds.value.max) / 2, chartBounds.value.max])
const chartPoints = computed(() => recentChart.value.map((item, index, list) => ({
  ...item,
  x: list.length === 1 ? 212 : 38 + index * 347 / (list.length - 1),
  y: chartY(item.consumption),
})))
const chartPath = computed(() => chartPoints.value.map((point, index) => (index ? 'L' : 'M') + point.x + ',' + point.y).join(' '))
const areaPath = computed(() => chartPoints.value.length ? chartPath.value + ' L' + chartPoints.value[chartPoints.value.length - 1].x + ',151 L' + chartPoints.value[0].x + ',151 Z' : '')
const money = (value: number) => new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(value)
const number = (value: number) => new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 1 }).format(value)
const date = (value: string) => new Intl.DateTimeFormat('zh-CN', { month: 'short', day: 'numeric' }).format(new Date(value + 'T00:00:00'))
const monthTitle = new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'long' }).format(new Date())
const todayTitle = new Intl.DateTimeFormat('zh-CN', { month: 'short', day: 'numeric', weekday: 'long' }).format(new Date())
</script>

<template>
  <section class="page overview">
    <div class="page-title row">
      <div><span class="eyebrow">{{ monthTitle }} · {{ vehicle?.name || '行驶概览' }}</span><h1>每一程，都心中有数。</h1></div>
      <span class="date-label">{{ todayTitle }}</span>
    </div>

    <div class="metric-grid">
      <article class="metric featured">
        <div class="metric-heading"><span>平均油耗</span><Gauge :size="24" /></div>
        <strong class="metric-value">{{ average ? average.toFixed(1) : '—' }}<small>L / 100 km</small></strong>
        <p><Leaf :size="14" /><span>{{ intervals.length ? '基于 ' + intervals.length + ' 个满箱区间 · ' + number(measuredDistance) + ' km' : '两次满箱后，计算更准确' }}</span></p>
      </article>
      <article class="metric">
        <div class="metric-heading"><span>本月油费</span><Wallet :size="19" /></div>
        <strong class="metric-value">{{ money(monthCost) }}</strong>
        <p>本月 {{ monthRecords.length }} 次 · 累计 {{ money(totalCost) }}</p>
      </article>
      <article class="metric">
        <div class="metric-heading"><span>记录里程</span><Route :size="19" /></div>
        <strong class="metric-value">{{ number(totalDistance) }}<small>km</small></strong>
        <p>{{ currentOdometer !== null ? '当前里程 ' + number(currentOdometer) + ' km' : '从第一笔开始累计' }}</p>
      </article>
    </div>

    <div class="dashboard-grid">
      <article class="panel chart-panel">
        <div class="panel-head"><div><h2>油耗趋势</h2><p>最近 {{ recentChart.length }} 个满箱区间</p></div><span class="unit">L / 100 km</span></div>
        <template v-if="chartPoints.length">
          <svg class="consumption-chart" viewBox="0 0 410 192" role="group" aria-label="满箱区间油耗趋势，选择数据点查看详情">
            <defs><linearGradient id="consumption-area" x1="0" x2="0" y1="0" y2="1"><stop offset="0" stop-color="var(--accent)" stop-opacity=".17" /><stop offset="1" stop-color="var(--accent)" stop-opacity=".01" /></linearGradient></defs>
            <g v-for="tick in chartTicks" :key="tick">
              <line class="chart-gridline" x1="35" x2="390" :y1="chartY(tick)" :y2="chartY(tick)" />
              <text class="chart-axis" x="3" :y="chartY(tick) + 4">{{ number(tick) }}</text>
            </g>
            <path :d="areaPath" fill="url(#consumption-area)" />
            <path class="chart-line" :d="chartPath" />
            <g v-for="(point, index) in chartPoints" :key="point.record.id">
              <g
                class="chart-point"
                :class="{ selected: selectedInterval?.record.id === point.record.id }"
                role="button"
                tabindex="0"
                :aria-label="date(point.record.date) + '，油耗 ' + point.consumption.toFixed(1) + ' 升每百公里'"
                :aria-pressed="selectedInterval?.record.id === point.record.id"
                @pointerenter="selectedId = point.record.id"
                @focus="selectedId = point.record.id"
                @click="selectedId = point.record.id"
                @keydown.enter.prevent="selectedId = point.record.id"
                @keydown.space.prevent="selectedId = point.record.id"
              >
                <circle :cx="point.x" :cy="point.y" r="15" fill="transparent" />
                <circle class="chart-halo" :cx="point.x" :cy="point.y" r="11" />
                <circle class="chart-dot" :cx="point.x" :cy="point.y" r="3.6" />
              </g>
              <text v-if="index === 0 || index === chartPoints.length - 1 || (chartPoints.length > 3 && index === Math.floor(chartPoints.length / 2))" class="chart-axis chart-date" :x="point.x" y="180" text-anchor="middle">{{ date(point.record.date) }}</text>
            </g>
          </svg>
          <div v-if="selectedInterval" class="chart-details" aria-live="polite">
            <span>{{ date(selectedInterval.record.date) }} · {{ number(selectedInterval.distance) }} km · {{ number(selectedInterval.liters) }} L</span>
            <strong>{{ selectedInterval.consumption.toFixed(1) }} <small>L/100km</small></strong>
          </div>
        </template>
        <div v-else class="empty compact"><span class="empty-icon"><BarChart3 :size="28" /></span><strong>从两次满箱开始</strong><p>连续记录两次满箱加油，<br />这里就会出现你的油耗趋势。</p></div>
      </article>

      <article class="panel recent-panel">
        <div class="panel-head"><h2>最近加油</h2><button class="text-button" @click="emit('records')">全部记录<ChevronRight :size="15" /></button></div>
        <div v-if="recentRecords.length" class="recent-list">
          <button v-for="item in recentRecords" :key="item.id" class="recent-record" :aria-label="'编辑 ' + item.date + ' 的加油记录'" @click="emit('edit', item)">
            <span class="receipt-icon"><Fuel :size="20" /></span>
            <span class="recent-info"><strong>{{ item.station || '加油记录' }}</strong><span>{{ date(item.date) }} · {{ number(item.liters) }} L <i v-if="item.isFull" class="full-tag">满箱</i></span></span>
            <span class="receipt-amount"><b>{{ money(item.amount) }}</b><small>{{ item.pumpAmount > item.amount ? '省 ' + money(item.pumpAmount - item.amount) : '实付金额' }}</small></span>
          </button>
        </div>
        <div v-else class="empty compact"><span class="empty-icon"><Fuel :size="28" /></span><strong>账本的第一页，留给你</strong><p>记录第一次加油，<br />让花费与油耗清晰起来。</p><button class="button primary" @click="emit('add')"><Plus :size="17" />记录第一次加油</button></div>
      </article>
    </div>
    <p class="local-note"><ShieldCheck :size="14" />记录保存在本机，安心出发。</p>
  </section>
</template>

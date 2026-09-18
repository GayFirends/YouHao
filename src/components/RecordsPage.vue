<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { History, Pencil, Plus, Search, Trash2, X } from '@lucide/vue'
import RecordActions from './RecordActions.vue'
import type { FuelRecord } from '../types'

const props = defineProps<{ records: FuelRecord[]; vehicleId?: string }>()
const emit = defineEmits<{ add: []; edit: [record: FuelRecord]; remove: [record: FuelRecord] }>()
const search = ref('')
const selectedMonth = ref('')
const visibleCount = ref(50)
const months = computed(() => [...new Set(props.records.map((item) => item.date.slice(0, 7)))].sort((a, b) => b.localeCompare(a)))
const filtered = computed(() => {
  const query = search.value.trim().toLocaleLowerCase()
  return props.records
    .filter(
      (item) =>
        (!selectedMonth.value || item.date.startsWith(selectedMonth.value)) &&
        (item.station + ' ' + item.note + ' ' + item.date).toLocaleLowerCase().includes(query),
    )
    .sort((a, b) => b.date.localeCompare(a.date) || b.odometer - a.odometer)
})
const visible = computed(() => filtered.value.slice(0, visibleCount.value))
const totalPaid = computed(() => filtered.value.reduce((sum, item) => sum + item.amount, 0))
const totalSaved = computed(() => filtered.value.reduce((sum, item) => sum + Math.max(0, item.pumpAmount - item.amount), 0))
const groups = computed(() => {
  const result = new Map<string, FuelRecord[]>()
  for (const item of visible.value) {
    const month = item.date.slice(0, 7)
    if (!result.has(month)) result.set(month, [])
    result.get(month)!.push(item)
  }
  return [...result].map(([month, records]) => ({ month, records, total: records.reduce((sum, item) => sum + item.amount, 0) }))
})
const money = (value: number) => new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(value)
const monthLabel = (value: string) => Number(value.slice(0, 4)) + ' 年 ' + Number(value.slice(5, 7)) + ' 月'
const hasFilter = computed(() => !!search.value.trim() || !!selectedMonth.value)

function clearFilters() {
  search.value = ''
  selectedMonth.value = ''
  visibleCount.value = 50
}
watch([() => props.vehicleId, search, selectedMonth], () => {
  visibleCount.value = 50
})
</script>

<template>
  <section class="page records-page">
    <div class="page-title">
      <span class="eyebrow">把每一次补给，记在这里</span>
      <h1>加油账本</h1>
    </div>
    <div class="toolbar">
      <label class="search"
        ><Search :size="19" /><input v-model="search" type="search" aria-label="搜索加油记录" placeholder="搜索加油站、日期或备注"
      /></label>
      <label class="month-filter"
        ><span class="visually-hidden">按月份筛选</span
        ><select v-model="selectedMonth">
          <option value="">全部月份</option>
          <option v-for="month in months" :key="month" :value="month">{{ monthLabel(month) }}</option>
        </select></label
      >
    </div>
    <div class="records-summary">
      <div>
        <span>{{ filtered.length }} 笔记录 · 实付合计</span><small v-if="totalSaved > 0">累计优惠 {{ money(totalSaved) }}</small>
      </div>
      <strong>{{ money(totalPaid) }}</strong>
    </div>

    <section v-for="group in groups" :key="group.month" class="record-month" :aria-label="monthLabel(group.month) + '加油记录'">
      <div class="month-heading">
        <h2>{{ monthLabel(group.month) }}</h2>
        <span>{{ money(group.total) }} · {{ group.records.length }} 笔</span>
      </div>
      <div class="record-table-wrap panel">
        <table class="record-table">
          <caption class="visually-hidden">
            {{
              monthLabel(group.month)
            }}的加油明细
          </caption>
          <thead>
            <tr>
              <th scope="col">日期 / 加油站</th>
              <th scope="col">里程</th>
              <th scope="col">加油量</th>
              <th scope="col">实付单价</th>
              <th scope="col">实付金额</th>
              <th scope="col"><span class="visually-hidden">操作</span></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in group.records" :key="item.id">
              <td>
                <button class="record-cell-link" :aria-label="'编辑 ' + item.date + ' 的加油记录'" @click="emit('edit', item)">
                  <b>{{ item.date }} <i v-if="item.isFull" class="full-tag">满箱</i></b
                  ><span :title="item.station || '未填写加油站'">{{ item.station || '未填写加油站' }}</span
                  ><small v-if="item.note" :title="item.note">{{ item.note }}</small>
                </button>
              </td>
              <td>{{ item.odometer.toLocaleString() }} <small>km</small></td>
              <td>{{ item.liters.toFixed(2) }} <small>L</small></td>
              <td>{{ money(item.pricePerLiter) }}</td>
              <td class="record-paid">
                <strong>{{ money(item.amount) }}</strong
                ><small v-if="item.pumpAmount > item.amount">省 {{ money(item.pumpAmount - item.amount) }}</small>
              </td>
              <td>
                <div class="row-actions">
                  <button class="icon-button" :aria-label="'编辑 ' + item.date + ' 的加油记录'" @click="emit('edit', item)">
                    <Pencil :size="17" /></button
                  ><button class="icon-button danger" :aria-label="'删除 ' + item.date + ' 的加油记录'" @click="emit('remove', item)">
                    <Trash2 :size="17" />
                  </button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <ul class="record-cards">
        <li v-for="item in group.records" :key="item.id" class="record-card">
          <button class="record-card-main" :aria-label="'编辑 ' + item.date + ' 的加油记录'" @click="emit('edit', item)">
            <span class="date-badge"
              ><b>{{ Number(item.date.slice(8, 10)) }}</b
              ><small>{{ Number(item.date.slice(5, 7)) }} 月</small></span
            >
            <span class="record-card-info"
              ><strong>{{ item.station || '加油记录' }}</strong
              ><span
                >{{ item.liters.toFixed(2) }} L · {{ money(item.pricePerLiter) }}/L <i v-if="item.isFull" class="full-tag">满箱</i></span
              ><small>{{ item.odometer.toLocaleString() }} km</small><span v-if="item.note" class="record-note">{{ item.note }}</span></span
            >
            <span class="receipt-amount"
              ><b>{{ money(item.amount) }}</b
              ><small>{{ item.pumpAmount > item.amount ? '省 ' + money(item.pumpAmount - item.amount) : '实付金额' }}</small></span
            >
          </button>
          <RecordActions :label="item.date + ' 的加油记录'" @edit="emit('edit', item)" @remove="emit('remove', item)" />
        </li>
      </ul>
    </section>

    <button v-if="visible.length < filtered.length" class="button secondary load-more" @click="visibleCount += 50">
      加载更多（还有 {{ filtered.length - visible.length }} 条）
    </button>

    <div v-if="!filtered.length" class="panel empty">
      <span class="empty-icon"><History :size="30" /></span>
      <h2>{{ hasFilter ? '没有找到这笔记录' : '账本的第一页，留给你' }}</h2>
      <p>{{ hasFilter ? '试试其他关键词或月份。' : '记录第一次加油，让花费与油耗清晰起来。' }}</p>
      <button v-if="hasFilter" class="button secondary" @click="clearFilters"><X :size="16" />清除筛选</button>
      <button v-else class="button primary" @click="emit('add')"><Plus :size="17" />记录第一次加油</button>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  BarChart3, Car, Check, ChevronDown, Cloud, Download, Droplets, FileJson, Fuel, Gauge,
  History, Menu, Pencil, Plus, RefreshCw, Search, Settings, Trash2, Upload, X,
} from 'lucide-vue-next'
import { useAppStore } from './stores/app'
import { testWebDav } from './services/webdav'
import { calculateAverageConsumption, calculateConsumptionIntervals, fuelRecordWarnings } from './services/fuel-calculations'
import { downloadText, parseBackup, recordsToCsv } from './services/backup'
import type { FuelRecord, Vehicle, ViewName, WebDavConfig } from './types'

const store = useAppStore()
const recordModal = ref(false)
const vehicleModal = ref(false)
const deleteTarget = ref<{ kind: 'vehicle' | 'record'; id: string; label: string } | null>(null)
const editingRecord = ref<FuelRecord | null>(null)
const editingVehicle = ref<Vehicle | null>(null)
const search = ref('')
const saving = ref(false)
const backupInput = ref<HTMLInputElement | null>(null)
const toast = reactive({ message: '', type: 'success' as 'success' | 'error' })
const testing = ref(false)
const configDraft = reactive<WebDavConfig>({ ...store.state.config })

const nav: { id: ViewName; label: string; icon: typeof BarChart3 }[] = [
  { id: 'overview', label: '概览', icon: BarChart3 },
  { id: 'records', label: '加油记录', icon: History },
  { id: 'vehicles', label: '我的车辆', icon: Car },
  { id: 'settings', label: '同步设置', icon: Settings },
]

const consumptionRows = computed(() => calculateConsumptionIntervals(store.vehicleRecords))
const averageConsumption = computed(() => calculateAverageConsumption(consumptionRows.value))
const totalCost = computed(() => store.vehicleRecords.reduce((sum, item) => sum + item.amount, 0))
const totalDistance = computed(() => {
  const list = store.vehicleRecords
  if (!list.length) return 0
  return Math.max(...list.map((item) => item.odometer)) - (store.selectedVehicle?.initialOdometer || Math.min(...list.map((item) => item.odometer)))
})
const thisMonthCost = computed(() => {
  const month = new Date().toISOString().slice(0, 7)
  return store.vehicleRecords.filter((item) => item.date.startsWith(month)).reduce((sum, item) => sum + item.amount, 0)
})
const filteredRecords = computed(() => store.vehicleRecords.filter((item) => `${item.station} ${item.note} ${item.date}`.toLowerCase().includes(search.value.toLowerCase())))
const recentChart = computed(() => consumptionRows.value.slice(-7))
const chartMax = computed(() => Math.max(12, ...recentChart.value.map((item) => item.consumption)))

function notify(message: string, type: 'success' | 'error' = 'success') {
  toast.message = message; toast.type = type
  window.setTimeout(() => { if (toast.message === message) toast.message = '' }, 2800)
}

function openRecord(item?: FuelRecord) { editingRecord.value = item || null; recordModal.value = true }
function openVehicle(item?: Vehicle) { editingVehicle.value = item || null; vehicleModal.value = true }

async function submitRecord(event: Event) {
  const data = new FormData(event.target as HTMLFormElement)
  const liters = Number(data.get('liters'))
  const amount = Number(data.get('amount'))
  const draft = {
    id: editingRecord.value?.id,
    date: String(data.get('date')),
    odometer: Number(data.get('odometer')),
    liters,
    amount,
    isFull: data.get('isFull') === 'on',
  }
  const warnings = fuelRecordWarnings(draft, store.vehicleRecords)
  if (warnings.length && !window.confirm(`这条记录可能存在异常：\n\n${warnings.map((warning) => `• ${warning}`).join('\n')}\n\n仍然保存吗？`)) return
  saving.value = true
  try {
    await store.saveRecord({
      id: editingRecord.value?.id, createdAt: editingRecord.value?.createdAt,
      vehicleId: String(data.get('vehicleId')), date: draft.date,
      odometer: draft.odometer, liters, amount,
      pricePerLiter: liters ? amount / liters : 0, isFull: draft.isFull,
      station: String(data.get('station')), note: String(data.get('note')),
    })
    recordModal.value = false
    notify(editingRecord.value ? '记录已更新' : '加油记录已保存')
  } catch (error) {
    notify(error instanceof Error ? error.message : '记录保存失败', 'error')
  } finally {
    saving.value = false
  }
}

async function submitVehicle(event: Event) {
  const data = new FormData(event.target as HTMLFormElement)
  saving.value = true
  try {
    await store.saveVehicle({ id: editingVehicle.value?.id, createdAt: editingVehicle.value?.createdAt, name: String(data.get('name')), plate: String(data.get('plate')), fuelType: String(data.get('fuelType')), initialOdometer: Number(data.get('initialOdometer')) })
    vehicleModal.value = false
    notify(editingVehicle.value ? '车辆已更新' : '车辆已添加')
  } catch (error) {
    notify(error instanceof Error ? error.message : '车辆保存失败', 'error')
  } finally {
    saving.value = false
  }
}

async function confirmDelete() {
  if (!deleteTarget.value) return
  saving.value = true
  try {
    await store.remove(deleteTarget.value.kind, deleteTarget.value.id)
    deleteTarget.value = null
    notify('已删除')
  } catch (error) {
    notify(error instanceof Error ? error.message : '删除失败', 'error')
  } finally {
    saving.value = false
  }
}

async function runSync() {
  try { await store.sync(); notify('同步完成') } catch (error) { notify(error instanceof Error ? error.message : '同步失败', 'error') }
}

async function runTest() {
  testing.value = true
  try { await testWebDav(configDraft); notify('连接成功') } catch (error) { notify(error instanceof Error ? `${error.message}。网页端还需服务器允许 CORS。` : '连接失败', 'error') } finally { testing.value = false }
}

function saveSettings() { store.saveConfig({ ...configDraft }); notify('同步设置已保存') }
async function exportBackup(format: 'json' | 'csv') {
  try {
    const payload = await store.exportData()
    const stamp = new Date().toISOString().slice(0, 10)
    const location = format === 'json'
      ? await downloadText(JSON.stringify(payload, null, 2), `fuel-track-${stamp}.json`, 'application/json;charset=utf-8')
      : await downloadText(recordsToCsv(payload.records, payload.vehicles), `fuel-track-${stamp}.csv`, 'text/csv;charset=utf-8')
    notify(location)
  } catch (error) {
    notify(error instanceof Error ? error.message : '导出失败', 'error')
  }
}

async function importBackup(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  saving.value = true
  try {
    await store.importData(parseBackup(await file.text()))
    notify('备份已合并导入')
  } catch (error) {
    notify(error instanceof Error ? error.message : '导入失败', 'error')
  } finally {
    saving.value = false
    input.value = ''
  }
}
function formatDate(date: string) { return new Intl.DateTimeFormat('zh-CN', { month: 'short', day: 'numeric' }).format(new Date(`${date}T00:00:00`)) }
function formatMoney(value: number) { return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY' }).format(value) }
function today() { return new Date().toISOString().slice(0, 10) }

onMounted(() => { void store.reload() })
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <div class="brand"><span class="brand-mark"><Fuel :size="22" /></span><div><strong>油迹</strong><small>Fuel Track</small></div></div>
      <nav>
        <button v-for="item in nav" :key="item.id" :class="{ active: store.state.view === item.id }" @click="store.state.view = item.id">
          <component :is="item.icon" :size="19" /><span>{{ item.label }}</span>
        </button>
      </nav>
      <div class="sidebar-sync">
        <span :class="['sync-dot', { online: store.state.lastSync }]" />
        <div><strong>{{ store.state.lastSync ? '云端已连接' : '尚未同步' }}</strong><small>{{ store.state.lastSync ? new Date(store.state.lastSync).toLocaleString('zh-CN') : '配置 WebDAV 开始同步' }}</small></div>
        <button class="icon-button" title="立即同步" :disabled="store.state.syncing" @click="runSync"><RefreshCw :size="17" :class="{ spin: store.state.syncing }" /></button>
      </div>
    </aside>

    <main>
      <header class="topbar">
        <div class="mobile-brand"><Fuel :size="21" /><strong>油迹</strong></div>
        <div class="vehicle-select">
          <span>{{ store.selectedVehicle?.name || '暂无车辆' }}</span><small v-if="store.selectedVehicle?.plate">{{ store.selectedVehicle.plate }}</small><ChevronDown :size="15" />
          <select v-model="store.state.selectedVehicleId" aria-label="切换车辆"><option v-for="vehicle in store.activeVehicles" :key="vehicle.id" :value="vehicle.id">{{ vehicle.name }}</option></select>
        </div>
        <button class="button primary top-add" @click="openRecord()"><Plus :size="18" />记录加油</button>
      </header>

      <section v-if="store.state.view === 'overview'" class="page overview">
        <div class="page-title"><div><span class="eyebrow">行驶概览</span><h1>你好，今天也一路顺风</h1><p>{{ store.vehicleRecords.length ? `已为 ${store.selectedVehicle?.name} 记录 ${store.vehicleRecords.length} 次加油` : '从第一笔加油记录开始了解你的爱车' }}</p></div><button class="button primary mobile-add" @click="openRecord()"><Plus :size="18" />记录加油</button></div>
        <div class="metric-grid">
          <article class="metric featured"><div class="metric-icon"><Gauge :size="20" /></div><span>平均油耗</span><strong>{{ averageConsumption ? averageConsumption.toFixed(1) : '--' }}<small>L/100km</small></strong><p>{{ consumptionRows.length ? `基于 ${consumptionRows.length} 个满箱区间` : '至少需要两次满箱记录' }}</p></article>
          <article class="metric"><div class="metric-icon amber"><Droplets :size="20" /></div><span>本月油费</span><strong>{{ formatMoney(thisMonthCost) }}</strong><p>累计 {{ formatMoney(totalCost) }}</p></article>
          <article class="metric"><div class="metric-icon dark"><Car :size="20" /></div><span>记录里程</span><strong>{{ totalDistance.toLocaleString() }}<small>km</small></strong><p>当前 {{ store.vehicleRecords[0]?.odometer.toLocaleString() || '--' }} km</p></article>
        </div>
        <div class="dashboard-grid">
          <article class="panel chart-panel">
            <div class="panel-head"><div><h2>油耗趋势</h2><p>最近 {{ recentChart.length }} 个满箱区间</p></div><span class="unit">L/100km</span></div>
            <div v-if="recentChart.length" class="chart">
              <div v-for="item in recentChart" :key="item.record.id" class="bar-column"><span>{{ item.consumption.toFixed(1) }}</span><div class="bar" :style="{ height: `${Math.max(8, item.consumption / chartMax * 100)}%` }" /><small>{{ formatDate(item.record.date) }}</small></div>
            </div>
            <div v-else class="empty compact"><BarChart3 :size="34" /><strong>趋势正在等待数据</strong><span>连续记录两次满箱加油后显示</span></div>
          </article>
          <article class="panel recent-panel">
            <div class="panel-head"><div><h2>最近加油</h2><p>最新的行程补给</p></div><button class="text-button" @click="store.state.view = 'records'">查看全部</button></div>
            <div v-if="store.vehicleRecords.length" class="recent-list">
              <button v-for="item in store.vehicleRecords.slice(0, 4)" :key="item.id" @click="openRecord(item)"><span class="date-badge"><b>{{ formatDate(item.date).split('月')[1]?.replace('日','') }}</b><small>{{ formatDate(item.date).split('月')[0] }}月</small></span><span class="recent-info"><strong>{{ item.station || '加油记录' }}</strong><small>{{ item.liters.toFixed(2) }} L · {{ item.odometer.toLocaleString() }} km</small></span><b>{{ formatMoney(item.amount) }}</b></button>
            </div>
            <div v-else class="empty compact"><Fuel :size="34" /><strong>还没有记录</strong><span>添加第一次加油，数据会出现在这里</span></div>
          </article>
        </div>
      </section>

      <section v-else-if="store.state.view === 'records'" class="page">
        <div class="page-title row"><div><span class="eyebrow">加油日志</span><h1>全部记录</h1><p>每一次补给，都清楚可查</p></div><button class="button primary" @click="openRecord()"><Plus :size="18" />新增记录</button></div>
        <div class="toolbar"><label class="search"><Search :size="18" /><input v-model="search" placeholder="搜索日期、加油站或备注" /></label><span>共 {{ filteredRecords.length }} 条</span></div>
        <div v-if="filteredRecords.length" class="record-table panel">
          <div class="table-row table-head"><span>日期 / 地点</span><span>里程</span><span>加油量</span><span>单价</span><span>金额</span><span></span></div>
          <div v-for="item in filteredRecords" :key="item.id" class="table-row">
            <span class="primary-cell"><b>{{ item.date }}</b><small>{{ item.station || '未填写加油站' }}<i v-if="item.isFull">满箱</i></small></span><span data-label="里程">{{ item.odometer.toLocaleString() }} km</span><span data-label="加油量">{{ item.liters.toFixed(2) }} L</span><span data-label="单价">¥{{ item.pricePerLiter.toFixed(2) }}</span><strong data-label="金额">{{ formatMoney(item.amount) }}</strong><span class="row-actions"><button class="icon-button" title="编辑" @click="openRecord(item)"><Pencil :size="16" /></button><button class="icon-button danger" title="删除" @click="deleteTarget = { kind: 'record', id: item.id, label: item.date + ' 的加油记录' }"><Trash2 :size="16" /></button></span>
          </div>
        </div>
        <div v-else class="panel empty"><History :size="40" /><h2>{{ search ? '没有匹配的记录' : '开始记录第一笔加油' }}</h2><p>{{ search ? '换个关键词试试' : '里程和金额会自动汇总到概览中' }}</p><button v-if="!search" class="button primary" @click="openRecord()"><Plus :size="18" />新增记录</button></div>
      </section>

      <section v-else-if="store.state.view === 'vehicles'" class="page">
        <div class="page-title row"><div><span class="eyebrow">车库</span><h1>我的车辆</h1><p>分别追踪每辆车的油耗表现</p></div><button class="button primary" @click="openVehicle()"><Plus :size="18" />添加车辆</button></div>
        <div class="vehicle-grid">
          <article v-for="vehicle in store.activeVehicles" :key="vehicle.id" :class="['vehicle-card', { selected: store.state.selectedVehicleId === vehicle.id }]" @click="store.state.selectedVehicleId = vehicle.id">
            <div class="vehicle-art"><Car :size="42" /><span>{{ vehicle.fuelType }}</span></div><div class="vehicle-card-body"><div><h2>{{ vehicle.name }}</h2><p>{{ vehicle.plate || '未设置车牌' }}</p></div><span v-if="store.state.selectedVehicleId === vehicle.id" class="selected-label"><Check :size="14" />当前车辆</span></div><dl><div><dt>加油次数</dt><dd>{{ store.state.records.filter(r => r.vehicleId === vehicle.id).length }}</dd></div><div><dt>初始里程</dt><dd>{{ vehicle.initialOdometer.toLocaleString() }} km</dd></div></dl><div class="card-actions"><button class="button subtle" @click.stop="openVehicle(vehicle)"><Pencil :size="16" />编辑</button><button class="icon-button danger" title="删除车辆" :disabled="store.activeVehicles.length === 1" @click.stop="deleteTarget = { kind: 'vehicle', id: vehicle.id, label: vehicle.name }"><Trash2 :size="17" /></button></div>
          </article>
        </div>
      </section>

      <section v-else class="page settings-page">
        <div class="page-title"><span class="eyebrow">跨端同步</span><h1>WebDAV 设置</h1><p>数据存储在你的 WebDAV 空间，由你完全掌控</p></div>
        <div class="settings-layout">
          <form class="panel settings-form" @submit.prevent="saveSettings">
            <div class="section-heading"><span class="large-icon"><Cloud :size="24" /></span><div><h2>云端连接</h2><p>支持坚果云、Nextcloud、群晖等 WebDAV 服务</p></div></div>
            <label><span>服务器地址</span><input v-model.trim="configDraft.url" type="url" placeholder="https://dav.example.com/fuel-track" /></label>
            <div class="form-grid"><label><span>用户名</span><input v-model="configDraft.username" autocomplete="username" placeholder="你的账号" /></label><label><span>应用密码</span><input v-model="configDraft.password" type="password" autocomplete="current-password" placeholder="WebDAV 应用密码" /></label></div>
            <label><span>同步文件名</span><input v-model.trim="configDraft.fileName" placeholder="fuel-track.json" /></label>
            <div class="form-actions"><button type="button" class="button secondary" :disabled="testing" @click="runTest"><RefreshCw :size="17" :class="{ spin: testing }" />测试连接</button><button class="button primary"><Check :size="17" />保存设置</button></div>
          </form>
          <aside class="sync-summary">
            <h2>同步状态</h2><div class="sync-illustration"><Cloud :size="32" /><span :class="{ active: store.state.lastSync }" /></div><strong>{{ store.state.lastSync ? '数据已同步' : '等待首次同步' }}</strong><p>{{ store.state.lastSync ? `上次同步：${new Date(store.state.lastSync).toLocaleString('zh-CN')}` : '保存连接信息后，点击立即同步。' }}</p><button class="button primary full" :disabled="store.state.syncing || !store.state.config.url" @click="runSync"><RefreshCw :size="18" :class="{ spin: store.state.syncing }" />{{ store.state.syncing ? '正在合并数据…' : '立即同步' }}</button><div class="privacy-note"><strong>记录级安全合并</strong><span>同步会比较每条记录的更新时间，多设备离线录入也不会整库覆盖。</span></div>
          </aside>
        </div>
        <section class="panel backup-panel">
          <div class="section-heading"><span class="large-icon"><FileJson :size="24" /></span><div><h2>本地备份</h2><p>导出完整 JSON 备份、CSV 报表，或将 JSON 备份安全合并到当前数据库</p></div></div>
          <div class="backup-actions">
            <button class="button secondary" @click="exportBackup('json')"><Download :size="17" />导出 JSON</button>
            <button class="button secondary" @click="exportBackup('csv')"><Download :size="17" />导出 CSV</button>
            <button class="button primary" :disabled="saving" @click="backupInput?.click()"><Upload :size="17" />合并导入 JSON</button>
            <input ref="backupInput" class="visually-hidden" type="file" accept="application/json,.json" @change="importBackup" />
          </div>
        </section>
      </section>
    </main>

    <nav class="bottom-nav"><button v-for="item in nav" :key="item.id" :class="{ active: store.state.view === item.id }" @click="store.state.view = item.id"><component :is="item.icon" :size="20" /><span>{{ item.label === '加油记录' ? '记录' : item.label === '我的车辆' ? '车辆' : item.label === '同步设置' ? '设置' : item.label }}</span></button></nav>

    <div v-if="recordModal" class="modal-backdrop" @mousedown.self="!saving && (recordModal = false)"><form class="modal" @submit.prevent="submitRecord"><div class="modal-head"><div><span class="eyebrow">{{ editingRecord ? '修改信息' : '新一次补给' }}</span><h2>{{ editingRecord ? '编辑加油记录' : '记录加油' }}</h2></div><button type="button" class="icon-button" title="关闭" :disabled="saving" @click="recordModal = false"><X :size="20" /></button></div><div class="form-grid"><label><span>车辆</span><select name="vehicleId" :value="editingRecord?.vehicleId || store.state.selectedVehicleId" required><option v-for="vehicle in store.activeVehicles" :key="vehicle.id" :value="vehicle.id">{{ vehicle.name }}</option></select></label><label><span>日期</span><input name="date" type="date" :value="editingRecord?.date || today()" required /></label><label><span>当前里程（km）</span><input name="odometer" type="number" min="0" step="0.1" :value="editingRecord?.odometer" placeholder="例如 32680" required /></label><label><span>加油量（L）</span><input name="liters" type="number" min="0.01" step="0.01" :value="editingRecord?.liters" placeholder="例如 42.50" required /></label><label><span>实付金额（元）</span><input name="amount" type="number" min="0" step="0.01" :value="editingRecord?.amount" placeholder="例如 328.00" required /></label><label><span>加油站</span><input name="station" :value="editingRecord?.station" placeholder="选填" /></label></div><label><span>备注</span><textarea name="note" rows="3" :value="editingRecord?.note" placeholder="路况、油品或其他备注"></textarea></label><label class="check-row"><input name="isFull" type="checkbox" :checked="editingRecord?.isFull ?? true" /><span><b>本次加满油箱</b><small>满箱记录用于准确计算区间油耗</small></span></label><div class="form-actions"><button type="button" class="button secondary" :disabled="saving" @click="recordModal = false">取消</button><button class="button primary" :disabled="saving"><RefreshCw v-if="saving" :size="17" class="spin" /><Check v-else :size="17" />{{ saving ? '正在保存…' : '保存记录' }}</button></div></form></div>

    <div v-if="vehicleModal" class="modal-backdrop" @mousedown.self="!saving && (vehicleModal = false)"><form class="modal small" @submit.prevent="submitVehicle"><div class="modal-head"><div><span class="eyebrow">车库信息</span><h2>{{ editingVehicle ? '编辑车辆' : '添加车辆' }}</h2></div><button type="button" class="icon-button" title="关闭" :disabled="saving" @click="vehicleModal = false"><X :size="20" /></button></div><label><span>车辆名称</span><input name="name" :value="editingVehicle?.name" placeholder="例如：家庭用车" required /></label><div class="form-grid"><label><span>车牌号</span><input name="plate" :value="editingVehicle?.plate" placeholder="选填" /></label><label><span>燃油标号</span><select name="fuelType" :value="editingVehicle?.fuelType || '92#'"><option>92#</option><option>95#</option><option>98#</option><option>柴油</option></select></label></div><label><span>起始里程（km）</span><input name="initialOdometer" type="number" min="0" step="0.1" :value="editingVehicle?.initialOdometer || 0" /></label><div class="form-actions"><button type="button" class="button secondary" :disabled="saving" @click="vehicleModal = false">取消</button><button class="button primary" :disabled="saving"><RefreshCw v-if="saving" :size="17" class="spin" /><Check v-else :size="17" />{{ saving ? '正在保存…' : '保存车辆' }}</button></div></form></div>

    <div v-if="deleteTarget" class="modal-backdrop"><div class="confirm-dialog"><span class="danger-icon"><Trash2 :size="23" /></span><h2>确认删除？</h2><p>“{{ deleteTarget.label }}”{{ deleteTarget.kind === 'vehicle' ? '及其全部加油记录' : '' }}将从当前设备移除，并在下次同步时更新到其他设备。</p><div class="form-actions"><button class="button secondary" :disabled="saving" @click="deleteTarget = null">取消</button><button class="button danger-button" :disabled="saving" @click="confirmDelete">{{ saving ? '正在删除…' : '确认删除' }}</button></div></div></div>
    <Transition name="toast"><div v-if="toast.message" :class="['toast', toast.type]"><Check v-if="toast.type === 'success'" :size="18" /><X v-else :size="18" />{{ toast.message }}</div></Transition>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import {
  BarChart3, Car, Check, ChevronDown, Cloud, Download, FileJson, Fuel, History,
  Plus, RefreshCw, Settings, Trash2, Upload,
} from 'lucide-vue-next'
import AppToast from './components/AppToast.vue'
import ConfirmDialog from './components/ConfirmDialog.vue'
import RecordModal from './components/RecordModal.vue'
import RecordsPage from './components/RecordsPage.vue'
import OverviewPage from './components/OverviewPage.vue'
import VehicleModal from './components/VehicleModal.vue'
import { useAppStore } from './stores/app'
import { testWebDav } from './services/webdav'
import { fuelRecordWarnings } from './services/fuel-calculations'
import { downloadText, parseBackup, recordsToCsv } from './services/backup'
import { localDateKey } from './services/local-date'
import type { FuelRecord, Vehicle, ViewName, WebDavConfig } from './types'

const store = useAppStore()
const recordModal = ref(false)
const vehicleModal = ref(false)
const deleteTarget = ref<{ kind: 'vehicle' | 'record'; id: string; label: string } | null>(null)
const editingRecord = ref<FuelRecord | null>(null)
const editingVehicle = ref<Vehicle | null>(null)
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
  const pumpAmount = Number(data.get('pumpAmount'))
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
      odometer: draft.odometer, liters, amount, pumpAmount,
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
function today() { return localDateKey() }

onMounted(async () => {
  try { await store.reload() } catch (error) {
    notify(error instanceof Error ? error.message : '本地数据读取失败', 'error')
  }
})
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

      <OverviewPage v-if="store.state.view === 'overview'" :vehicle="store.selectedVehicle" :records="store.vehicleRecords" @add="openRecord()" @edit="openRecord" @records="store.state.view = 'records'" />

      <RecordsPage v-else-if="store.state.view === 'records'" :records="store.vehicleRecords" @add="openRecord()" @edit="openRecord" @remove="deleteTarget = { kind: 'record', id: $event.id, label: $event.date + ' 的加油记录' }" />

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

    <RecordModal v-if="recordModal" :record="editingRecord" :vehicles="store.activeVehicles" :selected-vehicle-id="store.state.selectedVehicleId" :saving="saving" :today="today()" @close="recordModal = false" @submit="submitRecord" />
    <VehicleModal v-if="vehicleModal" :vehicle="editingVehicle" :saving="saving" @close="vehicleModal = false" @submit="submitVehicle" />
    <ConfirmDialog v-if="deleteTarget" :target="deleteTarget" :saving="saving" @close="deleteTarget = null" @confirm="confirmDelete" />
    <AppToast :message="toast.message" :type="toast.type" />
  </div>
</template>

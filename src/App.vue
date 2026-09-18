<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { BarChart3, Car, ChevronDown, Fuel, History, Plus, RefreshCw, Settings } from '@lucide/vue'
import AppToast from './components/AppToast.vue'
import ConfirmDialog from './components/ConfirmDialog.vue'
import OnboardingGuide from './components/OnboardingGuide.vue'
import RecordModal from './components/RecordModal.vue'
import RecordsPage from './components/RecordsPage.vue'
import OverviewPage from './components/OverviewPage.vue'
import VehicleModal from './components/VehicleModal.vue'
import VehiclesPage from './components/VehiclesPage.vue'
import SettingsPage from './components/SettingsPage.vue'
import { useAppStore } from './stores/app'
import { testWebDav } from './services/webdav'
import { fuelRecordWarnings } from './services/fuel-calculations'
import { downloadText, parseBackup, recordsToCsv } from './services/backup'
import { localDateKey } from './services/local-date'
import { userErrorMessage } from './services/app-error'
import { createDiagnosticReport } from './services/diagnostics'
import type { FuelRecord, Vehicle, ViewName, WebDavConfig } from './types'

const store = useAppStore()
const recordModal = ref(false)
const vehicleModal = ref(false)
const onboarding = ref(false)
const deleteTarget = ref<{ kind: 'vehicle' | 'record'; id: string; label: string } | null>(null)
const editingRecord = ref<FuelRecord | null>(null)
const editingVehicle = ref<Vehicle | null>(null)
const recordIdentity = ref<Pick<FuelRecord, 'id' | 'createdAt'>>()
const vehicleIdentity = ref<Pick<Vehicle, 'id' | 'createdAt'>>()
const saving = ref(false)
const toast = reactive({ message: '', type: 'success' as 'success' | 'error' })
const testing = ref(false)

const nav: { id: ViewName; label: string; shortLabel: string; icon: typeof BarChart3 }[] = [
  { id: 'overview', label: '行驶概览', shortLabel: '概览', icon: BarChart3 },
  { id: 'records', label: '加油记录', shortLabel: '记录', icon: History },
  { id: 'vehicles', label: '我的车辆', shortLabel: '车辆', icon: Car },
  { id: 'settings', label: '数据与同步', shortLabel: '同步', icon: Settings },
]

let toastTimer: number | undefined
function notify(message: string, type: 'success' | 'error' = 'success') {
  window.clearTimeout(toastTimer)
  toast.message = message
  toast.type = type
  toastTimer = window.setTimeout(() => {
    toast.message = ''
  }, 4200)
}

function openRecord(item?: FuelRecord) {
  if (!store.activeVehicles.length) {
    openVehicle()
    notify('先添加一辆车，就能开始记录加油。')
    return
  }
  editingRecord.value = item || null
  recordIdentity.value = { id: item?.id || crypto.randomUUID(), createdAt: item?.createdAt || new Date().toISOString() }
  recordModal.value = true
}
function openVehicle(item?: Vehicle) {
  editingVehicle.value = item || null
  vehicleIdentity.value = { id: item?.id || crypto.randomUUID(), createdAt: item?.createdAt || new Date().toISOString() }
  vehicleModal.value = true
}

async function submitRecord(event: Event) {
  if (saving.value) return
  const data = new FormData(event.target as HTMLFormElement)
  const liters = Number(data.get('liters'))
  const amount = Number(data.get('amount'))
  const pumpAmount = Number(data.get('pumpAmount'))
  const draft = {
    id: recordIdentity.value?.id,
    date: String(data.get('date')),
    odometer: Number(data.get('odometer')),
    liters,
    amount,
    isFull: data.get('isFull') === 'on',
  }
  const vehicleId = String(data.get('vehicleId'))
  const warnings = fuelRecordWarnings(
    draft,
    store.state.records.filter((item) => item.vehicleId === vehicleId),
  )
  if (
    warnings.length &&
    !window.confirm(`这条记录可能存在异常：\n\n${warnings.map((warning) => `• ${warning}`).join('\n')}\n\n仍然保存吗？`)
  )
    return
  saving.value = true
  try {
    await store.saveRecord({
      ...recordIdentity.value,
      vehicleId,
      date: draft.date,
      odometer: draft.odometer,
      liters,
      amount,
      pumpAmount,
      pricePerLiter: liters ? amount / liters : 0,
      isFull: draft.isFull,
      station: String(data.get('station')),
      note: String(data.get('note')),
    })
    recordModal.value = false
    notify(editingRecord.value ? '记录已更新' : '加油记录已保存')
  } catch (error) {
    notify(userErrorMessage(error), 'error')
  } finally {
    saving.value = false
  }
}

async function submitVehicle(event: Event) {
  if (saving.value) return
  const data = new FormData(event.target as HTMLFormElement)
  saving.value = true
  try {
    await store.saveVehicle({
      ...vehicleIdentity.value,
      name: String(data.get('name')),
      plate: String(data.get('plate')),
      fuelType: String(data.get('fuelType')),
      initialOdometer: Number(data.get('initialOdometer')),
    })
    vehicleModal.value = false
    notify(editingVehicle.value ? '车辆已更新' : '车辆已添加')
  } catch (error) {
    notify(userErrorMessage(error), 'error')
  } finally {
    saving.value = false
  }
}

async function confirmDelete() {
  if (!deleteTarget.value || saving.value) return
  saving.value = true
  try {
    await store.remove(deleteTarget.value.kind, deleteTarget.value.id)
    deleteTarget.value = null
    notify('已删除')
  } catch (error) {
    notify(userErrorMessage(error), 'error')
  } finally {
    saving.value = false
  }
}

async function runSync() {
  try {
    await store.sync()
    notify('同步完成')
  } catch (error) {
    notify(userErrorMessage(error), 'error')
  }
}

async function runTest(config: WebDavConfig) {
  testing.value = true
  try {
    await testWebDav(config)
    notify('连接成功')
  } catch (error) {
    notify(`${userErrorMessage(error)} 网页端还需服务器允许 CORS。`, 'error')
  } finally {
    testing.value = false
  }
}

async function saveSettings(config: WebDavConfig) {
  try {
    const wasEncrypted = Boolean(store.state.config.encryptionEnabled)
    const willEncrypt = Boolean(config.encryptionEnabled)
    const passphraseChanged = wasEncrypted && willEncrypt && config.encryptionPassphrase !== store.state.config.encryptionPassphrase
    const disabling = wasEncrypted && !willEncrypt

    if (willEncrypt && !wasEncrypted) {
      if (!window.confirm('启用加密后，旧版客户端将无法同步。请确认所有设备都已升级。继续前会自动下载一份明文本地备份。')) return
      await exportBackup('json')
    }
    if (passphraseChanged || disabling) {
      const action = passphraseChanged ? '修改同步口令' : '停用同步加密'
      if (!window.confirm(`${action}前会先使用当前口令拉取云端数据并导出本地备份，然后以新设置重新上传。是否继续？`)) return
      await store.sync()
      await exportBackup('json')
    }
    store.saveConfig(config)
    if (passphraseChanged || disabling) await store.sync()
    notify('同步设置已保存')
  } catch (error) {
    notify(userErrorMessage(error), 'error')
  }
}
async function exportBackup(format: 'json' | 'csv') {
  try {
    const payload = await store.exportData()
    const stamp = new Date().toISOString().slice(0, 10)
    const location =
      format === 'json'
        ? await downloadText(JSON.stringify(payload, null, 2), `fuel-track-${stamp}.json`, 'application/json;charset=utf-8')
        : await downloadText(recordsToCsv(payload.records, payload.vehicles), `fuel-track-${stamp}.csv`, 'text/csv;charset=utf-8')
    notify(location)
  } catch (error) {
    notify(userErrorMessage(error), 'error')
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
    notify(userErrorMessage(error), 'error')
  } finally {
    saving.value = false
    input.value = ''
  }
}
async function exportDiagnostics() {
  try {
    const report = await createDiagnosticReport()
    await downloadText(JSON.stringify(report, null, 2), `fuel-track-diagnostics-${today()}.json`, 'application/json;charset=utf-8')
    notify('脱敏诊断信息已导出')
  } catch (error) {
    notify(userErrorMessage(error), 'error')
  }
}
function today() {
  return localDateKey()
}

function completeOnboarding(next?: 'record' | 'vehicle') {
  localStorage.setItem('fuel-track-onboarding-v1', '1')
  onboarding.value = false
  if (next === 'record') openRecord()
  if (next === 'vehicle') openVehicle(store.selectedVehicle)
}

async function refreshLocalData() {
  try {
    await store.reload()
  } catch (error) {
    notify(userErrorMessage(error), 'error')
  }
}
function onVisibilityChange() {
  if (document.visibilityState === 'visible') void refreshLocalData()
}

onMounted(async () => {
  await refreshLocalData()
  onboarding.value = localStorage.getItem('fuel-track-onboarding-v1') !== '1' && store.vehicleRecords.length === 0
  document.addEventListener('visibilitychange', onVisibilityChange)
})
onBeforeUnmount(() => {
  window.clearTimeout(toastTimer)
  document.removeEventListener('visibilitychange', onVisibilityChange)
})
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <div class="brand">
        <span class="brand-mark"><Fuel :size="22" /></span>
        <div><strong>油迹</strong><small>Drive ledger</small></div>
      </div>
      <nav aria-label="主导航">
        <button
          v-for="item in nav"
          :key="item.id"
          :class="{ active: store.state.view === item.id }"
          :aria-current="store.state.view === item.id ? 'page' : undefined"
          @click="store.state.view = item.id"
        >
          <component :is="item.icon" :size="19" /><span>{{ item.label }}</span>
        </button>
      </nav>
      <div class="sidebar-sync">
        <span :class="['sync-dot', { online: store.state.lastSync }]" />
        <div>
          <strong>{{ store.state.lastSync ? '上次同步' : '尚未同步' }}</strong
          ><small>{{ store.state.lastSync ? new Date(store.state.lastSync).toLocaleString('zh-CN') : '配置 WebDAV 开始同步' }}</small>
        </div>
        <button class="icon-button" title="立即同步" :disabled="store.state.syncing" @click="runSync">
          <RefreshCw :size="17" :class="{ spin: store.state.syncing }" />
        </button>
      </div>
    </aside>

    <main>
      <header class="topbar">
        <div class="vehicle-select">
          <span class="vehicle-select-icon"><Car :size="20" /></span>
          <div class="vehicle-caption">
            <strong>{{ store.selectedVehicle?.name || '暂无车辆' }}</strong
            ><small>{{ store.selectedVehicle?.plate || store.selectedVehicle?.fuelType || '从第一辆车开始' }}</small>
          </div>
          <ChevronDown :size="15" />
          <select v-model="store.state.selectedVehicleId" aria-label="切换车辆" :disabled="!store.activeVehicles.length">
            <option v-for="vehicle in store.activeVehicles" :key="vehicle.id" :value="vehicle.id">{{ vehicle.name }}</option>
          </select>
        </div>
        <div class="mobile-brand">
          <span><Fuel :size="17" /></span><strong>油迹</strong>
        </div>
        <button class="button primary top-add" @click="openRecord()"><Plus :size="18" />记一笔加油</button>
      </header>

      <OverviewPage
        v-if="store.state.view === 'overview'"
        :vehicle="store.selectedVehicle"
        :records="store.vehicleRecords"
        @add="openRecord()"
        @edit="openRecord"
        @records="store.state.view = 'records'"
      />

      <RecordsPage
        v-else-if="store.state.view === 'records'"
        :records="store.vehicleRecords"
        :vehicle-id="store.selectedVehicle?.id"
        @add="openRecord()"
        @edit="openRecord"
        @remove="deleteTarget = { kind: 'record', id: $event.id, label: $event.date + ' 的加油记录' }"
      />

      <VehiclesPage
        v-else-if="store.state.view === 'vehicles'"
        :vehicles="store.activeVehicles"
        :records="store.state.records"
        :selected-vehicle-id="store.state.selectedVehicleId"
        @add="openVehicle()"
        @edit="openVehicle"
        @select="store.state.selectedVehicleId = $event"
        @remove="deleteTarget = { kind: 'vehicle', id: $event.id, label: $event.name }"
      />

      <SettingsPage
        v-else
        :config="store.state.config"
        :syncing="store.state.syncing"
        :testing="testing"
        :saving="saving"
        :last-sync="store.state.lastSync"
        @save="saveSettings"
        @test="runTest"
        @sync="runSync"
        @export="exportBackup"
        @import="importBackup"
        @diagnostics="exportDiagnostics"
      />
    </main>

    <nav class="bottom-nav" aria-label="底部导航">
      <template v-for="(item, index) in nav" :key="item.id">
        <button v-if="index === 2" class="bottom-add" aria-label="记一笔加油" @click="openRecord()">
          <span class="add-icon"><Plus :size="23" /></span><span>记一笔</span>
        </button>
        <button
          :class="{ active: store.state.view === item.id }"
          :aria-current="store.state.view === item.id ? 'page' : undefined"
          @click="store.state.view = item.id"
        >
          <component :is="item.icon" :size="21" /><span>{{ item.shortLabel }}</span>
        </button>
      </template>
    </nav>

    <RecordModal
      v-if="recordModal"
      :record="editingRecord"
      :vehicles="store.activeVehicles"
      :selected-vehicle-id="store.state.selectedVehicleId"
      :saving="saving"
      :today="today()"
      @close="recordModal = false"
      @submit="submitRecord"
    />
    <VehicleModal v-if="vehicleModal" :vehicle="editingVehicle" :saving="saving" @close="vehicleModal = false" @submit="submitVehicle" />
    <ConfirmDialog v-if="deleteTarget" :target="deleteTarget" :saving="saving" @close="deleteTarget = null" @confirm="confirmDelete" />
    <OnboardingGuide
      v-if="onboarding"
      :vehicle-name="store.selectedVehicle?.name || '我的车辆'"
      @finish="completeOnboarding()"
      @record="completeOnboarding('record')"
      @vehicle="completeOnboarding('vehicle')"
    />
    <AppToast :message="toast.message" :type="toast.type" :modal-open="recordModal || vehicleModal || !!deleteTarget || onboarding" />
  </div>
</template>

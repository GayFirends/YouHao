import { computed, reactive } from 'vue'
import { defineStore } from 'pinia'
import { database } from '../services/database'
import { syncWebDav } from '../services/webdav'
import type { FuelRecord, Vehicle, ViewName, WebDavConfig } from '../types'

export const useAppStore = defineStore('app', () => {
  const state = reactive({
    view: 'overview' as ViewName,
    vehicles: [] as Vehicle[],
    records: [] as FuelRecord[],
    selectedVehicleId: '',
    config: database.getConfig() as WebDavConfig,
    syncing: false,
    lastSync: localStorage.getItem('fuel-track-last-sync') || '',
  })

  const activeVehicles = computed(() => state.vehicles.filter((item) => !item.deletedAt))
  const selectedVehicle = computed(() => activeVehicles.value.find((item) => item.id === state.selectedVehicleId) || activeVehicles.value[0])
  const vehicleRecords = computed(() => state.records.filter((item) => item.vehicleId === selectedVehicle.value?.id && !item.deletedAt).sort((a, b) => b.odometer - a.odometer))

  async function reload() {
    const [vehicles, records] = await Promise.all([database.vehicles(), database.records()])
    state.vehicles = vehicles
    state.records = records
    if (!activeVehicles.value.some((item) => item.id === state.selectedVehicleId)) state.selectedVehicleId = activeVehicles.value[0]?.id || ''
  }

  async function saveVehicle(input: Partial<Vehicle> & Pick<Vehicle, 'name'>) {
    const now = new Date().toISOString()
    await database.saveVehicle({ id: input.id || crypto.randomUUID(), name: input.name, plate: input.plate || '', fuelType: input.fuelType || '92#', initialOdometer: Number(input.initialOdometer) || 0, createdAt: input.createdAt || now, updatedAt: now, deletedAt: null })
    await reload()
  }

  async function saveRecord(input: Omit<FuelRecord, 'id' | 'createdAt' | 'updatedAt' | 'deletedAt'> & { id?: string; createdAt?: string }) {
    const now = new Date().toISOString()
    await database.saveRecord({ ...input, id: input.id || crypto.randomUUID(), createdAt: input.createdAt || now, updatedAt: now, deletedAt: null })
    await reload()
  }

  async function remove(kind: 'vehicle' | 'record', id: string) {
    const now = new Date().toISOString()
    if (kind === 'vehicle') {
      await database.deleteVehicle(id, now)
    } else {
      const item = state.records.find((entry) => entry.id === id)
      if (item) await database.saveRecord({ ...item, deletedAt: now, updatedAt: now })
    }
    await reload()
  }

  function saveConfig(config: WebDavConfig) {
    state.config = { ...config }
    database.saveConfig(state.config)
  }

  async function sync() {
    state.syncing = true
    try {
      const date = await syncWebDav(state.config)
      state.lastSync = date.toISOString()
      localStorage.setItem('fuel-track-last-sync', state.lastSync)
      await reload()
    } finally { state.syncing = false }
  }

  async function exportData() {
    return database.exportData()
  }

  async function importData(payload: import('../types').SyncPayload) {
    await database.mergeData(payload)
    await reload()
  }

  return { state, activeVehicles, selectedVehicle, vehicleRecords, reload, saveVehicle, saveRecord, remove, saveConfig, sync, exportData, importData }
})

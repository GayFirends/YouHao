import type { FuelRecord, SyncPayload, Vehicle } from '../types'

const MAX_ITEMS = 100_000
const DATE_RE = /^\d{4}-\d{2}-\d{2}$/
const TIMESTAMP_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/

function fail(message: string): never {
  throw new Error(`备份文件校验失败：${message}`)
}

function stringField(value: unknown, field: string, allowEmpty = true): string {
  if (typeof value !== 'string' || (!allowEmpty && value.trim() === '')) fail(`${field} 必须是非空文本`)
  return value
}

function timestamp(value: unknown, field: string): string {
  const text = stringField(value, field, false)
  if (!TIMESTAMP_RE.test(text) || !Number.isFinite(Date.parse(text))) fail(`${field} 不是有效时间`)
  return text
}

function dateOnly(value: unknown, field: string): string {
  const text = stringField(value, field, false)
  const parsed = new Date(`${text}T00:00:00Z`)
  if (!DATE_RE.test(text) || Number.isNaN(parsed.getTime()) || parsed.toISOString().slice(0, 10) !== text) fail(`${field} 不是有效日期`)
  return text
}

function numberField(value: unknown, field: string, minimum: number): number {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < minimum) fail(`${field} 必须是大于等于 ${minimum} 的数字`)
  return value
}

function nullableTimestamp(value: unknown, field: string): string | null {
  return value === null ? null : timestamp(value, field)
}

function validateVehicle(value: unknown, index: number): Vehicle {
  if (!value || typeof value !== 'object') fail(`vehicles[${index}] 不是对象`)
  const item = value as Record<string, unknown>
  return {
    id: stringField(item.id, `vehicles[${index}].id`, false),
    name: stringField(item.name, `vehicles[${index}].name`, false),
    plate: stringField(item.plate, `vehicles[${index}].plate`),
    fuelType: stringField(item.fuelType, `vehicles[${index}].fuelType`, false),
    initialOdometer: numberField(item.initialOdometer, `vehicles[${index}].initialOdometer`, 0),
    createdAt: timestamp(item.createdAt, `vehicles[${index}].createdAt`),
    updatedAt: timestamp(item.updatedAt, `vehicles[${index}].updatedAt`),
    deletedAt: nullableTimestamp(item.deletedAt, `vehicles[${index}].deletedAt`),
  }
}

function validateRecord(value: unknown, index: number): FuelRecord {
  if (!value || typeof value !== 'object') fail(`records[${index}] 不是对象`)
  const item = value as Record<string, unknown>
  if (typeof item.isFull !== 'boolean') fail(`records[${index}].isFull 必须是布尔值`)
  return {
    id: stringField(item.id, `records[${index}].id`, false),
    vehicleId: stringField(item.vehicleId, `records[${index}].vehicleId`, false),
    date: dateOnly(item.date, `records[${index}].date`),
    odometer: numberField(item.odometer, `records[${index}].odometer`, 0),
    liters: numberField(item.liters, `records[${index}].liters`, Number.MIN_VALUE),
    amount: numberField(item.amount, `records[${index}].amount`, 0),
    pumpAmount: item.pumpAmount === undefined
      ? numberField(item.amount, `records[${index}].amount`, 0)
      : numberField(item.pumpAmount, `records[${index}].pumpAmount`, 0),
    pricePerLiter: numberField(item.pricePerLiter, `records[${index}].pricePerLiter`, 0),
    isFull: item.isFull,
    station: stringField(item.station, `records[${index}].station`),
    note: stringField(item.note, `records[${index}].note`),
    createdAt: timestamp(item.createdAt, `records[${index}].createdAt`),
    updatedAt: timestamp(item.updatedAt, `records[${index}].updatedAt`),
    deletedAt: nullableTimestamp(item.deletedAt, `records[${index}].deletedAt`),
  }
}

export function validateSyncPayload(value: unknown): SyncPayload {
  if (!value || typeof value !== 'object') fail('根节点必须是对象')
  const payload = value as Record<string, unknown>
  if (payload.version !== 1) fail('仅支持版本 1')
  const exportedAt = timestamp(payload.exportedAt, 'exportedAt')
  if (!Array.isArray(payload.vehicles) || !Array.isArray(payload.records)) fail('vehicles 和 records 必须是数组')
  if (payload.vehicles.length > MAX_ITEMS || payload.records.length > MAX_ITEMS) fail('记录数量超过上限')

  const vehicles = payload.vehicles.map(validateVehicle)
  const vehicleIds = new Set<string>()
  for (const vehicle of vehicles) {
    if (vehicleIds.has(vehicle.id)) fail(`车辆 ID 重复：${vehicle.id}`)
    vehicleIds.add(vehicle.id)
  }
  const records = payload.records.map(validateRecord)
  const recordIds = new Set<string>()
  for (const record of records) {
    if (recordIds.has(record.id)) fail(`记录 ID 重复：${record.id}`)
    if (!vehicleIds.has(record.vehicleId)) fail(`记录 ${record.id} 引用了不存在的车辆`)
    recordIds.add(record.id)
  }
  return { version: 1, exportedAt, vehicles, records }
}

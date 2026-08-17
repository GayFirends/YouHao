import type { FuelRecord, SyncPayload, Vehicle } from '../types'

export function parseBackup(text: string): SyncPayload {
  let value: unknown
  try { value = JSON.parse(text) } catch { throw new Error('备份文件不是有效的 JSON') }
  const payload = value as Partial<SyncPayload>
  if (payload.version !== 1 || !Array.isArray(payload.vehicles) || !Array.isArray(payload.records)) throw new Error('备份文件格式不受支持')
  return payload as SyncPayload
}

function csvCell(value: string | number | boolean) {
  const text = String(value)
  return /[",\r\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text
}

export function recordsToCsv(records: FuelRecord[], vehicles: Vehicle[]) {
  const vehicleNames = new Map(vehicles.map((vehicle) => [vehicle.id, vehicle.name]))
  const columns = ['车辆', '日期', '里程(km)', '加油量(L)', '金额(元)', '单价(元/L)', '满箱', '加油站', '备注']
  const lines = records.filter((record) => !record.deletedAt).map((record) => [
    vehicleNames.get(record.vehicleId) || record.vehicleId,
    record.date,
    record.odometer,
    record.liters,
    record.amount,
    record.pricePerLiter.toFixed(2),
    record.isFull ? '是' : '否',
    record.station,
    record.note,
  ].map(csvCell).join(','))
  return `\uFEFF${columns.join(',')}\r\n${lines.join('\r\n')}`
}

export function downloadText(content: string, fileName: string, type: string) {
  const url = URL.createObjectURL(new Blob([content], { type }))
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  link.click()
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
}

import type { FuelRecord, SyncPayload, Vehicle } from '../types'
import { Capacitor } from '@capacitor/core'
import { validateSyncPayload } from './sync-validation'

export function parseBackup(text: string): SyncPayload {
  if (text.length > 20 * 1024 * 1024) throw new Error('备份文件超过 20 MB，拒绝导入')
  let value: unknown
  try { value = JSON.parse(text) } catch { throw new Error('备份文件不是有效的 JSON') }
  try { return validateSyncPayload(value) } catch (error) {
    if (error instanceof Error && error.message.startsWith('备份文件校验失败')) throw error
    throw new Error('备份文件格式不受支持')
  }
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

export async function downloadText(content: string, fileName: string, type: string) {
  if (Capacitor.isNativePlatform()) {
    const [{ Directory, Encoding, Filesystem }, { Share }] = await Promise.all([
      import('@capacitor/filesystem'),
      import('@capacitor/share'),
    ])
    const permission = await Filesystem.checkPermissions()
    if (permission.publicStorage !== 'granted') await Filesystem.requestPermissions()
    const path = `FuelTrack/${fileName}`
    const result = await Filesystem.writeFile({
      path,
      directory: Directory.Documents,
      data: content,
      encoding: Encoding.UTF8,
      recursive: true,
    })
    try {
      if ((await Share.canShare()).value) {
        await Share.share({ files: [result.uri], title: fileName, dialogTitle: '保存或分享油迹备份' })
      }
    } catch {
      // The file is already saved; dismissing the share sheet is harmless.
    }
    return `已保存到 Documents/FuelTrack/${fileName}`
  }

  const url = URL.createObjectURL(new Blob([content], { type }))
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  link.click()
  window.setTimeout(() => URL.revokeObjectURL(url), 1000)
  return `已下载 ${fileName}`
}

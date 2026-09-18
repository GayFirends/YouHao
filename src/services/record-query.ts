import type { FuelRecord, RecordCursor, RecordListQuery, RecordPage, VehicleSummary } from '../types'

const DEFAULT_PAGE_SIZE = 50
const MAX_PAGE_SIZE = 200

export function compareRecords(left: FuelRecord, right: FuelRecord) {
  return (
    right.date.localeCompare(left.date) ||
    right.odometer - left.odometer ||
    right.createdAt.localeCompare(left.createdAt) ||
    right.id.localeCompare(left.id)
  )
}

function afterCursor(record: FuelRecord, cursor: RecordCursor) {
  return compareRecords(record, { ...record, ...cursor }) > 0
}

export function listRecordPage(records: FuelRecord[], query: RecordListQuery): RecordPage {
  const search = query.search?.trim().toLocaleLowerCase() || ''
  const limit = Math.min(MAX_PAGE_SIZE, Math.max(1, query.limit || DEFAULT_PAGE_SIZE))
  const filtered = records
    .filter((item) => query.includeDeleted || !item.deletedAt)
    .filter((item) => !query.vehicleId || item.vehicleId === query.vehicleId)
    .filter((item) => !query.month || item.date.startsWith(query.month))
    .filter((item) => !search || `${item.station} ${item.note} ${item.date}`.toLocaleLowerCase().includes(search))
    .filter((item) => !query.cursor || afterCursor(item, query.cursor))
    .sort(compareRecords)
  const items = filtered.slice(0, limit)
  const last = items.at(-1)
  return {
    items,
    nextCursor:
      filtered.length > limit && last ? { date: last.date, odometer: last.odometer, createdAt: last.createdAt, id: last.id } : null,
  }
}

export function summarizeVehicle(records: FuelRecord[], vehicleId: string): VehicleSummary {
  const active = records.filter((item) => item.vehicleId === vehicleId && !item.deletedAt)
  return {
    vehicleId,
    recordCount: active.length,
    totalPaid: active.reduce((sum, item) => sum + item.amount, 0),
    currentOdometer: active.length ? Math.max(...active.map((item) => item.odometer)) : null,
  }
}

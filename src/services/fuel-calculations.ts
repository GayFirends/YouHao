import type { FuelRecord } from '../types'
import { localDateKey } from './local-date'

export interface ConsumptionInterval {
  record: FuelRecord
  previousFullRecord: FuelRecord
  distance: number
  liters: number
  consumption: number
}

export function calculateConsumptionIntervals(records: FuelRecord[]): ConsumptionInterval[] {
  const ordered = [...records]
    .filter((record) => !record.deletedAt)
    .sort((left, right) => left.odometer - right.odometer || left.date.localeCompare(right.date) || left.createdAt.localeCompare(right.createdAt))

  const intervals: ConsumptionInterval[] = []
  let previousFullRecord: FuelRecord | undefined
  let accumulatedLiters = 0

  for (const record of ordered) {
    if (!previousFullRecord) {
      if (record.isFull) previousFullRecord = record
      continue
    }

    accumulatedLiters += record.liters
    if (!record.isFull) continue

    const distance = record.odometer - previousFullRecord.odometer
    const consumption = distance > 0 ? accumulatedLiters / distance * 100 : 0
    if (distance > 0 && consumption > 0 && consumption < 50) {
      intervals.push({ record, previousFullRecord, distance, liters: accumulatedLiters, consumption })
    }
    previousFullRecord = record
    accumulatedLiters = 0
  }

  return intervals
}

export function calculateAverageConsumption(intervals: ConsumptionInterval[]) {
  const totals = intervals.reduce((result, interval) => ({
    liters: result.liters + interval.liters,
    distance: result.distance + interval.distance,
  }), { liters: 0, distance: 0 })
  return totals.distance > 0 ? totals.liters / totals.distance * 100 : 0
}

export interface RecordDraft {
  id?: string
  date: string
  odometer: number
  liters: number
  amount: number
  isFull: boolean
}

export function fuelPriceSummary(liters: number, amount: number, pumpAmount: number) {
  const validLiters = Number.isFinite(liters) && liters > 0 ? liters : 0
  const paid = Number.isFinite(amount) && amount > 0 ? amount : 0
  const displayed = Number.isFinite(pumpAmount) && pumpAmount > 0 ? pumpAmount : 0
  return {
    pumpPricePerLiter: validLiters ? displayed / validLiters : 0,
    discountedPricePerLiter: validLiters ? paid / validLiters : 0,
    discountAmount: Math.max(displayed - paid, 0),
  }
}

export function fuelRecordWarnings(draft: RecordDraft, records: FuelRecord[], today = localDateKey()) {
  const warnings: string[] = []
  const others = records.filter((record) => !record.deletedAt && record.id !== draft.id)
  const before = others.filter((record) => record.date <= draft.date).sort((a, b) => b.date.localeCompare(a.date) || b.odometer - a.odometer)[0]
  const after = others.filter((record) => record.date > draft.date).sort((a, b) => a.date.localeCompare(b.date) || a.odometer - b.odometer)[0]
  const unitPrice = draft.liters > 0 ? draft.amount / draft.liters : 0

  if (draft.date > today) warnings.push('加油日期晚于今天')
  if (before && draft.odometer < before.odometer) warnings.push(`里程低于此前记录的 ${before.odometer.toLocaleString()} km`)
  if (after && draft.odometer > after.odometer) warnings.push(`里程高于之后记录的 ${after.odometer.toLocaleString()} km`)
  if (draft.isFull && others.some((record) => record.isFull && record.odometer === draft.odometer)) warnings.push('同一里程已经存在满箱记录')
  if (draft.liters > 150) warnings.push('加油量超过 150 L')
  if (unitPrice > 0 && (unitPrice < 2 || unitPrice > 20)) warnings.push(`计算单价为 ¥${unitPrice.toFixed(2)}/L`)
  return warnings
}

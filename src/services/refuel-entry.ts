import type { FuelRecord } from '../types'

export type NumberInput = number | ''
export type AmountField = 'liters' | 'amount' | 'pumpAmount' | 'pumpPrice'

export interface RefuelEntry {
  liters: NumberInput
  amount: NumberInput
  pumpAmount: NumberInput
  pumpPrice: NumberInput
  hasPumpOverride: boolean
  lastPumpSource: 'amount' | 'unitPrice'
}

const round = (value: number) => Number(value.toFixed(2))

export function createRefuelEntry(record?: Pick<FuelRecord, 'liters' | 'amount' | 'pumpAmount'> | null): RefuelEntry {
  const liters = record?.liters ?? ''
  const amount = record?.amount ?? ''
  const pumpAmount = record?.pumpAmount ?? amount
  return {
    liters,
    amount,
    pumpAmount,
    pumpPrice: Number(liters) > 0 ? round(Number(pumpAmount) / Number(liters)) : '',
    hasPumpOverride: !!record && pumpAmount !== amount,
    lastPumpSource: 'amount',
  }
}

export function effectivePumpAmount(entry: RefuelEntry) {
  return entry.pumpAmount === '' ? Number(entry.amount) : entry.pumpAmount
}

function calculatePrice(entry: RefuelEntry) {
  if (Number(entry.liters) > 0 && entry.pumpAmount !== '') {
    entry.pumpPrice = round(entry.pumpAmount / Number(entry.liters))
  }
}

export function resetPumpAmount(entry: RefuelEntry) {
  entry.hasPumpOverride = false
  entry.lastPumpSource = 'amount'
  entry.pumpAmount = entry.amount
  entry.pumpPrice = Number(entry.liters) > 0 && entry.amount !== ''
    ? round(entry.amount / Number(entry.liters))
    : ''
}

export function updateRefuelAmount(entry: RefuelEntry, field: AmountField, value: NumberInput) {
  entry[field] = value === '' || !Number.isFinite(value) ? '' : value
  if (field === 'amount') {
    if (!entry.hasPumpOverride) resetPumpAmount(entry)
    return
  }
  if (field === 'pumpAmount') {
    entry.hasPumpOverride = entry.pumpAmount !== ''
    entry.lastPumpSource = 'amount'
    if (entry.pumpAmount === '') return
    if (Number(entry.liters) > 0) calculatePrice(entry)
    else if (Number(entry.pumpPrice) > 0) entry.liters = round(entry.pumpAmount / Number(entry.pumpPrice))
    return
  }
  if (field === 'pumpPrice') {
    if (entry.pumpPrice === '') {
      entry.lastPumpSource = 'amount'
      return
    }
    entry.hasPumpOverride = true
    entry.lastPumpSource = 'unitPrice'
    if (Number(entry.liters) > 0) entry.pumpAmount = round(Number(entry.liters) * entry.pumpPrice)
    else if (entry.pumpPrice > 0 && entry.pumpAmount !== '') entry.liters = round(entry.pumpAmount / entry.pumpPrice)
    return
  }
  if (!entry.hasPumpOverride) {
    resetPumpAmount(entry)
  } else if (Number(entry.liters) > 0) {
    if (entry.lastPumpSource === 'unitPrice' && entry.pumpPrice !== '') {
      entry.pumpAmount = round(Number(entry.liters) * entry.pumpPrice)
    } else {
      calculatePrice(entry)
    }
  }
}

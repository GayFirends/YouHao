import { describe, expect, it } from 'vitest'
import { createRefuelEntry, effectivePumpAmount, resetPumpAmount, updateRefuelAmount } from '../refuel-entry'

describe('refuel entry amounts', () => {
  it('allows ordinary refueling without filling in displayed amounts', () => {
    const entry = createRefuelEntry()
    updateRefuelAmount(entry, 'liters', 40)
    updateRefuelAmount(entry, 'amount', 280)
    expect(effectivePumpAmount(entry)).toBe(280)
    expect(entry.pumpPrice).toBe(7)
    updateRefuelAmount(entry, 'amount', 288)
    expect(effectivePumpAmount(entry)).toBe(288)
    expect(entry.pumpPrice).toBe(7.2)
  })
  it('keeps an existing discount when paid amount or volume is edited', () => {
    const entry = createRefuelEntry({ liters: 40, amount: 280, pumpAmount: 320 })
    updateRefuelAmount(entry, 'amount', 270)
    expect(effectivePumpAmount(entry)).toBe(320)
    updateRefuelAmount(entry, 'liters', 50)
    expect(effectivePumpAmount(entry)).toBe(320)
    expect(entry.pumpPrice).toBe(6.4)
  })
  it('calculates volume from displayed amount and unit price in either order', () => {
    for (const order of ['amount-first', 'price-first']) {
      const entry = createRefuelEntry()
      if (order === 'amount-first') {
        updateRefuelAmount(entry, 'pumpAmount', 289.98)
        updateRefuelAmount(entry, 'pumpPrice', 8.1)
      } else {
        updateRefuelAmount(entry, 'pumpPrice', 8.1)
        updateRefuelAmount(entry, 'pumpAmount', 289.98)
      }
      expect(entry.liters).toBe(35.8)
      expect(effectivePumpAmount(entry)).toBe(289.98)
    }
  })
  it('retains a manually entered unit price when volume changes', () => {
    const entry = createRefuelEntry()
    updateRefuelAmount(entry, 'liters', 35.8)
    updateRefuelAmount(entry, 'pumpPrice', 8.1)
    updateRefuelAmount(entry, 'amount', 264.92)
    expect(effectivePumpAmount(entry)).toBe(289.98)
    updateRefuelAmount(entry, 'liters', 40)
    expect(effectivePumpAmount(entry)).toBe(324)
    expect(entry.amount).toBe(264.92)
  })
  it('supports explicitly removing a discount and a zero paid amount', () => {
    const entry = createRefuelEntry({ liters: 40, amount: 280, pumpAmount: 320 })
    resetPumpAmount(entry)
    expect(effectivePumpAmount(entry)).toBe(280)
    updateRefuelAmount(entry, 'amount', 0)
    expect(effectivePumpAmount(entry)).toBe(0)
    expect(entry.pumpPrice).toBe(0)
    updateRefuelAmount(entry, 'pumpAmount', '')
    expect(effectivePumpAmount(entry)).toBe(0)
  })
})

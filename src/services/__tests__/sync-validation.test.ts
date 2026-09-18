import { describe, expect, it } from 'vitest'
import { validateSyncPayload } from '../sync-validation'

const stamp = '2026-09-18T00:00:00.000Z'
const vehicle = {
  id: 'car',
  name: '车辆',
  plate: '',
  fuelType: '92#',
  initialOdometer: 0,
  createdAt: stamp,
  updatedAt: stamp,
  deletedAt: null,
}
const record = {
  id: 'record',
  vehicleId: 'car',
  date: '2026-09-18',
  odometer: 1000,
  liters: 40,
  amount: 280,
  pumpAmount: 300,
  pricePerLiter: 7,
  isFull: true,
  station: '',
  note: '',
  createdAt: stamp,
  updatedAt: stamp,
  deletedAt: null,
}
const valid = { version: 1, exportedAt: stamp, vehicles: [vehicle], records: [record] }

describe('sync payload validation failures', () => {
  it.each([
    [null, '根节点'],
    [{ ...valid, version: 2 }, '仅支持版本 1'],
    [{ ...valid, exportedAt: 'today' }, '不是有效时间'],
    [{ ...valid, vehicles: null }, '必须是数组'],
    [{ ...valid, vehicles: [null] }, '不是对象'],
    [{ ...valid, vehicles: [{ ...vehicle, id: '' }] }, '必须是非空文本'],
    [{ ...valid, vehicles: [{ ...vehicle, initialOdometer: -1 }] }, '必须是大于等于'],
    [{ ...valid, vehicles: [vehicle, vehicle] }, '车辆 ID 重复'],
    [{ ...valid, records: [null] }, '不是对象'],
    [{ ...valid, records: [{ ...record, isFull: 'yes' }] }, '必须是布尔值'],
    [{ ...valid, records: [{ ...record, date: '2026-02-30' }] }, '不是有效日期'],
    [{ ...valid, records: [{ ...record, liters: 0 }] }, '必须是大于等于'],
    [{ ...valid, records: [{ ...record, amount: Number.NaN }] }, '必须是大于等于'],
    [{ ...valid, records: [record, record] }, '记录 ID 重复'],
    [{ ...valid, records: [{ ...record, vehicleId: 'missing' }] }, '引用了不存在'],
  ])('rejects invalid input %#', (input, message) => {
    expect(() => validateSyncPayload(input)).toThrow(message)
  })
})

export interface Vehicle {
  id: string
  name: string
  plate: string
  fuelType: string
  initialOdometer: number
  createdAt: string
  updatedAt: string
  deletedAt: string | null
}

export interface FuelRecord {
  id: string
  vehicleId: string
  date: string
  odometer: number
  liters: number
  amount: number
  pricePerLiter: number
  isFull: boolean
  station: string
  note: string
  createdAt: string
  updatedAt: string
  deletedAt: string | null
}

export interface WebDavConfig {
  url: string
  username: string
  password: string
  fileName: string
}

export interface SyncPayload {
  version: 1
  exportedAt: string
  vehicles: Vehicle[]
  records: FuelRecord[]
}

export type ViewName = 'overview' | 'records' | 'vehicles' | 'settings'

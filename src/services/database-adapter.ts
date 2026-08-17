import type { FuelRecord, SyncPayload, Vehicle } from '../types'

export interface DatabaseAdapter {
  init(): Promise<void>
  vehicles(includeDeleted?: boolean): Promise<Vehicle[]>
  records(includeDeleted?: boolean): Promise<FuelRecord[]>
  saveVehicle(vehicle: Vehicle): Promise<void>
  saveRecord(record: FuelRecord): Promise<void>
  deleteVehicle(vehicleId: string, deletedAt: string): Promise<void>
  exportData(): Promise<SyncPayload>
  mergeData(remote: SyncPayload): Promise<void>
  flush(): Promise<void>
}

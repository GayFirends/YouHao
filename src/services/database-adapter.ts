import type {
  FuelRecord,
  RecordListQuery,
  RecordPage,
  SafetySnapshot,
  SchemaInfo,
  SyncConflict,
  SyncPayload,
  Vehicle,
  VehicleSummary,
} from '../types'

export interface DatabaseAdapter {
  init(): Promise<void>
  vehicles(includeDeleted?: boolean): Promise<Vehicle[]>
  records(includeDeleted?: boolean): Promise<FuelRecord[]>
  listRecords(query: RecordListQuery): Promise<RecordPage>
  getRecord(id: string): Promise<FuelRecord | undefined>
  getVehicleSummary(vehicleId: string): Promise<VehicleSummary>
  saveVehicle(vehicle: Vehicle): Promise<void>
  saveRecord(record: FuelRecord): Promise<void>
  deleteVehicle(vehicleId: string, deletedAt: string): Promise<void>
  exportData(): Promise<SyncPayload>
  mergeData(remote: SyncPayload): Promise<void>
  createSafetySnapshot(): Promise<SafetySnapshot>
  restoreSafetySnapshot(id: string): Promise<void>
  compactTombstones(cutoff: string): Promise<number>
  getSchemaInfo(): Promise<SchemaInfo>
  conflicts(): Promise<SyncConflict[]>
  saveConflicts(conflicts: SyncConflict[]): Promise<void>
  resolveConflict(id: string, resolution: 'local' | 'remote', merged?: Vehicle | FuelRecord): Promise<void>
  flush(): Promise<void>
}

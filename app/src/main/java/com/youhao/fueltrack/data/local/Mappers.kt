package com.youhao.fueltrack.data.local

import com.youhao.fueltrack.domain.model.FuelRecord
import com.youhao.fueltrack.domain.model.Vehicle

fun VehicleEntity.toDomain() = Vehicle(
    id = id,
    name = name,
    plate = plate,
    fuelType = fuelType,
    initialOdometer = initialOdometer,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
)

fun Vehicle.toEntity() = VehicleEntity(
    id = id,
    name = name,
    plate = plate,
    fuelType = fuelType,
    initialOdometer = initialOdometer,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
)

fun FuelRecordEntity.toDomain() = FuelRecord(
    id = id,
    vehicleId = vehicleId,
    date = date,
    odometer = odometer,
    liters = liters,
    amount = amount,
    pumpAmount = pumpAmount,
    pricePerLiter = pricePerLiter,
    isFull = isFull,
    station = station,
    note = note,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
)

fun FuelRecord.toEntity() = FuelRecordEntity(
    id = id,
    vehicleId = vehicleId,
    date = date,
    odometer = odometer,
    liters = liters,
    amount = amount,
    pumpAmount = pumpAmount,
    pricePerLiter = pricePerLiter,
    isFull = isFull,
    station = station,
    note = note,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
)

package com.example.obdlogger

import kotlin.math.max
import kotlin.math.min

data class CarProfile(
    val make: String,
    val model: String,
    val year: Int,
    val fuel: String,          // "Petrol" or "Diesel"
    val displacementL: Double, // engine size in litres, e.g. 1.2
    val fuelPrice: Double      // price per litre, 0 if unknown
) {
    val label get() = "$year $make $model"
}

/**
 * Fuel-use estimation. Order of preference:
 *  1. PID 5E (engine fuel rate), if the car reports it: most accurate.
 *  2. MAF sensor (PID 10): air mass / air-fuel ratio.
 *  3. Speed-density from MAP, RPM and intake temp, using engine size and an assumed 85% volumetric efficiency.
 * Estimates are approximate. Petrol is reasonable; diesels run lean, so MAF/MAP-based numbers overstate use.
 */
object Fuel {
    fun lPerHour(fuel: String, displacementL: Double, v: Map<String, Double>): Double? {
        v["5E"]?.let { return it }
        val maf = v["10"] ?: run {
            val map = v["0B"]; val rpm = v["0C"]; val iat = v["0F"]
            if (map == null || rpm == null || iat == null || displacementL <= 0) return null
            val imap = rpm * map / (iat + 273.15) / 2.0
            imap / 60.0 * 0.85 * displacementL * 28.97 / 8.314
        }
        val afr = if (fuel == "Diesel") 14.5 else 14.7
        val density = if (fuel == "Diesel") 832.0 else 745.0 // g/L
        return maf * 3600.0 / (afr * density)
    }

    fun co2PerLitre(fuel: String) = if (fuel == "Diesel") 2.68 else 2.31 // kg CO2 per litre burned
}

data class TripStats(
    val distanceKm: Double,
    val durationMin: Double,
    val avgMovingKmh: Double?,
    val maxKmh: Double,
    val idleMin: Double,
    val fuelL: Double?,
    val lPer100: Double?,
    val cost: Double?,
    val co2Kg: Double?
) {
    companion object {
        fun compute(trip: Trip, samples: List<Sample>): TripStats {
            val cur = mutableMapOf<String, Double>()
            var last: Long? = null
            var distance = 0.0; var fuel = 0.0; var fuelKnown = false
            var movingMs = 0L; var idleMs = 0L; var maxSpeed = 0.0
            for (s in samples) {
                last?.let { l ->
                    val dt = min(s.ts - l, 5000L) // ignore gaps, e.g. dropped connection
                    if (dt > 0) {
                        cur["0D"]?.let { spd ->
                            distance += spd * dt / 3.6e6
                            if (spd > 1) movingMs += dt
                            else if ((cur["0C"] ?: 0.0) > 0) idleMs += dt
                        }
                        Fuel.lPerHour(trip.fuelType, trip.displacementL, cur)?.let {
                            fuel += it * dt / 3.6e6; fuelKnown = true
                        }
                    }
                }
                cur[s.pid] = s.value
                last = s.ts
                if (s.pid == "0D") maxSpeed = max(maxSpeed, s.value)
            }
            val end = trip.endMs ?: last ?: trip.startMs
            val fuelL = if (fuelKnown) fuel else null
            return TripStats(
                distanceKm = distance,
                durationMin = (end - trip.startMs) / 60000.0,
                avgMovingKmh = if (movingMs > 0) distance / (movingMs / 3.6e6) else null,
                maxKmh = maxSpeed,
                idleMin = idleMs / 60000.0,
                fuelL = fuelL,
                lPer100 = if (fuelL != null && distance > 0.1) fuelL / distance * 100 else null,
                cost = if (fuelL != null && trip.fuelPrice > 0) fuelL * trip.fuelPrice else null,
                co2Kg = fuelL?.let { it * Fuel.co2PerLitre(trip.fuelType) }
            )
        }
    }
}

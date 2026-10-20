package com.virtualrun.app.model

import android.content.Context

/** Private local settings/results; these are not a health record or a sensor data provider. */
object SimulationStorage {
    private const val FILE_NAME = "local_simulation_metrics"

    fun loadProfile(context: Context): SimulationProfile {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        return runCatching {
            SimulationProfile(
                strideLengthCm = Double.fromBits(prefs.getLong("profile_stride", SimulationProfile().strideLengthCm.toBits())),
                caloriesPerKm = Double.fromBits(prefs.getLong("profile_calories", 60.0.toBits()))
            )
        }.getOrDefault(SimulationProfile())
    }

    fun saveProfile(context: Context, profile: SimulationProfile) {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).edit()
            .putLong("profile_stride", profile.strideLengthCm.toBits())
            .putLong("profile_calories", profile.caloriesPerKm.toBits())
            .apply()
    }

    fun loadSnapshot(context: Context): SimulationMetrics {
        val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
        return runCatching {
            val profile = SimulationProfile(
                strideLengthCm = Double.fromBits(prefs.getLong("result_stride", 100.0.toBits())),
                caloriesPerKm = Double.fromBits(prefs.getLong("result_calories", 60.0.toBits()))
            )
            SimulationMetricsCalculator.calculate(
                Double.fromBits(prefs.getLong("result_distance", 0.0.toBits())),
                prefs.getLong("result_elapsed", 0L),
                profile
            )
        }.getOrDefault(SimulationMetrics())
    }

    fun saveSnapshot(context: Context, metrics: SimulationMetrics, profile: SimulationProfile) {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).edit()
            .putLong("result_distance", metrics.distanceMeters.toBits())
            .putLong("result_elapsed", metrics.elapsedMillis)
            .putLong("result_stride", profile.strideLengthCm.toBits())
            .putLong("result_calories", profile.caloriesPerKm.toBits())
            .apply()
    }

    fun clearSnapshot(context: Context) {
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).edit()
            .remove("result_distance").remove("result_elapsed")
            .remove("result_stride").remove("result_calories")
            .apply()
    }
}

package com.virtualrun.app.map

/**
 * 地图类型枚举
 */
enum class MapType(val displayName: String, val description: String) {
    OSM("OpenStreetMap", "开源地图，无需 API Key"),
    GOOGLE("Google 地图", "需要 Google Play 服务"),
    BAIDU("百度地图", "国内推荐使用"),
    AMAP("高德地图", "国内推荐使用"),
    NONE("无地图模式", "纯坐标输入，无需地图");

    companion object {
        fun getDefault(): MapType = OSM
    }
}

/**
 * 坐标系统类型
 */
enum class CoordinateSystem {
    WGS84,      // GPS 坐标，国际标准
    GCJ02,      // 国测局坐标，Google/高德使用
    BD09        // 百度坐标
}

/**
 * 地图坐标转换工具
 */
object CoordinateConverter {
    private const val X_PI = 3.14159265358979324 * 3000.0 / 180.0
    private const val PI = 3.1415926535897932384626
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323

    /**
     * WGS84 转 GCJ02（火星坐标）
     */
    fun wgs84ToGcj02(lat: Double, lng: Double): Pair<Double, Double> {
        if (outOfChina(lat, lng)) {
            return Pair(lat, lng)
        }
        var dLat = transformLat(lng - 105.0, lat - 35.0)
        var dLng = transformLng(lng - 105.0, lat - 35.0)
        val radLat = lat / 180.0 * PI
        var magic = Math.sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = Math.sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLng = (dLng * 180.0) / (A / sqrtMagic * Math.cos(radLat) * PI)
        val mgLat = lat + dLat
        val mgLng = lng + dLng
        return Pair(mgLat, mgLng)
    }

    /**
     * GCJ02 转 WGS84（迭代法，精度约 0.00001 度 ≈ 1 米）
     */
    fun gcj02ToWgs84(gcjLat: Double, gcjLng: Double): Pair<Double, Double> {
        if (outOfChina(gcjLat, gcjLng)) {
            return Pair(gcjLat, gcjLng)
        }
        // 迭代逼近：WGS-84 = GCJ-02 - offset(WGS-84)
        var wgsLat = gcjLat
        var wgsLng = gcjLng
        repeat(5) {
            val gcj = wgs84ToGcj02(wgsLat, wgsLng)
            val dLat = gcj.first - gcjLat
            val dLng = gcj.second - gcjLng
            wgsLat -= dLat
            wgsLng -= dLng
        }
        return Pair(wgsLat, wgsLng)
    }

    /**
     * GCJ02 转 BD09
     */
    fun gcj02ToBd09(lat: Double, lng: Double): Pair<Double, Double> {
        val z = Math.sqrt(lng * lng + lat * lat) + 0.00002 * Math.sin(lat * X_PI)
        val theta = Math.atan2(lat, lng) + 0.000003 * Math.cos(lng * X_PI)
        val bdLng = z * Math.cos(theta) + 0.0065
        val bdLat = z * Math.sin(theta) + 0.006
        return Pair(bdLat, bdLng)
    }

    /**
     * BD09 转 GCJ02
     */
    fun bd09ToGcj02(lat: Double, lng: Double): Pair<Double, Double> {
        val x = lng - 0.0065
        val y = lat - 0.006
        val z = Math.sqrt(x * x + y * y) - 0.00002 * Math.sin(y * X_PI)
        val theta = Math.atan2(y, x) - 0.000003 * Math.cos(x * X_PI)
        val ggLng = z * Math.cos(theta)
        val ggLat = z * Math.sin(theta)
        return Pair(ggLat, ggLng)
    }

    /**
     * WGS84 转 BD09
     */
    fun wgs84ToBd09(lat: Double, lng: Double): Pair<Double, Double> {
        val (gcjLat, gcjLng) = wgs84ToGcj02(lat, lng)
        return gcj02ToBd09(gcjLat, gcjLng)
    }

    /**
     * BD09 转 WGS84
     */
    fun bd09ToWgs84(lat: Double, lng: Double): Pair<Double, Double> {
        val (gcjLat, gcjLng) = bd09ToGcj02(lat, lng)
        return gcj02ToWgs84(gcjLat, gcjLng)
    }

    private fun outOfChina(lat: Double, lng: Double): Boolean {
        return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271
    }

    private fun transformLat(lng: Double, lat: Double): Double {
        var ret = -100.0 + 2.0 * lng + 3.0 * lat + 0.2 * lat * lat +
                0.1 * lng * lat + 0.2 * Math.sqrt(Math.abs(lng))
        ret += (20.0 * Math.sin(6.0 * lng * PI) + 20.0 * Math.sin(2.0 * lng * PI)) * 2.0 / 3.0
        ret += (20.0 * Math.sin(lat * PI) + 40.0 * Math.sin(lat / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * Math.sin(lat / 12.0 * PI) + 320 * Math.sin(lat * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(lng: Double, lat: Double): Double {
        var ret = 300.0 + lng + 2.0 * lat + 0.1 * lng * lng +
                0.1 * lng * lat + 0.1 * Math.sqrt(Math.abs(lng))
        ret += (20.0 * Math.sin(6.0 * lng * PI) + 20.0 * Math.sin(2.0 * lng * PI)) * 2.0 / 3.0
        ret += (20.0 * Math.sin(lng * PI) + 40.0 * Math.sin(lng / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * Math.sin(lng / 12.0 * PI) + 300.0 * Math.sin(lng / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }
}

/**
 * 检查是否有 Google Play 服务
 */
fun hasGooglePlayServices(context: android.content.Context): Boolean {
    return try {
        val info = context.packageManager.getPackageInfo("com.google.android.gms", 0)
        info != null
    } catch (e: Exception) {
        false
    }
}

/**
 * 自动选择最佳地图类型
 */
fun autoSelectMapType(context: android.content.Context): MapType {
    return when {
        hasGooglePlayServices(context) -> MapType.GOOGLE
        else -> MapType.BAIDU  // 默认使用百度地图
    }
}

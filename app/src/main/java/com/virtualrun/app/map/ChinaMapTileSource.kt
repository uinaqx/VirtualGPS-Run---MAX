package com.virtualrun.app.map

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex

const val CHINA_MAP_MIN_ZOOM_LEVEL = 0

// AutoNavi returns a tiny blank placeholder tile from z19 onward for this raster endpoint.
// Keep network requests capped at z18, then let osmdroid crop and enlarge cached parent tiles
// for a few extra display levels instead of requesting those blank placeholders.
const val CHINA_MAP_NATIVE_MAX_ZOOM_LEVEL = 18
const val CHINA_MAP_MAX_ZOOM_LEVEL = 22

/**
 * 高德地图 (AMap/AutoNavi) 瓦片源
 * 国内直连非常快，无需 VPN
 */
class ChinaMapTileSource : OnlineTileSourceBase(
    "AMap",
    CHINA_MAP_MIN_ZOOM_LEVEL, CHINA_MAP_NATIVE_MAX_ZOOM_LEVEL, 256, ".png",
    arrayOf(
        "https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8",
        "https://webrd02.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8",
        "https://webrd03.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8",
        "https://webrd04.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8"
    )
) {
    override fun getTileURLString(pTileIndex: Long): String {
        return "${baseUrl}&x=${MapTileIndex.getX(pTileIndex)}&y=${MapTileIndex.getY(pTileIndex)}&z=${MapTileIndex.getZoom(pTileIndex)}"
    }
}

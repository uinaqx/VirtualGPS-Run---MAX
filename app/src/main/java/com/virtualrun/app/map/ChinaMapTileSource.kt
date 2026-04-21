package com.virtualrun.app.map

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex

/**
 * 高德地图 (AMap/AutoNavi) 瓦片源
 * 国内直连非常快，无需 VPN
 */
class ChinaMapTileSource : OnlineTileSourceBase(
    "AMap",
    0, 20, 256, ".png",
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

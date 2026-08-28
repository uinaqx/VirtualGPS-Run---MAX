package com.virtualrun.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.MapTileIndex

class ChinaMapTileSourceTest {

    @Test
    fun rasterTileSourceStopsBeforeAutoNaviBlankPlaceholderLevels() {
        val source = ChinaMapTileSource()

        assertEquals(CHINA_MAP_MIN_ZOOM_LEVEL, source.minimumZoomLevel)
        assertEquals(CHINA_MAP_NATIVE_MAX_ZOOM_LEVEL, source.maximumZoomLevel)
        assertEquals(22, CHINA_MAP_MAX_ZOOM_LEVEL)
        assertTrue(CHINA_MAP_MAX_ZOOM_LEVEL > CHINA_MAP_NATIVE_MAX_ZOOM_LEVEL)

        val tileIndex = MapTileIndex.getTileIndex(CHINA_MAP_NATIVE_MAX_ZOOM_LEVEL, 215_837, 99_333)
        val url = source.getTileURLString(tileIndex)
        assertTrue(url.contains("x=215837"))
        assertTrue(url.contains("y=99333"))
        assertTrue(url.contains("z=18"))
    }
}

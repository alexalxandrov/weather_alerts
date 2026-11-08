package com.alexalx.weatheralerts

import org.junit.Assert.assertEquals
import org.junit.Test

class AlertClassificationTest {
    @Test fun classifiesMajorHazardFamilies() {
        assertEquals("Severe Storm", AlertEngine.classify("Tornado Warning"))
        assertEquals("Tsunami", AlertEngine.classify("Tsunami Warning"))
        assertEquals("Earthquake", AlertEngine.classify("Earthquake M 6.2"))
        assertEquals("Flood", AlertEngine.classify("Flash Flood Warning"))
        assertEquals("Wildfire", AlertEngine.classify("Wildfire evacuation notice"))
        assertEquals("Volcano", AlertEngine.classify("Volcanic eruption"))
        assertEquals("Radiological / Nuclear", AlertEngine.classify("Radiological emergency"))
        assertEquals("Chemical", AlertEngine.classify("Hazardous materials incident"))
        assertEquals("Health", AlertEngine.classify("Disease outbreak"))
        assertEquals("Landslide", AlertEngine.classify("Landslide warning"))
        assertEquals("Avalanche", AlertEngine.classify("Avalanche danger"))
        assertEquals("Winter Weather", AlertEngine.classify("Blizzard warning"))
        assertEquals("Extreme Heat", AlertEngine.classify("Excessive heat warning"))
        assertEquals("Extreme Cold", AlertEngine.classify("Extreme cold warning"))
        assertEquals("Air Quality", AlertEngine.classify("Air quality alert"))
        assertEquals("Space Weather", AlertEngine.classify("Geomagnetic storm watch"))
        assertEquals("Public Safety", AlertEngine.classify("Evacuation order"))
    }
}

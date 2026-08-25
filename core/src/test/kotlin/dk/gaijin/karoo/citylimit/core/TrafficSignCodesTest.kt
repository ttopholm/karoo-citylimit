package dk.gaijin.karoo.citylimit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficSignCodesTest {
    @Test
    fun `danish entry sign is an entry`() {
        val sides = TrafficSignCodes.classify(mapOf("traffic_sign" to "DK:E55", "name" to "Hillerød"))
        assertTrue(sides.entry)
        assertFalse(sides.exit)
        assertTrue(sides.directional)
    }

    @Test
    fun `danish crossed out sign is an exit only`() {
        val sides = TrafficSignCodes.classify(mapOf("traffic_sign" to "DK:E56"))
        assertFalse(sides.entry)
        assertTrue(sides.exit)
    }

    @Test
    fun `generic city limit node is both, without direction`() {
        val sides = TrafficSignCodes.classify(mapOf("traffic_sign" to "city_limit", "name" to "Nørre Herlev"))
        assertTrue(sides.entry)
        assertTrue(sides.exit)
        assertFalse(sides.directional)
    }

    @Test
    fun `city limit with begin or end refines the generic node`() {
        val begin = TrafficSignCodes.classify(mapOf("traffic_sign" to "city_limit", "city_limit" to "begin"))
        assertTrue(begin.entry)
        assertFalse(begin.exit)
        assertTrue(begin.directional)

        val end = TrafficSignCodes.classify(mapOf("traffic_sign" to "city_limit", "city_limit" to "end"))
        assertFalse(end.entry)
        assertTrue(end.exit)
    }

    @Test
    fun `direction suffixed keys are both read`() {
        val sides = TrafficSignCodes.classify(
            mapOf(
                "traffic_sign:forward" to "DK:E55",
                "traffic_sign:backward" to "DK:E56",
            ),
        )
        assertTrue(sides.entry)
        assertTrue(sides.exit)
        assertTrue(sides.directional)
    }

    @Test
    fun `sign parameters and lists are handled`() {
        val sides = TrafficSignCodes.classify(mapOf("traffic_sign" to "DE:310[Berlin];DE:1000-30"))
        assertTrue(sides.entry)
        assertFalse(sides.exit)
    }

    @Test
    fun `matching is case insensitive`() {
        assertTrue(TrafficSignCodes.classify(mapOf("traffic_sign" to "dk:e55")).entry)
        assertTrue(TrafficSignCodes.classify(mapOf("traffic_sign" to "NL:H01")).entry)
    }

    @Test
    fun `other european entry and exit codes`() {
        val entries = listOf(
            "DE:310", "AT:53-17a", "CH:4.27", "SE:E5", "NO:365", "FI:571",
            "NL:H01", "BE:F1a", "FR:EB10", "ES:S-500", "PL:D-42", "CZ:IZ4a",
        )
        for (code in entries) {
            val sides = TrafficSignCodes.classify(mapOf("traffic_sign" to code))
            assertTrue("$code should be an entry sign", sides.entry)
            assertFalse("$code should not be an exit sign", sides.exit)
        }
        val exits = listOf(
            "DE:311", "AT:53-17b", "CH:4.28", "SE:E6", "NO:366", "FI:572",
            "NL:H02", "BE:F3a", "FR:EB20", "ES:S-510", "PL:D-43", "CZ:IZ4b",
        )
        for (code in exits) {
            val sides = TrafficSignCodes.classify(mapOf("traffic_sign" to code))
            assertFalse("$code should not be an entry sign", sides.entry)
            assertTrue("$code should be an exit sign", sides.exit)
        }
    }

    @Test
    fun `unrelated signs are ignored`() {
        assertEquals(SignSides.NONE, TrafficSignCodes.classify(mapOf("traffic_sign" to "DK:C55")))
        assertEquals(SignSides.NONE, TrafficSignCodes.classify(mapOf("highway" to "stop")))
        assertEquals(SignSides.NONE, TrafficSignCodes.classify(emptyMap()))
    }
}

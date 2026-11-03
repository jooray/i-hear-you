package app.ihearyou.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlatformTest {

    @Test
    fun versionSelectsPlatform() {
        assertEquals(Platform.V5_1, Platform.of(5, 1)) // the tested Nexia pair reports 05 01
        assertEquals(Platform.V5_0, Platform.of(5, 0))
        assertEquals(Platform.V4, Platform.of(4, 0))
        assertEquals(Platform.V3, Platform.of(3, 0))
        assertEquals(Platform.V2, Platform.of(2, 0))
        assertEquals(Platform.V1, Platform.of(1, 0))
        assertNull(Platform.of(6, 0))
        assertNull(Platform.of(0, 0))
    }

    @Test
    fun dfuAndButtonNeverWritable() {
        for (p in Platform.entries) {
            assertFalse(p.startDfu in p.writable, "${p.label}: StartDFU writable")
            p.pushButton?.let { assertFalse(it in p.writable, "${p.label}: push button writable") }
            p.hiState?.let { assertFalse(it in p.writable, "${p.label}: HI state writable") }
            assertTrue(Handles.ACTIVE_PROGRAM in p.writable)
        }
    }

    @Test
    fun olderPlatformsUseOtherButtonHandle() {
        // on protocols 2 and 3 handle 53 starts a firmware update
        assertEquals(53, Platform.V3.startDfu)
        assertEquals(57, Platform.V3.pushButton)
        assertNull(Platform.V4.tapConfig)
        assertEquals(66, Platform.V5_1.tapConfig)
    }
}

package com.a1111remote.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PcControlClientTest {
    @Test fun `control port reuses private A1111 PC address`() {
        assertEquals("http://192.168.1.5:42001",
            PcControlClient.controllerUrl("http://192.168.1.5:42000"))
        assertEquals("http://100.100.1.5:42001",
            PcControlClient.controllerUrl("http://100.100.1.5:42000"))
    }

    @Test fun `does not send control token to public HTTP hosts`() {
        assertThrows(IllegalArgumentException::class.java) {
            PcControlClient.controllerUrl("http://8.8.8.8:42000")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PcControlClient.controllerUrl("https://example.com:42000")
        }
    }

    @Test fun `wake MAC validation`() {
        assertEquals("AABBCCDDEEFF",
            PcControlClient.parseMac("aa:bb:cc:dd:ee:ff").joinToString("") { "%02X".format(it) })
        assertThrows(IllegalArgumentException::class.java) {
            PcControlClient.parseMac("not-a-mac")
        }
    }
}

package com.packetcapture.automation

import org.junit.Assert.*
import org.junit.Test

class AutomationAccessTest {
    @Test fun onlyAppRootAndShellMayRead() {
        assertTrue(AutomationAccess.allowed(10123, 10123))
        assertTrue(AutomationAccess.allowed(2000, 10123))
        assertTrue(AutomationAccess.allowed(0, 10123))
        assertFalse(AutomationAccess.allowed(10124, 10123))
        assertFalse(AutomationAccess.allowed(102000, 10123))
    }
}

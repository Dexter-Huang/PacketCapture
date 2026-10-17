package com.packetcapture.automation

internal object AutomationAccess {
    fun allowed(callerUid: Int, appUid: Int): Boolean = callerUid == appUid || callerUid == 2000 || callerUid == 0
}

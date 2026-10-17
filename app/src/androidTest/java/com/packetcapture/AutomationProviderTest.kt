package com.packetcapture

import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FileNotFoundException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Runs on a test installation; does not send model prompts or change capture configuration. */
class AutomationProviderTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as PacketCaptureApp).container

    @Test fun optInIsRequiredAndStatusHasVersionedJson() = runBlocking {
        val original = container.settings.settings.first()
        val uri = Uri.parse("content://com.packetcapture.automation/v1/status")
        try {
            container.settings.update(original.copy(adbExportEnabled = false))
            try {
                context.contentResolver.openInputStream(uri)?.close()
                fail("Disabled export must deny reads")
            } catch (_: SecurityException) { }
            container.settings.update(original.copy(adbExportEnabled = true))
            val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            val status = JSONObject(text)
            assertEquals(1, status.getInt("schemaVersion"))
            assertTrue(status.getLong("deviceTime") > 0)
            assertTrue(status.has("capture"))
            try {
                context.contentResolver.openOutputStream(uri)?.close()
                fail("Writes must be denied")
            } catch (_: FileNotFoundException) { }
        } finally {
            container.settings.update(original)
        }
    }
}

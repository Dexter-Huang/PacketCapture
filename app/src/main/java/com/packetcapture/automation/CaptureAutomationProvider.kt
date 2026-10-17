package com.packetcapture.automation

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import com.packetcapture.BuildConfig
import com.packetcapture.PacketCaptureApp
import com.packetcapture.llm.AnswerJson
import com.packetcapture.llm.AnswerSelector
import com.packetcapture.llm.PlatformCatalog
import java.io.FileNotFoundException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Read-only v1 bridge for a locally authorized ADB client. No network listener or task execution. */
class CaptureAutomationProvider : ContentProvider() {
    private val container get() = (context!!.applicationContext as PacketCaptureApp).container
    override fun onCreate() = true

    private fun authorize() {
        val caller = Binder.getCallingUid()
        if (!AutomationAccess.allowed(caller, Process.myUid())) throw SecurityException("ADB shell access only")
        val enabled = runBlocking(Dispatchers.IO) { withTimeout(5_000) { container.settings.settings.first().adbExportEnabled } }
        if (!enabled) throw SecurityException("Enable ADB result export in Packet Capture settings")
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        authorize()
        if (mode != "r") throw FileNotFoundException("Read-only interface")
        val path = uri.pathSegments
        val route = when {
            path == listOf("v1", "status") -> "status"
            path == listOf("v1", "sessions") -> "sessions"
            path.size == 4 && path[0] == "v1" && path[1] == "sessions" && path[3] == "answers" -> "answers"
            else -> throw FileNotFoundException("Unknown v1 resource")
        }
        val allowed = if (route == "answers") setOf("provider", "prompt", "startedAfter", "exchangeId") else emptySet()
        require(uri.queryParameterNames.all { it in allowed && uri.getQueryParameters(it).size == 1 }) { "Unknown or repeated selector" }
        val selector = AnswerSelector(provider = uri.getQueryParameter("provider"), prompt = uri.getQueryParameter("prompt"),
            startedAfter = uri.getQueryParameter("startedAfter")?.let { it.toLongOrNull() ?: error("Invalid startedAfter") } ?: 0,
            exchangeId = uri.getQueryParameter("exchangeId"))
        return openPipeHelper(uri, "application/json", null, Unit) { pipe, _, _, _, _ ->
            ParcelFileDescriptor.AutoCloseOutputStream(pipe).bufferedWriter(Charsets.UTF_8).use { writer ->
                val json = try {
                    runBlocking(Dispatchers.IO) {
                        withTimeout(30_000) {
                            container.ready.await()
                            // Recheck after the pipe is opened; revoking the setting denies subsequent reads.
                            check(container.settings.settings.first().adbExportEnabled) { "Export disabled" }
                            AnswerJson.encode(when (route) {
                                "status" -> mapOf("schemaVersion" to 1, "appVersion" to BuildConfig.VERSION_NAME, "deviceTime" to System.currentTimeMillis(),
                                    "capture" to container.controller.state.value, "platforms" to PlatformCatalog.platforms)
                                "sessions" -> mapOf("schemaVersion" to 1, "sessions" to container.repository.sessions().first())
                                else -> container.answers.read(path[2], selector)
                            })
                        }
                    }
                } catch (_: Exception) {
                    // Do not leak disk paths or private body snippets through exception messages.
                    AnswerJson.encode(mapOf("schemaVersion" to 1, "error" to "export_unavailable"))
                }
                writer.write(json)
            }
        }
    }

    override fun getType(uri: Uri): String { authorize(); return "application/json" }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? =
        throw UnsupportedOperationException("Use content read")
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("Read only")
}

package com.packetcapture.scheduler

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.google.gson.Gson
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 设备凭据、租约和待回传回答整体加密并原子保存；不进入备份或 ADB 结果接口。 */
internal class SchedulerJournal(context: Context, name: String = "geo-scheduler.enc") {
    init { require(name.matches(Regex("[a-zA-Z0-9.-]+"))) }
    private val file = AtomicFile(File(context.noBackupFilesDir, name))
    private val archiveDirectory = File(context.noBackupFilesDir, "geo-task-archives")
    private val gson = Gson()
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("geo-scheduler-v1", null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("geo-scheduler-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(): SchedulerRecord {
        if (!file.baseFile.exists()) return SchedulerRecord()
        val bytes = file.readFully()
        require(bytes.size > 28) { "调度记录损坏，请保留数据并检查" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return gson.fromJson(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8), SchedulerRecord::class.java)
    }
    fun write(record: SchedulerRecord) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.iv + cipher.doFinal(gson.toJson(record).toByteArray(Charsets.UTF_8))
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (e: Exception) { file.failWrite(output); throw e }
    }
    fun archive() {
        check(archiveDirectory.mkdirs() || archiveDirectory.isDirectory)
        val archive = AtomicFile(File(archiveDirectory, "${java.util.UUID.randomUUID()}.enc"))
        val output = archive.startWrite()
        try { output.write(file.readFully()); archive.finishWrite(output) }
        catch (e: Exception) { archive.failWrite(output); throw e }
    }
}

internal data class SchedulerRecord(
    val serverUrl: String = "", val token: String = "", val certificateSha256: String = "",
    val deviceId: String = "", val claimRequestId: String = "",
    val job: MobileJob? = null, val sessionId: String? = null, val startedAfter: Long = 0,
    val outbox: String? = null,
)

data class MobileJob(
    val jobId: String, val batchId: String, val taskId: String, val provider: String, val prompt: String,
    val status: String, val leaseToken: String, val leaseUntil: String,
)

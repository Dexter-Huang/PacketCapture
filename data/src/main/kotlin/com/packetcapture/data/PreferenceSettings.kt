package com.packetcapture.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.packetcapture.core.*
import kotlinx.coroutines.flow.map

private val Context.capturePreferences by preferencesDataStore("capture-settings")
class PreferenceSettings(context: Context) : SettingsRepository {
    private val store = context.applicationContext.capturePreferences
    private val packages = stringSetPreferencesKey("packages")
    private val https = booleanPreferencesKey("https")
    private val blockUdp443 = booleanPreferencesKey("block-udp-443")
    private val bypass = stringSetPreferencesKey("bypass")
    private val tlsHosts = stringPreferencesKey("tls-host-overrides")
    private val theme = stringPreferencesKey("theme")
    private val adbExport = booleanPreferencesKey("adb-export-enabled")
    private val proxyEnabled = booleanPreferencesKey("upstream-proxy-enabled")
    private val proxyPort = intPreferencesKey("upstream-proxy-port")
    private val geoServer = stringPreferencesKey("geo-server-url")
    private val deviceName = stringPreferencesKey("geo-device-name")
    private val pollInterval = intPreferencesKey("geo-poll-interval-seconds")
    private val providers = stringSetPreferencesKey("geo-planned-providers")
    override val settings = store.data.map {
        AppSettings(CaptureConfig(packages = it[packages] ?: emptySet(), decryptHttps = it[https] ?: true,
            bypassDomains = it[bypass] ?: emptySet(), blockUdp443 = it[blockUdp443] ?: false,
            tlsHostOverrides = runCatching { TlsHostOverrides.parse(it[tlsHosts].orEmpty()) }.getOrDefault(emptyMap()),
            upstreamProxy = UpstreamProxy(it[proxyEnabled] ?: false, it[proxyPort]?.takeIf { port -> port in 1..65535 } ?: 10808)),
            runCatching { ThemeMode.valueOf(it[theme] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
            adbExportEnabled = it[adbExport] ?: false,
            scheduler = SchedulerSettings(it[geoServer].orEmpty(), it[deviceName].orEmpty(),
                it[pollInterval]?.takeIf { seconds -> seconds in 10..300 } ?: 15,
                it[providers] ?: emptySet()))
    }
    override suspend fun update(settings: AppSettings) {
        store.edit {
            it[packages] = settings.capture.packages
            it[https] = settings.capture.decryptHttps
            it[blockUdp443] = settings.capture.blockUdp443
            it[bypass] = settings.capture.bypassDomains
            it[tlsHosts] = TlsHostOverrides.format(settings.capture.tlsHostOverrides)
            it[theme] = settings.theme.name
            it[adbExport] = settings.adbExportEnabled
            it[proxyEnabled] = settings.capture.upstreamProxy.enabled
            it[proxyPort] = settings.capture.upstreamProxy.port
            it[geoServer] = settings.scheduler.serverUrl
            it[deviceName] = settings.scheduler.deviceName
            it[pollInterval] = settings.scheduler.pollIntervalSeconds
            it[providers] = settings.scheduler.providers
        }
    }
}

package com.hamuk.swimpro.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.dataStore by preferencesDataStore(name = "settings")

/** "auto" = ask the Gemini API for the newest Flash model available to this key. */
const val DEFAULT_GEMINI_MODEL = "auto"

const val DEFAULT_DAILY_LIMIT = 200

class SettingsStore(private val context: Context) {
    private val modelKey = stringPreferencesKey("gemini_model")
    private val onboardedKey = booleanPreferencesKey("onboarded")
    private val lastSyncKey = longPreferencesKey("last_sync_ms")
    private val lastSyncMsgKey = stringPreferencesKey("last_sync_msg")

    val model: Flow<String> = context.dataStore.data.map { it[modelKey] ?: DEFAULT_GEMINI_MODEL }
    val onboarded: Flow<Boolean> = context.dataStore.data.map { it[onboardedKey] ?: false }
    val lastSyncMs: Flow<Long?> = context.dataStore.data.map { it[lastSyncKey] }
    val lastSyncMessage: Flow<String?> = context.dataStore.data.map { it[lastSyncMsgKey] }

    suspend fun model(): String = model.first()

    private val resolvedKey = stringPreferencesKey("gemini_resolved_model")

    /** Model picked automatically last time (cached so we don't list models on every message). */
    suspend fun resolvedModel(): String? = context.dataStore.data.first()[resolvedKey]

    suspend fun setResolvedModel(value: String?) = context.dataStore.edit {
        if (value == null) it.remove(resolvedKey) else it[resolvedKey] = value
    }
    suspend fun lastSync(): Long? = lastSyncMs.first()

    private val dailyLimitKey = androidx.datastore.preferences.core.intPreferencesKey("gemini_daily_limit")
    private val alertsKey = booleanPreferencesKey("gemini_usage_alerts")
    private val notifiedKey = stringPreferencesKey("gemini_usage_notified")

    /** Requests per day the user wants to stay under (their free-tier allowance). */
    val dailyLimit: Flow<Int> = context.dataStore.data.map { it[dailyLimitKey] ?: DEFAULT_DAILY_LIMIT }
    val usageAlerts: Flow<Boolean> = context.dataStore.data.map { it[alertsKey] ?: true }

    suspend fun setDailyLimit(value: Int) = context.dataStore.edit { it[dailyLimitKey] = value.coerceIn(1, 100_000) }
    suspend fun setUsageAlerts(value: Boolean) = context.dataStore.edit { it[alertsKey] = value }

    /** Last alert sent, as "epochDay:level", so each level notifies once per day. */
    suspend fun lastUsageNotification(): String? = context.dataStore.data.first()[notifiedKey]
    suspend fun setLastUsageNotification(value: String) = context.dataStore.edit { it[notifiedKey] = value }

    suspend fun setModel(value: String) = context.dataStore.edit {
        it[modelKey] = value.trim().ifEmpty { DEFAULT_GEMINI_MODEL }
    }

    suspend fun setOnboarded(value: Boolean) = context.dataStore.edit { it[onboardedKey] = value }

    suspend fun setLastSync(ms: Long, message: String) = context.dataStore.edit {
        it[lastSyncKey] = ms
        it[lastSyncMsgKey] = message
    }
}

/** Stores the Gemini API key encrypted with an Android Keystore AES key. */
class SecureKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences("secure", Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun save(value: String) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ct = cipher.doFinal(value.trim().toByteArray())
        val enc = b64(cipher.iv) + ":" + b64(ct)
        prefs.edit().putString(PREF, enc).apply()
    }

    fun load(): String? {
        val stored = prefs.getString(PREF, null) ?: return null
        return try {
            val (iv, ct) = stored.split(":").map { Base64.decode(it, Base64.NO_WRAP) }
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct))
        } catch (e: Exception) {
            null
        }
    }

    fun hasKey(): Boolean = load() != null

    fun clear() = prefs.edit().remove(PREF).apply()

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private companion object {
        const val ALIAS = "swimpro_gemini_key"
        const val PREF = "gemini_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}

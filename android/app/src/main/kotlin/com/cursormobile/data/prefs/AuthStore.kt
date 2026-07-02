package com.cursormobile.data.prefs

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Long-lived secrets backed by AES-GCM with a key in the Android Keystore.
 *
 * Stores:
 *  - Our X25519 keypair (we mint once, then reuse for all pairings).
 *  - Active relay URL.
 *  - List of paired Macs and their pubkeys/labels/pair IDs.
 */
@Singleton
class AuthStore @Inject constructor(@ApplicationContext context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "auth",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var relayUrl: String?
        get() = prefs.getString("relay_url", null)
        set(value) { prefs.edit().putString("relay_url", value).apply() }

    var ourPubKey: String?
        get() = prefs.getString("our_pub", null)
        set(value) { prefs.edit().putString("our_pub", value).apply() }

    var ourPrivKey: String?
        get() = prefs.getString("our_priv", null)
        set(value) { prefs.edit().putString("our_priv", value).apply() }

    // Active pair selected by the user (we support multiple Macs).
    /**
     * Backing flow for the active pair so Compose can observe pairing changes
     * and re-render the bottom nav / pairing screen accordingly.
     */
    private val _activePair = MutableStateFlow(activePair())
    val activePairFlow: StateFlow<PairedMac?> = _activePair.asStateFlow()

    var activePairId: String?
        get() = prefs.getString("active_pair", null)
        set(value) {
            prefs.edit().putString("active_pair", value).apply()
            _activePair.value = activePair()
        }

    fun savePair(pair: PairedMac) {
        val key = "pair:${pair.pairId}"
        prefs.edit().putString(key, pair.serialize()).apply()
        val list = pairs().map { it.pairId }.toMutableSet().apply { add(pair.pairId) }
        prefs.edit().putString("pair_ids", list.joinToString(",")).apply()
        _activePair.value = activePair()
    }

    fun forgetPair(pairId: String) {
        prefs.edit().remove("pair:$pairId").apply()
        val list = pairs().map { it.pairId }.toMutableSet().apply { remove(pairId) }
        prefs.edit().putString("pair_ids", list.joinToString(",")).apply()
        if (activePairId == pairId) activePairId = null
        _activePair.value = activePair()
    }

    fun pairs(): List<PairedMac> {
        val ids = prefs.getString("pair_ids", "")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        return ids.mapNotNull { id -> prefs.getString("pair:$id", null)?.let { PairedMac.parse(it) } }
    }

    fun activePair(): PairedMac? {
        val id = activePairId ?: return null
        return prefs.getString("pair:$id", null)?.let { PairedMac.parse(it) }
    }

    var fcmToken: String?
        get() = prefs.getString("fcm_token", null)
        set(value) { prefs.edit().putString("fcm_token", value).apply() }

    /** Optional LAN host override; when set we prefer ws://<lanHost>:<lanPort>/lan over the relay. */
    var lanHost: String?
        get() = prefs.getString("lan_host", null)
        set(value) { prefs.edit().putString("lan_host", value).apply() }

    var lanPort: Int
        get() = prefs.getInt("lan_port", 7681)
        set(value) { prefs.edit().putInt("lan_port", value).apply() }
}

data class PairedMac(
    val pairId: String,
    val macPubKey: String,
    val macLabel: String,
    val pairedAt: Long,
) {
    fun serialize(): String = listOf(pairId, macPubKey, macLabel, pairedAt.toString()).joinToString("|")

    companion object {
        fun parse(s: String): PairedMac? {
            val parts = s.split("|")
            if (parts.size != 4) return null
            return PairedMac(parts[0], parts[1], parts[2], parts[3].toLongOrNull() ?: 0L)
        }
    }
}

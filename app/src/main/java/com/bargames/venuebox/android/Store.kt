package com.bargames.venuebox.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.bargames.venuebox.client.TokenStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Box address and names. Nothing secret here (the token lives in [KeystoreTokenStore]). */
class Config(context: Context) {
    private val prefs = context.getSharedPreferences("venuebox", Context.MODE_PRIVATE)

    /** What staff typed or discovery found, e.g. "http://192.168.1.20:7210". */
    var box: String?
        get() = prefs.getString("box", null)
        set(v) = prefs.edit().putString("box", v).apply()

    /** The box's discovery id (mDNS TXT id): how it's re-found when its address changes. */
    var boxId: String?
        get() = prefs.getString("boxId", null)
        set(v) = prefs.edit().putString("boxId", v).apply()

    var boxName: String?
        get() = prefs.getString("boxName", null)
        set(v) = prefs.edit().putString("boxName", v).apply()

    /** The name this device asks to be called when pairing; null → the device's own name. */
    var name: String?
        get() = prefs.getString("name", null)
        set(v) = prefs.edit().putString("name", v).apply()

    /** Touch mode (kiosk tablets, touch TVs): pages get the GameKit on-screen keyboard flag. */
    var touchMode: Boolean
        get() = prefs.getBoolean("touchMode", false)
        set(v) = prefs.edit().putBoolean("touchMode", v).apply()
}

/**
 * The device token, encrypted with an AES-GCM key that never leaves the Android Keystore.
 * If the key is lost (e.g. a restore onto another device), the token reads as absent and
 * the app pairs again.
 */
class KeystoreTokenStore(context: Context) : TokenStore {
    private val prefs = context.getSharedPreferences("venuebox-secure", Context.MODE_PRIVATE)
    @Volatile private var cached: String? = null
    @Volatile private var loaded = false

    override fun get(): String? {
        if (loaded) return cached
        val stored = prefs.getString(KEY_DATA, null)
        cached = stored?.let { decrypt(it) }
        loaded = true
        return cached
    }

    override fun set(token: String) {
        prefs.edit().putString(KEY_DATA, encrypt(token)).apply()
        cached = token
        loaded = true
    }

    override fun clear() {
        prefs.edit().remove(KEY_DATA).apply()
        cached = null
        loaded = true
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(byteArrayOf(iv.size.toByte()) + iv + ct, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? = try {
        val all = Base64.decode(stored, Base64.NO_WRAP)
        val ivLen = all[0].toInt()
        val iv = all.copyOfRange(1, 1 + ivLen)
        val ct = all.copyOfRange(1 + ivLen, all.size)
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(c.doFinal(ct), Charsets.UTF_8)
    } catch (e: Exception) {
        Log.w(TAG, "stored token unreadable: pairing again (${e.javaClass.simpleName})")
        null
    }

    private companion object {
        const val ALIAS = "venuebox-token"
        const val KEY_DATA = "token"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}

package com.yogeshpaliyal.sendahead

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Optional, off by default: the device PIN/password used to auto-unlock before sending.
 * Encrypted with an AES key that lives in the Android Keystore (never leaves the device,
 * so a copied prefs file is useless elsewhere). App backups are disabled.
 */
object UnlockSecret {
    enum class Type { PIN, PASSWORD }
    data class Secret(val type: Type, val value: String)

    private const val PREFS = "auto_unlock"
    private const val ALIAS = "sendahead_auto_unlock"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(ctx: Context) = prefs(ctx).contains("data")

    fun save(ctx: Context, secret: Secret) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(secret.value.toByteArray(Charsets.UTF_8))
        prefs(ctx).edit()
            .putString("type", secret.type.name)
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("data", Base64.encodeToString(ct, Base64.NO_WRAP))
            .apply()
    }

    fun load(ctx: Context): Secret? = try {
        val p = prefs(ctx)
        val data = p.getString("data", null) ?: return null
        val iv = Base64.decode(p.getString("iv", null), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        val value = String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
        Secret(Type.valueOf(p.getString("type", Type.PIN.name)!!), value)
    } catch (e: Exception) {
        null
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS)
        }
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
                .build()
        )
        return gen.generateKey()
    }
}

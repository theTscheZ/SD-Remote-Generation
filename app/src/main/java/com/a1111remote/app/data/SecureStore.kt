package com.a1111remote.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.a1111remote.app.model.ConnectionSettings
import com.a1111remote.app.model.GenerationSettings
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Never writes the API password in cleartext or includes it in source control. */
class SecureStore(context: Context) {
    private val preferences = context.getSharedPreferences("a1111_settings", Context.MODE_PRIVATE)
    private val alias = "a1111_api_password_v1"

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        if (value.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        if (value.isEmpty()) return ""
        return runCatching {
            val bytes = Base64.decode(value, Base64.DEFAULT)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }.getOrDefault("")
    }

    fun connection(): ConnectionSettings = ConnectionSettings(
        lanUrl = preferences.getString("lanUrl", "http://192.168.1.100:7860").orEmpty(),
        vpnUrl = preferences.getString("vpnUrl", "").orEmpty(),
        username = preferences.getString("username", "").orEmpty(),
        password = decrypt(preferences.getString("password", "").orEmpty())
    )

    fun saveConnection(config: ConnectionSettings) {
        preferences.edit()
            .putString("lanUrl", config.lanUrl)
            .putString("vpnUrl", config.vpnUrl)
            .putString("username", config.username)
            .putString("password", encrypt(config.password))
            .apply()
    }

    fun generation(): GenerationSettings = runCatching {
        GenerationSettings.fromJson(JSONObject(preferences.getString("generation", "{}") ?: "{}"))
    }.getOrElse { GenerationSettings() }

    fun saveGeneration(settings: GenerationSettings) {
        preferences.edit().putString("generation", settings.toJson().toString()).apply()
    }
}

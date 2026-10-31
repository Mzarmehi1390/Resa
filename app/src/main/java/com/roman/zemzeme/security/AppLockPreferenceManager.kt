package com.roman.zemzeme.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object AppLockPreferenceManager {

    private const val PREFS_NAME = "bitchat_applock"
    private const val KEY_ENABLED = "app_lock_enabled"
    private const val KEY_PIN_HASH = "pin_hash"
    private const val KEY_PIN_SALT = "pin_salt"
    private const val KEY_PIN_LENGTH = "pin_length"
    private const val KEY_SETUP_PROMPT_SHOWN = "setup_prompt_shown"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun isEnabled(): Boolean = prefs?.getBoolean(KEY_ENABLED, false) ?: false

    fun setEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
    }

    fun hasPinSet(): Boolean = prefs?.getString(KEY_PIN_HASH, null) != null

    fun hasShownSetupPrompt(): Boolean = prefs?.getBoolean(KEY_SETUP_PROMPT_SHOWN, false) ?: false

    fun markSetupPromptShown() {
        prefs?.edit()?.putBoolean(KEY_SETUP_PROMPT_SHOWN, true)?.apply()
    }

    fun getPinLength(): Int = 6

    fun savePin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = hashPin(pin, salt)
        prefs?.edit()
            ?.putString(KEY_PIN_HASH, Base64.encodeToString(hash, Base64.DEFAULT))
            ?.putString(KEY_PIN_SALT, Base64.encodeToString(salt, Base64.DEFAULT))
            ?.putInt(KEY_PIN_LENGTH, pin.length)
            ?.apply()
    }

    fun verifyPin(pin: String): Boolean {
        val saltStr = prefs?.getString(KEY_PIN_SALT, null) ?: return false
        val hashStr = prefs?.getString(KEY_PIN_HASH, null) ?: return false
        val salt = Base64.decode(saltStr, Base64.DEFAULT)
        val storedHash = Base64.decode(hashStr, Base64.DEFAULT)
        val inputHash = hashPin(pin, salt)
        return storedHash.contentEquals(inputHash)
    }

    fun clearPin() {
        prefs?.edit()
            ?.remove(KEY_PIN_HASH)
            ?.remove(KEY_PIN_SALT)
            ?.remove(KEY_PIN_LENGTH)
            ?.apply()
    }

    private fun hashPin(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 100_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }
}

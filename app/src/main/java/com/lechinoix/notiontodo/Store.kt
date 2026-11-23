package com.lechinoix.notiontodo

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

class Store(c: Context) {
    // These names and the encrypted format must remain unchanged across APK updates.
    private val prefs = c.getSharedPreferences("notion", Context.MODE_PRIVATE)
    fun config() = JSONObject(prefs.getString("config", "{}")!!)
    fun ready() = prefs.contains("config") && prefs.contains("secret")
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias("notion-token")) {
            val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
            generator.init(KeyGenParameterSpec.Builder("notion-token", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generator.generateKey()
        }
        return ks.getKey("notion-token", null) as SecretKey
    }
    fun token(): String {
        if (!ready()) return ""
        val pair = prefs.getString("secret", "")!!.split(":")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(pair[0], Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(pair[1], Base64.NO_WRAP)), Charsets.UTF_8)
    }
    fun configure(token: String, config: JSONObject, tasks: JSONArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val secret = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(token.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        if (!prefs.edit().putString("secret", secret).putString("config", config.toString()).putString("tasks", tasks.toString())
                .putLong("synced", System.currentTimeMillis()).putString("message", "").commit()) {
            throw java.io.IOException("Could not save settings on this device.")
        }
    }
    fun tasks(): JSONArray = try { JSONArray(prefs.getString("tasks", "[]")) } catch (e: Exception) { JSONArray() }
    fun tasks(a: JSONArray) { prefs.edit().putString("tasks", a.toString()).apply() }
    fun remove(id: String) {
        val a = tasks()
        tasks(JSONArray().apply { for (i in 0 until a.length()) if (id != a.getJSONObject(i).getString("id")) put(a.get(i)) })
    }
    fun message(text: String) { prefs.edit().putString("message", text).apply() }
    fun message(): String = prefs.getString("message", "")!!
    fun synced() = prefs.getLong("synced", 0)
    fun refresh() {
        val api = NotionApi(token())
        val c = config()
        val statuses = NotionApi.property(api.schema(c.getString("source")), c.getString("status")).getJSONObject("status").getJSONArray("options")
        val done = (0 until statuses.length()).map { statuses.getJSONObject(it) }.find { c.getString("done") == it.getString("id") }
            ?: throw IllegalArgumentException("The completed status was removed. Reconnect the table in Settings.")
        c.put("doneName", done.getString("name"))
        tasks(api.tasks(c))
        prefs.edit().putLong("synced", System.currentTimeMillis()).putString("message", "").apply()
    }
    companion object {
        // Serializes refresh, settings and writes so stale reads cannot overwrite newer results.
        @JvmField val LOCK = Any()
    }
}

package com.lechinoix.notiontodo

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Build
import android.view.WindowInsets
import android.widget.*
import org.json.JSONArray
import java.util.concurrent.Executors

object Ui {
    private val io = Executors.newSingleThreadExecutor()
    fun <T> async(a: Activity, job: () -> T, done: (T) -> Unit, fail: (Exception) -> Unit) {
        io.execute {
            try {
                val result = job()
                a.runOnUiThread { if (!a.isFinishing && !a.isDestroyed) done(result) }
            } catch (e: Exception) {
                a.runOnUiThread { if (!a.isFinishing && !a.isDestroyed) fail(e) }
            }
        }
    }
    fun error(e: Exception): String = when (e) {
        is java.net.UnknownHostException, is java.net.SocketTimeoutException, is java.net.ConnectException -> "Cannot reach Notion. Check your internet connection."
        is NotionApi.ApiException, is IllegalArgumentException -> e.message ?: "Could not complete the request."
        else -> "Could not complete the request. Check your connection or reconnect the table in Settings."
    }
    fun dp(a: Activity, value: Int) = (a.resources.displayMetrics.density * value).toInt()
    fun screen(a: Activity): LinearLayout {
        val scroll = ScrollView(a).apply { isFillViewport = true }
        val body = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(a,24),dp(a,20),dp(a,24),dp(a,24))
        }
        scroll.addView(body)
        a.setContentView(scroll)
        if (Build.VERSION.SDK_INT >= 30) scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        return body
    }
    fun text(a: Activity, body: LinearLayout, text: String, size: Int): TextView = TextView(a).apply {
        this.text = text; textSize = size.toFloat(); setTextColor(Color.rgb(35,53,47))
        setPadding(0,dp(a,8),0,dp(a,8)); body.addView(this)
    }
    fun button(a: Activity, body: LinearLayout, title: String, click: () -> Unit): Button = Button(a).apply {
        text = title; isAllCaps = false; minHeight = dp(a,48)
        body.addView(this); setOnClickListener { click() }
    }
    fun tags(tags: JSONArray): String = (0 until tags.length()).joinToString("  ·  ") { tags.optJSONObject(it).optString("name") }.ifEmpty { "Add tags" }
    fun alert(a: Activity, message: String) { AlertDialog.Builder(a).setMessage(message).setPositiveButton("OK",null).show() }
}

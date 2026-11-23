package com.lechinoix.notiontodo

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var store: Store
    private var settings = false
    private lateinit var notice: TextView
    private var list: LinearLayout? = null
    private val changes = SharedPreferences.OnSharedPreferenceChangeListener { _,_ -> if (!settings && list != null) renderTasks() }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); store = Store(this)
        getSharedPreferences("notion",MODE_PRIVATE).registerOnSharedPreferenceChangeListener(changes)
        if (store.ready()) home() else setup()
    }
    override fun onResume() {
        super.onResume()
        if (::store.isInitialized && store.ready() && !settings) { renderTasks(); SyncWorker.schedule(this); SyncWorker.enqueue(this,null) }
    }
    override fun onDestroy() {
        getSharedPreferences("notion",MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(changes); super.onDestroy()
    }
    private fun home() {
        settings = false
        val body = Ui.screen(this)
        Ui.text(this,body,"NOTION  /  YOUR EVERYDAY LIST",12)
        Ui.text(this,body,"A little more done.",30)
        Ui.text(this,body,"Check off a task, or tap its title to choose tags.",15)
        Ui.button(this,body,"+  New task") { startActivity(Intent(this,TaskActivity::class.java)) }
        notice = Ui.text(this,body,"",13)
        Ui.button(this,body,"↻  Refresh") { SyncWorker.enqueue(this,null) }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; body.addView(this) }
        renderTasks()
        Ui.button(this,body,"Add to home screen") {
            val manager = AppWidgetManager.getInstance(this)
            if (manager.isRequestPinAppWidgetSupported) manager.requestPinAppWidget(ComponentName(this,TodoWidget::class.java),null,null)
            else Ui.alert(this,"Long-press your home screen, choose Widgets, then drag Notion Tasks onto it.")
        }
        Ui.button(this,body,"Settings",::setup)
    }
    private fun renderTasks() {
        val list = list ?: return
        if (settings) return
        list.removeAllViews()
        notice.text = store.message().ifEmpty { "Tasks synced with Notion. The widget also refreshes about every 30 minutes." }
        val tasks = store.tasks()
        if (tasks.length() == 0) Ui.text(this,list,"All caught up ✓",20)
        for (i in 0 until tasks.length()) try {
            val task = tasks.getJSONObject(i)
            val id = task.getString("id")
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }; list.addView(row)
            val done = CheckBox(this).apply {
                minWidth = Ui.dp(this@MainActivity,48); minHeight = Ui.dp(this@MainActivity,64)
                contentDescription = "Mark done: ${task.optString("title")}"
            }
            row.addView(done)
            val label = TextView(this).apply {
                text = task.optString("title") + "\n" + Ui.tags(task.getJSONArray("tags")); textSize = 16f; setPadding(8,18,8,18)
            }
            row.addView(label,LinearLayout.LayoutParams(0,-2,1f))
            label.setOnClickListener { startActivity(Intent(this,TaskActivity::class.java).putExtra("page",id)) }
            done.setOnClickListener { done.isChecked = false; SyncWorker.enqueue(this,id) }
        } catch (e: Exception) { notice.text = "Could not read a cached task. Refresh to reload it." }
    }
    private fun field(body: LinearLayout, label: String, secret: Boolean): EditText {
        Ui.text(this,body,label,14)
        return EditText(this).apply {
            setSingleLine(true); minHeight = Ui.dp(this@MainActivity,48)
            inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSaveEnabled = false; importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO; body.addView(this)
        }
    }
    private fun setup() {
        settings = true; list = null
        val body = Ui.screen(this)
        Ui.text(this,body,"Connect your Notion",28)
        Ui.text(this,body,"1. Create an internal connection at notion.so/profile/integrations with Read, Insert and Update content.\n\n2. In Notion, open the original TODO database → ••• → Connections, and add that connection.\n\n3. Paste its secret and the database link below. Your secret is encrypted on this device.",15)
        val token = field(body,"Connection secret",true).apply { hint = if (store.ready()) "Leave blank to keep your saved secret" else "ntn_…" }
        val database = field(body,"Database link or data source ID",false)
        try { if (store.ready()) database.setText(store.config().getString("source")) } catch (e: Exception) { /* Setup can repair invalid config. */ }
        val status = Ui.text(this,body,"",14)
        val mapping = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val connect = Ui.button(this,body,"Load table") {}
        body.addView(mapping)
        connect.setOnClickListener {
            connect.isEnabled = false; mapping.removeAllViews(); status.text = "Loading your table…"
            val enteredToken = token.text.toString().trim()
            val link = database.text.toString().trim()
            Ui.async(this,{
                val secret = enteredToken.ifEmpty { store.token() }
                require(secret.isNotBlank()) { "Enter your Notion connection secret." }
                val sources = NotionApi(secret).sources(link)
                require(sources.length() != 0) { "This database has no data sources." }
                secret to sources
            },{ (secret,sources) ->
                connect.isEnabled = true; status.text = ""
                if (sources.length() == 1) loadMapping(secret,sources.getJSONObject(0).getString("id"),mapping,status)
                else {
                    val names = Array(sources.length()) { sources.getJSONObject(it).optString("name","Table ${it+1}") }
                    AlertDialog.Builder(this).setTitle("Choose your task table").setItems(names) { _,which ->
                        loadMapping(secret,sources.getJSONObject(which).getString("id"),mapping,status)
                    }.show()
                }
            },{ e -> connect.isEnabled = true; status.text = Ui.error(e) })
        }
        if (store.ready()) Ui.button(this,body,"Back to tasks",::home)
    }
    private fun properties(schema: JSONObject, type: String): List<JSONObject> {
        val props = schema.getJSONObject("properties")
        val result = props.keys().asSequence().mapNotNull { name ->
            props.getJSONObject(name).takeIf { type == it.optString("type") }?.let { JSONObject(it.toString()).put("name",name) }
        }.toList()
        require(result.isNotEmpty()) { "This table needs a $type property. Use Status for progress and a multi-select property for tags." }
        return result
    }
    private fun choose(body: LinearLayout, label: String, options: List<JSONObject>): Spinner {
        Ui.text(this,body,label,14)
        val adapter = ArrayAdapter(this,android.R.layout.simple_spinner_item,options.map { it.optString("name") })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        return Spinner(this).apply { this.adapter = adapter; minimumHeight = Ui.dp(this@MainActivity,48); body.addView(this) }
    }
    private fun objects(a: JSONArray): List<JSONObject> = (0 until a.length()).map { a.getJSONObject(it) }
    private fun loadMapping(secret: String, source: String, body: LinearLayout, status: TextView) {
        status.text = "Reading statuses and tags…"
        Ui.async(this,{ NotionApi(secret).schema(source) },{ schema ->
            body.removeAllViews()
            try {
                val titles = properties(schema,"title"); val statuses = properties(schema,"status"); val tags = properties(schema,"multi_select")
                val title = choose(body,"Task title",titles)
                val statusProp = choose(body,"Status property",statuses)
                val tagProp = choose(body,"Tags property",tags)
                val optionsBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; body.addView(optionsBody)
                fun renderOptions() {
                    optionsBody.removeAllViews()
                    val property = statuses[statusProp.selectedItemPosition]
                    val statusSchema = property.getJSONObject("status")
                    val options = objects(statusSchema.getJSONArray("options"))
                    if (options.size < 2) { status.text = "The Status property needs at least two options."; return }
                    val todo = choose(optionsBody,"New tasks start as",options)
                    val done = choose(optionsBody,"Mark completed tasks as (hidden from widget)",options)
                    done.setSelection(options.size-1)
                    // Notion status groups are ordered To-do, In progress, Complete.
                    val groups = statusSchema.optJSONArray("groups")
                    if (groups != null && groups.length() > 0) {
                        val completeIds = groups.getJSONObject(groups.length()-1).optJSONArray("option_ids")
                        if (completeIds != null && completeIds.length() > 0) options.forEachIndexed { i,option ->
                            if (option.optString("id") == completeIds.optString(0)) done.setSelection(i)
                        }
                    }
                    val save = Ui.button(this,optionsBody,"Save and load tasks") {}
                    save.setOnClickListener saveClick@{
                        if (todo.selectedItemPosition == done.selectedItemPosition) { status.text = "Choose different statuses for new and completed tasks."; return@saveClick }
                        save.isEnabled = false; status.text = "Checking the table and saving…"
                        val titleIndex = title.selectedItemPosition; val tagIndex = tagProp.selectedItemPosition
                        val todoIndex = todo.selectedItemPosition; val doneIndex = done.selectedItemPosition
                        Ui.async(this,{
                            val c = JSONObject().put("source",source).put("title",titles[titleIndex].getString("id"))
                                .put("status",property.getString("id")).put("tags",tags[tagIndex].getString("id"))
                                .put("todo",options[todoIndex].getString("id")).put("done",options[doneIndex].getString("id"))
                                .put("doneName",options[doneIndex].getString("name"))
                            synchronized(Store.LOCK) { store.configure(secret,c,NotionApi(secret).tasks(c)) }
                        },{ SyncWorker.schedule(this); TodoWidget.render(this); home() },{ e -> save.isEnabled = true; status.text = Ui.error(e) })
                    }
                }
                statusProp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = renderOptions()
                    override fun onNothingSelected(p: AdapterView<*>?) {}
                }
                status.text = "Choose the properties used by your TODO table."
            } catch (e: Exception) { status.text = Ui.error(e) }
        },{ e -> status.text = Ui.error(e) })
    }
}

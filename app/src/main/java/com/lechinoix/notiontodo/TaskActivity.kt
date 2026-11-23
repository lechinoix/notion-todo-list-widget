package com.lechinoix.notiontodo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject

class TaskActivity : Activity() {
    private val selected = linkedSetOf<String>()
    private val original = linkedSetOf<String>()
    private var page: String? = null
    private lateinit var config: JSONObject
    private lateinit var store: Store
    private lateinit var status: TextView
    private lateinit var titleInput: EditText
    private lateinit var saveButton: Button
    private var writing = false
    private var loaded = false
    private var restored: Bundle? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        store = Store(this); restored = state
        if (!store.ready()) { startActivity(Intent(this,MainActivity::class.java)); finish(); return }
        page = intent.getStringExtra("page")
        // Accept pending intents created by an older installed widget until its next refresh.
        if (intent.getBooleanExtra("complete",false) && page != null) { SyncWorker.enqueue(this,page); finish(); return }
        val body = Ui.screen(this)
        Ui.text(this,body,if (page == null) "One less thing to remember." else "Task tags",23)
        titleInput = EditText(this).apply {
            hint = "What needs doing?"; textSize = 20f; maxLines = 4
            filters = arrayOf(InputFilter.LengthFilter(2000)); isSaveEnabled = false
        }
        val inputRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        body.addView(inputRow)
        inputRow.addView(titleInput,LinearLayout.LayoutParams(0,-2,1f))
        if (page == null) {
            saveButton = Ui.button(this,inputRow,"✓",::save).apply {
                contentDescription = "Create task"
                layoutParams = LinearLayout.LayoutParams(Ui.dp(this@TaskActivity,48),Ui.dp(this@TaskActivity,48))
                minWidth = 0; minimumWidth = 0; setPadding(0,0,0,0)
            }
            if (state != null) titleInput.setText(state.getString("title",""))
        } else titleInput.visibility = View.GONE
        status = Ui.text(this,body,"Loading tags…",14)
        val tags = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(tags)
        if (page != null) saveButton = Ui.button(this,body,"Save tags",::save)
        saveButton.isEnabled = false
        Ui.button(this,body,"Cancel") { if (!writing) closeEditor() }
        load(tags)
    }
    @Deprecated("Legacy back handling")
    override fun onBackPressed() { if (!writing) closeEditor() }
    override fun onSaveInstanceState(state: Bundle) {
        if (::titleInput.isInitialized) state.putString("title",titleInput.text.toString())
        if (loaded) state.putStringArrayList("selected",ArrayList(selected))
        super.onSaveInstanceState(state)
    }
    private fun closeEditor() {
        if (intent.getBooleanExtra("fromWidget",false) && isTaskRoot) finishAndRemoveTask() else finish()
    }
    private fun load(tags: LinearLayout) {
        Ui.async(this,{
            synchronized(Store.LOCK) {
                config = store.config()
                val api = NotionApi(store.token())
                val options = NotionApi.property(api.schema(config.getString("source")),config.getString("tags")).getJSONObject("multi_select").getJSONArray("options")
                options to page?.let { NotionApi.task(api.page(it),config) }
            }
        },{ (options,task) ->
            if (task != null) {
                status.text = task.optString("title")
                val existing = task.getJSONArray("tags")
                for (i in 0 until existing.length()) original.add(existing.getJSONObject(i).getString("id"))
            } else status.text = "Choose existing tags (optional)."
            selected.clear(); selected.addAll(original)
            restored?.getStringArrayList("selected")?.let { selected.clear(); selected.addAll(it) }
            if (options.length() == 0) Ui.text(this,tags,"No tags yet. Add tag options in Notion, then reopen this screen.",14)
            for (i in 0 until options.length()) {
                val option = options.getJSONObject(i)
                val id = option.getString("id")
                val box = CheckBox(this).apply {
                    text = option.optString("name"); minHeight = Ui.dp(this@TaskActivity,48); isChecked = selected.contains(id)
                    setOnCheckedChangeListener { _,checked -> if (checked) selected.add(id) else selected.remove(id) }
                }
                tags.addView(box)
            }
            loaded = true; saveButton.isEnabled = true
        },{ e ->
            status.text = Ui.error(e)
            Ui.button(this,tags,"Retry loading tags") { tags.removeAllViews(); load(tags) }
        })
    }
    private fun save() {
        val name = titleInput.text.toString().trim()
        if (page == null && name.isEmpty()) { titleInput.error = "Enter a task title"; return }
        writing = true; saveButton.isEnabled = false; status.text = "Saving to Notion…"
        val chosen = selected.toSet()
        Ui.async(this,{
            synchronized(Store.LOCK) {
                require(store.config().toString() == config.toString()) { "Table settings changed. Reopen this task before saving." }
                val api = NotionApi(store.token())
                val options = NotionApi.property(api.schema(config.getString("source")),config.getString("tags")).getJSONObject("multi_select").getJSONArray("options")
                val valid = (0 until options.length()).map { options.getJSONObject(it).getString("id") }.toSet()
                require(valid.containsAll(chosen)) { "A selected tag was removed in Notion. Reopen this task to reload tags." }
                val id = page
                if (id == null) {
                    val tasks = JSONArray().put(NotionApi.task(api.create(name,chosen,config),config))
                    val old = store.tasks()
                    for (i in 0 until old.length()) tasks.put(old.get(i))
                    store.tasks(tasks)
                } else {
                    // Apply only this editor's changes, retaining tags added elsewhere meanwhile.
                    val latest = NotionApi.task(api.page(id),config)
                    val current = latest.getJSONArray("tags")
                    val merged = (0 until current.length()).map { current.getJSONObject(it).getString("id") }.toMutableSet()
                    merged.removeAll(original - chosen); merged.addAll(chosen - original)
                    api.tags(id,config,merged)
                    val updated = JSONArray()
                    for (i in 0 until options.length()) if (options.getJSONObject(i).getString("id") in merged) updated.put(options.getJSONObject(i))
                    latest.put("tags",updated)
                    val tasks = store.tasks()
                    for (i in 0 until tasks.length()) if (id == tasks.getJSONObject(i).getString("id")) tasks.put(i,latest)
                    store.tasks(tasks)
                }
                store.message("")
                try { store.refresh() } catch (e: Exception) { store.message("Saved to Notion. List refresh failed; tap ↻ to retry.") }
                TodoWidget.render(this)
            }
        },{
            writing = false
            Toast.makeText(this,if (page == null) "Task created" else "Tags saved",Toast.LENGTH_SHORT).show()
            closeEditor()
        },{ e ->
            writing = false; saveButton.isEnabled = true
            status.text = Ui.error(e) + if (page == null) "\nIf the connection dropped during creation, refresh your task list before trying again to avoid a duplicate." else ""
        })
    }
    companion object {
        @JvmStatic fun fromWidget(c: Context): Intent = Intent(c,TaskActivity::class.java).putExtra("fromWidget",true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
    }
}

package com.lechinoix.notiontodo

import android.content.Intent
import android.graphics.Paint
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import org.json.JSONArray
import java.util.UUID

open class TaskListService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = object : RemoteViewsFactory {
        private var tasks = JSONArray()
        override fun onCreate() = onDataSetChanged()
        override fun onDataSetChanged() { tasks = Store(applicationContext).tasks() }
        override fun onDestroy() {}
        override fun getCount() = tasks.length()
        override fun getViewAt(position: Int): RemoteViews? {
            if (position !in 0 until tasks.length()) return null
            return try {
                val task = tasks.getJSONObject(position)
                val id = task.getString("id")
                RemoteViews(packageName,R.layout.task_row).apply {
                    setTextViewText(R.id.title,task.optString("title","Untitled"))
                    setTextViewText(R.id.tags,Ui.tags(task.getJSONArray("tags")))
                    val phase = TodoWidget.completing[id] ?: 0
                    setTextViewText(R.id.done,if (phase == 0) "○" else "✓")
                    if (phase > 0) {
                        val color = if (phase == 1) 0xFF758575.toInt() else 0x55758575
                        setTextColor(R.id.done,color); setTextColor(R.id.title,color); setTextColor(R.id.tags,color)
                        setInt(R.id.title,"setPaintFlags",Paint.ANTI_ALIAS_FLAG or Paint.STRIKE_THRU_TEXT_FLAG)
                    } else {
                        setTextColor(R.id.done,getColor(R.color.muted)); setTextColor(R.id.title,getColor(R.color.ink)); setTextColor(R.id.tags,getColor(R.color.muted))
                        setInt(R.id.title,"setPaintFlags",Paint.ANTI_ALIAS_FLAG)
                    }
                    setContentDescription(R.id.done,"Mark done: ${task.optString("title")}")
                    setOnClickFillInIntent(R.id.done,Intent().putExtra("page",id).putExtra("complete",true))
                    setOnClickFillInIntent(R.id.edit,Intent().putExtra("page",id))
                }
            } catch (e: Exception) { null }
        }
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int): Long = try {
            val id = UUID.fromString(tasks.getJSONObject(position).getString("id"))
            id.mostSignificantBits xor id.leastSignificantBits
        } catch (e: Exception) { position.toLong() }
        override fun hasStableIds() = true
    }
}

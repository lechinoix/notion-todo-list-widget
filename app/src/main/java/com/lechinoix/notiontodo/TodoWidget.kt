package com.lechinoix.notiontodo

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.widget.RemoteViews
import java.util.concurrent.ConcurrentHashMap

class TodoWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, manager: AppWidgetManager, ids: IntArray) {
        render(c); SyncWorker.schedule(c); SyncWorker.enqueue(c,null)
    }
    companion object {
        // Transient feedback: the persisted cache remains recoverable after process death.
        @JvmField val completing = ConcurrentHashMap<String,Int>()
        @JvmStatic fun render(context: Context) {
            val c = context.applicationContext
            val manager = AppWidgetManager.getInstance(c)
            val store = Store(c)
            for (id in manager.getAppWidgetIds(ComponentName(c,TodoWidget::class.java))) {
                val v = RemoteViews(c.packageName,R.layout.widget)
                val message = when {
                    !store.ready() -> "Tap My tasks to connect Notion"
                    store.message().isNotEmpty() -> store.message()
                    else -> "${store.tasks().length()} tasks · ${DateUtils.getRelativeTimeSpanString(store.synced(),System.currentTimeMillis(),DateUtils.MINUTE_IN_MILLIS)}"
                }
                v.setTextViewText(R.id.status,message)
                v.setTextViewText(R.id.empty,if (store.ready()) "All caught up ✓" else "Connect your Notion table to get started.")
                val service = Intent(c,TaskListService::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id).setData(Uri.parse("notiontodo://list/$id"))
                v.setRemoteAdapter(R.id.tasks,service); v.setEmptyView(R.id.tasks,R.id.empty)
                // Completion goes directly to background work, with no activity or app flash.
                val row = Intent(c,WidgetActionReceiver::class.java).setAction("row").setData(Uri.parse("notiontodo://row/$id"))
                v.setPendingIntentTemplate(R.id.tasks,PendingIntent.getBroadcast(c,id,row,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE))
                v.setOnClickPendingIntent(R.id.heading,PendingIntent.getActivity(c,1,Intent(c,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                v.setOnClickPendingIntent(R.id.add,PendingIntent.getActivity(c,2,TaskActivity.fromWidget(c).setAction("add"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                v.setOnClickPendingIntent(R.id.refresh,PendingIntent.getBroadcast(c,3,Intent(c,WidgetActionReceiver::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                manager.updateAppWidget(id,v); manager.notifyAppWidgetViewDataChanged(id,R.id.tasks)
            }
        }
    }
}

package com.lechinoix.notiontodo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        val page = intent.getStringExtra("page")
        if (intent.action == "row" && page != null) {
            if (TodoWidget.completing.containsKey(page)) return
            if (intent.getBooleanExtra("complete",false)) SyncWorker.enqueue(c,page)
            // A user tap from the visible launcher grants a short activity-start window.
            else c.startActivity(TaskActivity.fromWidget(c).putExtra("page",page))
        } else SyncWorker.enqueue(c,null)
    }
}

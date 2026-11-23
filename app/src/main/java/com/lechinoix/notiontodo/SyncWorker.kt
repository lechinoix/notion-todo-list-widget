package com.lechinoix.notiontodo

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, parameters: WorkerParameters) : Worker(context,parameters) {
    override fun doWork(): Result {
        val store = Store(applicationContext)
        synchronized(Store.LOCK) {
            if (!store.ready()) return Result.success()
            var completed = false
            val page = inputData.getString("page")
            try {
                val expected = inputData.getString("source")
                if (expected != null && expected != store.config().getString("source")) return Result.success()
                if (page != null) {
                    NotionApi(store.token()).complete(page,store.config()); completed = true
                    // Best-effort two-stage feedback; launchers can coalesce collection updates.
                    try {
                        TodoWidget.completing[page] = 1; TodoWidget.render(applicationContext)
                        Thread.sleep(250)
                        TodoWidget.completing[page] = 2; TodoWidget.render(applicationContext)
                        Thread.sleep(250)
                    } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
                    finally { TodoWidget.completing.remove(page); store.remove(page) }
                    TodoWidget.render(applicationContext)
                }
                store.refresh()
            } catch (e: Exception) {
                val prefix = when {
                    completed -> "Task completed; list refresh failed. "
                    page != null -> "Could not complete task. Tap its circle to retry. "
                    else -> "Refresh failed. Tap ↻ to retry. "
                }
                store.message(prefix + Ui.error(e))
                return Result.failure()
            } finally { TodoWidget.render(applicationContext) }
        }
        return Result.success()
    }
    companion object {
        fun schedule(c: Context) {
            WorkManager.getInstance(c).enqueueUniquePeriodicWork("periodic-sync",ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequest.Builder(SyncWorker::class.java,30,TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        }
        fun enqueue(c: Context, page: String?) {
            val store = Store(c)
            if (!store.ready()) return
            try {
                val source = store.config().getString("source")
                store.message(if (page == null) "Refreshing…" else "Completing task…"); TodoWidget.render(c)
                val data = Data.Builder().putString("page",page).putString("source",source).build()
                WorkManager.getInstance(c).enqueueUniqueWork(if (page == null) "manual-refresh" else "done-$page",ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequest.Builder(SyncWorker::class.java).setInputData(data).build())
            } catch (e: Exception) { store.message("Open Settings to reconnect the table."); TodoWidget.render(c) }
        }
    }
}

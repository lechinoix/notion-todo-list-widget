package com.lechinoix.notiontodo;

import android.content.Context;
import androidx.work.*;
import java.util.concurrent.TimeUnit;

public class SyncWorker extends Worker {
    public SyncWorker(Context context,WorkerParameters parameters) { super(context,parameters); }
    public static void schedule(Context c) {
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("periodic-sync",ExistingPeriodicWorkPolicy.KEEP,
            new PeriodicWorkRequest.Builder(SyncWorker.class,30,TimeUnit.MINUTES)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build());
    }
    public static void enqueue(Context c,String page) {
        Store store=new Store(c);
        if(!store.ready()) return;
        try {
            String source=store.config().getString("source");
            store.message(page==null ? "Refreshing…" : "Completing task…"); TodoWidget.render(c);
            Data data=new Data.Builder().putString("page",page).putString("source",source).build();
            WorkManager.getInstance(c).enqueueUniqueWork(page==null ? "manual-refresh" : "done-"+page,ExistingWorkPolicy.KEEP,
                new OneTimeWorkRequest.Builder(SyncWorker.class).setInputData(data).build());
        } catch(Exception e) { store.message("Open Settings to reconnect the table."); TodoWidget.render(c); }
    }
    @Override public Result doWork() {
        Store store=new Store(getApplicationContext());
        synchronized(Store.LOCK) {
            if(!store.ready()) return Result.success();
            boolean completed=false;
            String page=getInputData().getString("page");
            try {
                String expected=getInputData().getString("source");
                if(expected!=null && !expected.equals(store.config().getString("source"))) return Result.success();
                if(page!=null) { new NotionApi(store.token()).complete(page,store.config()); store.remove(page); completed=true; }
                store.refresh();
            } catch(Exception e) {
                store.message((completed ? "Task completed; list refresh failed. " : page!=null ? "Could not complete task. Tap its circle to retry. " : "Refresh failed. Tap ↻ to retry. ")+Ui.error(e));
                return Result.failure();
            } finally { TodoWidget.render(getApplicationContext()); }
        }
        return Result.success();
    }
}

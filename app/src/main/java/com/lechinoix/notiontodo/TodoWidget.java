package com.lechinoix.notiontodo;

import android.app.PendingIntent;
import android.appwidget.*;
import android.content.*;
import android.net.Uri;
import android.widget.RemoteViews;
import android.text.format.DateUtils;

public class TodoWidget extends AppWidgetProvider {
    @Override public void onUpdate(Context c,AppWidgetManager manager,int[] ids) { render(c); SyncWorker.schedule(c); SyncWorker.enqueue(c,null); }
    public static void render(Context context) {
        Context c=context.getApplicationContext();
        AppWidgetManager manager=AppWidgetManager.getInstance(c);
        Store store=new Store(c);
        for(int id:manager.getAppWidgetIds(new ComponentName(c,TodoWidget.class))) {
            RemoteViews v=new RemoteViews(c.getPackageName(),R.layout.widget);
            String message=store.message();
            if(!store.ready()) message="Tap My tasks to connect Notion";
            else if(message.isEmpty()) message=store.tasks().length()+" tasks · "+DateUtils.getRelativeTimeSpanString(store.synced(),System.currentTimeMillis(),DateUtils.MINUTE_IN_MILLIS);
            v.setTextViewText(R.id.status,message);
            v.setTextViewText(R.id.empty,store.ready() ? "All caught up ✓" : "Connect your Notion table to get started.");
            Intent service=new Intent(c,TaskListService.class).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id).setData(Uri.parse("notiontodo://list/"+id));
            v.setRemoteAdapter(R.id.tasks,service); v.setEmptyView(R.id.tasks,R.id.empty);
            // Activity template avoids background-activity launch restrictions on recent Android.
            Intent edit=new Intent(c,TaskActivity.class).setData(Uri.parse("notiontodo://row/"+id));
            v.setPendingIntentTemplate(R.id.tasks,PendingIntent.getActivity(c,id,edit,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_MUTABLE));
            v.setOnClickPendingIntent(R.id.heading,PendingIntent.getActivity(c,1,new Intent(c,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
            v.setOnClickPendingIntent(R.id.add,PendingIntent.getActivity(c,2,new Intent(c,TaskActivity.class).setAction("add"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
            v.setOnClickPendingIntent(R.id.refresh,PendingIntent.getBroadcast(c,3,new Intent(c,WidgetActionReceiver.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
            manager.updateAppWidget(id,v); manager.notifyAppWidgetViewDataChanged(id,R.id.tasks);
        }
    }
}

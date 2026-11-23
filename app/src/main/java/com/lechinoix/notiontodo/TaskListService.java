package com.lechinoix.notiontodo;
import android.content.Intent;
import android.widget.*;
import org.json.*;

public class TaskListService extends RemoteViewsService {
    @Override public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new RemoteViewsFactory() {
            JSONArray tasks=new JSONArray();
            public void onCreate() { onDataSetChanged(); }
            public void onDataSetChanged() { tasks=new Store(getApplicationContext()).tasks(); }
            public void onDestroy() { }
            public int getCount() { return tasks.length(); }
            public RemoteViews getViewAt(int position) {
                if(position<0 || position>=tasks.length()) return null;
                try {
                    JSONObject task=tasks.getJSONObject(position); String id=task.getString("id");
                    RemoteViews v=new RemoteViews(getPackageName(),R.layout.task_row);
                    v.setTextViewText(R.id.title,task.optString("title","Untitled"));
                    v.setTextViewText(R.id.tags,Ui.tags(task.getJSONArray("tags")));
                    v.setContentDescription(R.id.done,"Mark done: "+task.optString("title"));
                    v.setOnClickFillInIntent(R.id.done,new Intent().putExtra("page",id).putExtra("complete",true));
                    v.setOnClickFillInIntent(R.id.edit,new Intent().putExtra("page",id));
                    return v;
                } catch(Exception e) { return null; }
            }
            public RemoteViews getLoadingView() { return null; }
            public int getViewTypeCount() { return 1; }
            public long getItemId(int position) { return position; }
            public boolean hasStableIds() { return false; }
        };
    }
}

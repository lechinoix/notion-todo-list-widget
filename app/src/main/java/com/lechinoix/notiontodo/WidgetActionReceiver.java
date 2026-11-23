package com.lechinoix.notiontodo;
import android.content.*;
public class WidgetActionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c,Intent intent) { SyncWorker.enqueue(c,null); }
}

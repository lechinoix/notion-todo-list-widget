package com.lechinoix.notiontodo;
import android.app.*;
import android.graphics.Color;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.concurrent.*;

final class Ui {
    static final ExecutorService IO=Executors.newSingleThreadExecutor();
    interface Job<T> { T run() throws Exception; }
    interface Done<T> { void accept(T value); }
    static <T> void async(Activity a,Job<T> job,Done<T> done,Done<Exception> fail) {
        IO.execute(()->{ try { T result=job.run(); a.runOnUiThread(()->{ if(!a.isFinishing()&&!a.isDestroyed()) done.accept(result); }); }
        catch(Exception e) { a.runOnUiThread(()->{ if(!a.isFinishing()&&!a.isDestroyed()) fail.accept(e); }); } });
    }
    static String error(Exception e) {
        if(e instanceof java.net.UnknownHostException || e instanceof java.net.SocketTimeoutException || e instanceof java.net.ConnectException) return "Cannot reach Notion. Check your internet connection.";
        if(e instanceof NotionApi.ApiException || e instanceof IllegalArgumentException) return e.getMessage();
        return "Could not complete the request. Check your connection or reconnect the table in Settings.";
    }
    static int dp(Activity a,int value) { return (int)(a.getResources().getDisplayMetrics().density*value); }
    static LinearLayout screen(Activity a) {
        ScrollView scroll=new ScrollView(a); scroll.setFillViewport(true);
        LinearLayout body=new LinearLayout(a); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(a,24),dp(a,20),dp(a,24),dp(a,24));
        scroll.addView(body); a.setContentView(scroll);
        if(android.os.Build.VERSION.SDK_INT>=30) scroll.setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.ime());
            v.setPadding(bars.left,bars.top,bars.right,bars.bottom); return insets;
        });
        if(android.os.Build.VERSION.SDK_INT<30) scroll.setOnApplyWindowInsetsListener(null);
        return body;
    }
    static TextView text(Activity a,LinearLayout body,String text,int size) {
        TextView v=new TextView(a); v.setText(text); v.setTextSize(size); v.setTextColor(Color.rgb(35,53,47));
        v.setPadding(0,dp(a,8),0,dp(a,8)); body.addView(v); return v;
    }
    static Button button(Activity a,LinearLayout body,String title,Runnable click) {
        Button v=new Button(a); v.setText(title); v.setAllCaps(false); v.setMinHeight(dp(a,48)); body.addView(v);
        v.setOnClickListener(w->click.run()); return v;
    }
    static String tags(JSONArray tags) {
        StringBuilder b=new StringBuilder();
        for(int i=0;i<tags.length();i++) { if(i>0)b.append("  ·  "); b.append(tags.optJSONObject(i).optString("name")); }
        return b.length()==0 ? "Add tags" : b.toString();
    }
    static void alert(Activity a,String message) { new AlertDialog.Builder(a).setMessage(message).setPositiveButton("OK",null).show(); }
}

package com.lechinoix.notiontodo;

import android.app.*;
import android.appwidget.AppWidgetManager;
import android.content.*;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import org.json.*;
import java.util.*;

public class MainActivity extends Activity {
    private Store store;
    private boolean settings=false;
    private TextView notice;
    private LinearLayout list;
    private final SharedPreferences.OnSharedPreferenceChangeListener changes=(prefs,key)->{ if(!settings && list!=null) renderTasks(); };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); store=new Store(this);
        getSharedPreferences("notion",MODE_PRIVATE).registerOnSharedPreferenceChangeListener(changes);
        if(store.ready()) home(); else setup();
    }
    @Override protected void onResume() { super.onResume(); if(store!=null && store.ready() && !settings) { renderTasks(); SyncWorker.schedule(this); SyncWorker.enqueue(this,null); } }
    @Override protected void onDestroy() { getSharedPreferences("notion",MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(changes); super.onDestroy(); }
    private void home() {
        settings=false;
        LinearLayout body=Ui.screen(this);
        Ui.text(this,body,"NOTION  /  YOUR EVERYDAY LIST",12);
        Ui.text(this,body,"A little more done.",30);
        Ui.text(this,body,"Check off a task, or tap its title to choose tags.",15);
        Ui.button(this,body,"+  New task",()->startActivity(new Intent(this,TaskActivity.class)));
        notice=Ui.text(this,body,"",13);
        Ui.button(this,body,"↻  Refresh",()->SyncWorker.enqueue(this,null));
        list=new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); body.addView(list); renderTasks();
        Ui.button(this,body,"Add to home screen",()->{
            AppWidgetManager manager=AppWidgetManager.getInstance(this);
            if(manager.isRequestPinAppWidgetSupported()) manager.requestPinAppWidget(new ComponentName(this,TodoWidget.class),null,null);
            else Ui.alert(this,"Long-press your home screen, choose Widgets, then drag Notion Tasks onto it.");
        });
        Ui.button(this,body,"Settings",this::setup);
    }
    private void renderTasks() {
        if(list==null || settings) return;
        list.removeAllViews(); notice.setText(store.message().isEmpty() ? "Tasks synced with Notion. The widget also refreshes about every 30 minutes." : store.message());
        JSONArray tasks=store.tasks();
        if(tasks.length()==0) Ui.text(this,list,"All caught up ✓",20);
        for(int i=0;i<tasks.length();i++) try {
            JSONObject task=tasks.getJSONObject(i); String id=task.getString("id");
            LinearLayout row=new LinearLayout(this); row.setGravity(android.view.Gravity.CENTER_VERTICAL); list.addView(row);
            CheckBox done=new CheckBox(this); done.setMinWidth(Ui.dp(this,48)); done.setMinHeight(Ui.dp(this,64)); done.setContentDescription("Mark done: "+task.optString("title")); row.addView(done);
            TextView label=new TextView(this); label.setText(task.optString("title")+"\n"+Ui.tags(task.getJSONArray("tags"))); label.setTextSize(16); label.setPadding(8,18,8,18);
            row.addView(label,new LinearLayout.LayoutParams(0,-2,1));
            label.setOnClickListener(v->startActivity(new Intent(this,TaskActivity.class).putExtra("page",id)));
            done.setOnClickListener(v->{ done.setChecked(false); SyncWorker.enqueue(this,id); });
        } catch(Exception ignored) { notice.setText("Could not read a cached task. Refresh to reload it."); }
    }
    private EditText field(LinearLayout body,String label,boolean secret) {
        Ui.text(this,body,label,14);
        EditText input=new EditText(this); input.setSingleLine(true); input.setMinHeight(Ui.dp(this,48));
        input.setInputType(secret ? InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        input.setSaveEnabled(false); input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); body.addView(input); return input;
    }
    private void setup() {
        settings=true; list=null;
        LinearLayout body=Ui.screen(this);
        Ui.text(this,body,"Connect your Notion",28);
        Ui.text(this,body,"1. Create an internal connection at notion.so/profile/integrations with Read, Insert and Update content.\n\n2. In Notion, open the original TODO database → ••• → Connections, and add that connection.\n\n3. Paste its secret and the database link below. Your secret is encrypted on this device.",15);
        EditText token=field(body,"Connection secret",true); token.setHint(store.ready() ? "Leave blank to keep your saved secret" : "ntn_…");
        EditText database=field(body,"Database link or data source ID",false);
        try { if(store.ready()) database.setText(store.config().getString("source")); } catch(Exception ignored) { }
        TextView status=Ui.text(this,body,"",14);
        LinearLayout mapping=new LinearLayout(this); mapping.setOrientation(LinearLayout.VERTICAL);
        Button connect=Ui.button(this,body,"Load table",()->{}); body.addView(mapping);
        connect.setOnClickListener(v->{
            connect.setEnabled(false); mapping.removeAllViews(); status.setText("Loading your table…");
            String enteredToken=token.getText().toString().trim(), link=database.getText().toString().trim();
            Ui.async(this,()->{
                String secret=enteredToken.isEmpty() ? store.token() : enteredToken;
                if(secret.isBlank()) throw new IllegalArgumentException("Enter your Notion connection secret.");
                JSONArray sources=new NotionApi(secret).sources(link);
                if(sources.length()==0) throw new IllegalArgumentException("This database has no data sources.");
                return new Object[]{secret,sources};
            }, result->{
                connect.setEnabled(true); status.setText(""); String secret=(String)result[0]; JSONArray sources=(JSONArray)result[1];
                if(sources.length()==1) loadMapping(secret,sources.optJSONObject(0).optString("id"),mapping,status);
                else {
                    String[] names=new String[sources.length()]; for(int i=0;i<names.length;i++) names[i]=sources.optJSONObject(i).optString("name","Table "+(i+1));
                    new AlertDialog.Builder(this).setTitle("Choose your task table").setItems(names,(d,which)->loadMapping(secret,sources.optJSONObject(which).optString("id"),mapping,status)).show();
                }
            },e->{ connect.setEnabled(true); status.setText(Ui.error(e)); });
        });
        if(store.ready()) Ui.button(this,body,"Back to tasks",this::home);
    }
    private List<JSONObject> properties(JSONObject schema,String type) throws Exception {
        List<JSONObject> result=new ArrayList<>(); JSONObject props=schema.getJSONObject("properties");
        for(Iterator<String> it=props.keys();it.hasNext();) { String name=it.next(); JSONObject p=props.getJSONObject(name); if(type.equals(p.optString("type"))) result.add(new JSONObject(p.toString()).put("name",name)); }
        if(result.isEmpty()) throw new IllegalArgumentException("This table needs a "+type+" property. Use Status for progress and a multi-select property for tags.");
        return result;
    }
    private Spinner choose(LinearLayout body,String label,List<JSONObject> options) {
        Ui.text(this,body,label,14); Spinner spinner=new Spinner(this);
        List<String> labels=new ArrayList<>(); for(JSONObject p:options) labels.add(p.optString("name"));
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,labels); adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter); spinner.setMinimumHeight(Ui.dp(this,48)); body.addView(spinner); return spinner;
    }
    private List<JSONObject> objects(JSONArray a) { List<JSONObject> l=new ArrayList<>(); for(int i=0;i<a.length();i++)l.add(a.optJSONObject(i)); return l; }
    private void loadMapping(String secret,String source,LinearLayout body,TextView status) {
        status.setText("Reading statuses and tags…");
        Ui.async(this,()->new NotionApi(secret).schema(source),schema->{
            body.removeAllViews();
            try {
                List<JSONObject> titles=properties(schema,"title"), statuses=properties(schema,"status"), tags=properties(schema,"multi_select");
                Spinner title=choose(body,"Task title",titles), statusProp=choose(body,"Status property",statuses), tagProp=choose(body,"Tags property",tags);
                LinearLayout optionsBody=new LinearLayout(this); optionsBody.setOrientation(LinearLayout.VERTICAL); body.addView(optionsBody);
                Runnable renderOptions=()->{
                    optionsBody.removeAllViews();
                    JSONObject property=statuses.get(statusProp.getSelectedItemPosition());
                    JSONObject statusSchema=property.optJSONObject("status");
                    List<JSONObject> options=objects(statusSchema.optJSONArray("options"));
                    if(options.size()<2) { status.setText("The Status property needs at least two options."); return; }
                    Spinner todo=choose(optionsBody,"New tasks start as",options), done=choose(optionsBody,"Mark completed tasks as (hidden from widget)",options);
                    done.setSelection(options.size()-1);
                    // Notion status groups are ordered To-do, In progress, Complete.
                    JSONArray groups=statusSchema.optJSONArray("groups");
                    if(groups!=null && groups.length()>0) {
                        JSONArray completeIds=groups.optJSONObject(groups.length()-1).optJSONArray("option_ids");
                        if(completeIds!=null && completeIds.length()>0) for(int i=0;i<options.size();i++) if(options.get(i).optString("id").equals(completeIds.optString(0))) done.setSelection(i);
                    }
                    Button save=Ui.button(this,optionsBody,"Save and load tasks",()->{});
                    save.setOnClickListener(v->{
                        if(todo.getSelectedItemPosition()==done.getSelectedItemPosition()) { status.setText("Choose different statuses for new and completed tasks."); return; }
                        save.setEnabled(false); status.setText("Checking the table and saving…");
                        int titleIndex=title.getSelectedItemPosition(), tagIndex=tagProp.getSelectedItemPosition();
                        int todoIndex=todo.getSelectedItemPosition(), doneIndex=done.getSelectedItemPosition();
                        Ui.async(this,()->{
                            JSONObject c=new JSONObject().put("source",source).put("title",titles.get(titleIndex).getString("id"))
                                .put("status",property.getString("id")).put("tags",tags.get(tagIndex).getString("id"))
                                .put("todo",options.get(todoIndex).getString("id"))
                                .put("done",options.get(doneIndex).getString("id"))
                                .put("doneName",options.get(doneIndex).getString("name"));
                            synchronized(Store.LOCK) {
                                JSONArray tasks=new NotionApi(secret).tasks(c); store.configure(secret,c,tasks);
                            }
                            return true;
                        },ok->{ SyncWorker.schedule(this); TodoWidget.render(this); home(); },e->{save.setEnabled(true);status.setText(Ui.error(e));});
                    });
                };
                statusProp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    public void onItemSelected(AdapterView<?> p,View v,int pos,long id) { renderOptions.run(); }
                    public void onNothingSelected(AdapterView<?> p) { }
                });
                status.setText("Choose the properties used by your TODO table.");
            } catch(Exception e) { status.setText(Ui.error(e)); }
        },e->status.setText(Ui.error(e)));
    }
}

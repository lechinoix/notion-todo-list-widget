package com.lechinoix.notiontodo;

import android.app.*;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputFilter;
import android.widget.*;
import org.json.*;
import java.util.*;

public class TaskActivity extends Activity {
    private final Set<String> selected=new LinkedHashSet<>(), original=new LinkedHashSet<>();
    private String page;
    private JSONObject config;
    private Store store;
    private TextView status;
    private EditText title;
    private Button save;
    private boolean writing=false, loaded=false;
    private Bundle restored;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); store=new Store(this); restored=state;
        if(!store.ready()) { startActivity(new Intent(this,MainActivity.class)); finish(); return; }
        page=getIntent().getStringExtra("page");
        if(getIntent().getBooleanExtra("complete",false) && page!=null) { SyncWorker.enqueue(this,page); finish(); return; }
        LinearLayout body=Ui.screen(this);
        Ui.text(this,body,page==null ? "One less thing to remember." : "Task tags",23);
        title=new EditText(this); title.setHint("What needs doing?"); title.setTextSize(20); title.setMaxLines(4); title.setFilters(new InputFilter[]{new InputFilter.LengthFilter(2000)}); title.setSaveEnabled(false); body.addView(title);
        if(page!=null) title.setVisibility(android.view.View.GONE);
        else if(state!=null) title.setText(state.getString("title",""));
        status=Ui.text(this,body,"Loading tags…",14);
        LinearLayout tags=new LinearLayout(this); tags.setOrientation(LinearLayout.VERTICAL); body.addView(tags);
        save=Ui.button(this,body,page==null ? "Create task" : "Save tags",this::save); save.setEnabled(false);
        Ui.button(this,body,"Cancel",()->{if(!writing) finish();});
        load(tags);
    }
    @Override public void onBackPressed() { if(!writing) super.onBackPressed(); }
    @Override public void onSaveInstanceState(Bundle state) {
        if(title!=null) state.putString("title",title.getText().toString());
        if(loaded) state.putStringArrayList("selected",new ArrayList<>(selected)); super.onSaveInstanceState(state);
    }
    private void load(LinearLayout tags) {
        Ui.async(this,()->{
            synchronized(Store.LOCK) {
                config=store.config(); NotionApi api=new NotionApi(store.token());
                JSONObject schema=api.schema(config.getString("source"));
                JSONArray options=NotionApi.property(schema,config.getString("tags")).getJSONObject("multi_select").getJSONArray("options");
                JSONObject task=page==null ? null : NotionApi.task(api.page(page),config);
                return new Object[]{options,task};
            }
        },data->{
            JSONArray options=(JSONArray)data[0]; JSONObject task=(JSONObject)data[1];
            if(task!=null) {
                status.setText(task.optString("title"));
                JSONArray existing=task.optJSONArray("tags");
                for(int i=0;i<existing.length();i++) original.add(existing.optJSONObject(i).optString("id"));
            } else status.setText("Choose existing tags (optional).");
            selected.clear(); selected.addAll(original);
            if(restored!=null && restored.containsKey("selected")) { selected.clear(); selected.addAll(restored.getStringArrayList("selected")); }
            if(options.length()==0) Ui.text(this,tags,"No tags yet. Add tag options in Notion, then reopen this screen.",14);
            for(int i=0;i<options.length();i++) {
                JSONObject option=options.optJSONObject(i); String id=option.optString("id");
                CheckBox box=new CheckBox(this); box.setText(option.optString("name")); box.setMinHeight(Ui.dp(this,48)); box.setChecked(selected.contains(id)); tags.addView(box);
                box.setOnCheckedChangeListener((v,checked)->{if(checked)selected.add(id);else selected.remove(id);});
            }
            loaded=true; save.setEnabled(true);
        },e->{status.setText(Ui.error(e)); Ui.button(this,tags,"Retry loading tags",()->{tags.removeAllViews();load(tags);});});
    }
    private void save() {
        String name=title.getText().toString().trim();
        if(page==null && name.isEmpty()) { title.setError("Enter a task title"); return; }
        writing=true; save.setEnabled(false); status.setText("Saving to Notion…");
        Set<String> chosen=new LinkedHashSet<>(selected);
        Ui.async(this,()->{
            synchronized(Store.LOCK) {
                if(!store.config().toString().equals(config.toString())) throw new IllegalArgumentException("Table settings changed. Reopen this task before saving.");
                NotionApi api=new NotionApi(store.token());
                JSONArray options=NotionApi.property(api.schema(config.getString("source")),config.getString("tags")).getJSONObject("multi_select").getJSONArray("options");
                Set<String> valid=new HashSet<>(); for(int i=0;i<options.length();i++)valid.add(options.getJSONObject(i).getString("id"));
                if(!valid.containsAll(chosen)) throw new IllegalArgumentException("A selected tag was removed in Notion. Reopen this task to reload tags.");
                if(page==null) {
                    JSONObject created=NotionApi.task(api.create(name,chosen,config),config);
                    JSONArray tasks=new JSONArray().put(created), old=store.tasks(); for(int i=0;i<old.length();i++)tasks.put(old.get(i)); store.tasks(tasks);
                } else {
                    // Apply only this editor's tag changes, retaining tags added elsewhere meanwhile.
                    JSONObject latest=NotionApi.task(api.page(page),config);
                    Set<String> merged=new LinkedHashSet<>(); JSONArray current=latest.getJSONArray("tags");
                    for(int i=0;i<current.length();i++) merged.add(current.getJSONObject(i).getString("id"));
                    for(String id:original) if(!chosen.contains(id)) merged.remove(id);
                    for(String id:chosen) if(!original.contains(id)) merged.add(id);
                    api.tags(page,config,merged);
                    JSONArray updated=new JSONArray(); for(int i=0;i<options.length();i++) if(merged.contains(options.getJSONObject(i).getString("id")))updated.put(options.getJSONObject(i));
                    latest.put("tags",updated); JSONArray tasks=store.tasks();
                    for(int i=0;i<tasks.length();i++) if(page.equals(tasks.getJSONObject(i).getString("id")))tasks.put(i,latest);
                    store.tasks(tasks);
                }
                store.message("");
                try { store.refresh(); } catch(Exception e) { store.message("Saved to Notion. List refresh failed; tap ↻ to retry."); }
                TodoWidget.render(this); return true;
            }
        },ok->{writing=false;Toast.makeText(this,page==null ? "Task created" : "Tags saved",Toast.LENGTH_SHORT).show();finish();},e->{
            writing=false; save.setEnabled(true);
            status.setText(Ui.error(e)+(page==null ? "\nIf the connection dropped during creation, refresh your task list before trying again to avoid a duplicate." : ""));
        });
    }
}

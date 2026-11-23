package com.lechinoix.notiontodo;

import android.app.Instrumentation;
import android.appwidget.*;
import android.content.*;
import android.view.*;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AndroidSmokeTest {
    Context context;
    Instrumentation instrumentation;
    @Before public void setup() {instrumentation=InstrumentationRegistry.getInstrumentation();context=instrumentation.getTargetContext();}
    @After public void cleanup() {context.getSharedPreferences("notion",Context.MODE_PRIVATE).edit().clear().commit();TodoWidget.completing.clear();}
    @Test public void tokenIsEncryptedAndCacheSurvivesStoreRecreation() throws Exception {
        Store store=new Store(context);
        JSONObject config=new JSONObject().put("source","test-source");
        JSONArray tasks=new JSONArray().put(new JSONObject().put("id","task1").put("title","Acheter des croquettes").put("tags",new JSONArray()));
        store.configure("test-not-a-real-token",config,tasks);
        assertEquals("test-not-a-real-token",new Store(context).token());
        String disk=context.getSharedPreferences("notion",Context.MODE_PRIVATE).getString("secret","");
        assertFalse(disk.contains("test-not-a-real-token"));
        assertEquals("Acheter des croquettes",new Store(context).tasks().getJSONObject(0).getString("title"));
        store.remove("task1"); assertEquals(0,new Store(context).tasks().length());
    }
    @Test public void setupActivityAndWidgetLayoutsInflate() {
        context.getSharedPreferences("notion",Context.MODE_PRIVATE).edit().clear().commit();
        MainActivity activity=(MainActivity)instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.runOnMainSync(()->{
            assertTrue(containsText(activity.getWindow().getDecorView(),"Connect your Notion"));
            RemoteViews widget=new RemoteViews(context.getPackageName(),R.layout.widget);
            View view=widget.apply(context,new FrameLayout(context));
            assertNotNull(view.findViewById(R.id.tasks));assertNotNull(view.findViewById(R.id.add));
            RemoteViews row=new RemoteViews(context.getPackageName(),R.layout.task_row);
            assertNotNull(row.apply(context,new FrameLayout(context)).findViewById(R.id.done));
            activity.finish();
        });
    }
    @Test public void providerIsRegisteredAsResizableHomeWidget() {
        AppWidgetProviderInfo provider=AppWidgetManager.getInstance(context).getInstalledProviders().stream()
            .filter(p->p.provider.equals(new ComponentName(context,TodoWidget.class))).findFirst().orElseThrow();
        assertEquals(AppWidgetProviderInfo.RESIZE_BOTH,provider.resizeMode);
        assertEquals(0,provider.updatePeriodMillis);
    }
    @Test public void widgetEditorUsesAnIndependentTask() {
        Intent intent=TaskActivity.fromWidget(context);
        assertTrue(intent.getBooleanExtra("fromWidget",false));
        assertTrue((intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK)!=0);
        assertTrue((intent.getFlags() & Intent.FLAG_ACTIVITY_MULTIPLE_TASK)!=0);
        assertTrue((intent.getFlags() & Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)!=0);
    }
    @Test public void completionFeedbackResetsWhenRowIsReused() throws Exception {
        String id="12345678-1234-1234-1234-123456789abc";
        new Store(context).tasks(new JSONArray().put(new JSONObject().put("id",id).put("title","Example").put("tags",new JSONArray())));
        // Attach a real context without starting the remote service or making a network request.
        class TestService extends TaskListService { TestService(Context base) { attachBaseContext(base); } }
        TaskListService service=new TestService(context);
        RemoteViewsService.RemoteViewsFactory factory=service.onGetViewFactory(new Intent()); factory.onCreate();
        instrumentation.runOnMainSync(()->{
            TodoWidget.completing.put(id,1);
            View row=factory.getViewAt(0).apply(context,new FrameLayout(context));
            assertEquals("✓",((TextView)row.findViewById(R.id.done)).getText().toString());
            assertTrue((((TextView)row.findViewById(R.id.title)).getPaintFlags() & android.graphics.Paint.STRIKE_THRU_TEXT_FLAG)!=0);
            TodoWidget.completing.put(id,2); factory.getViewAt(0).reapply(context,row);
            assertEquals(0x55758575,((TextView)row.findViewById(R.id.title)).getCurrentTextColor());
            TodoWidget.completing.clear(); factory.getViewAt(0).reapply(context,row);
            assertEquals("○",((TextView)row.findViewById(R.id.done)).getText().toString());
            assertEquals(0,((TextView)row.findViewById(R.id.title)).getPaintFlags() & android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
        });
        factory.onDestroy();
    }
    private boolean containsText(View view,String text) {
        if(view instanceof TextView && ((TextView)view).getText().toString().contains(text))return true;
        if(view instanceof ViewGroup) {ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(containsText(group.getChildAt(i),text))return true;}
        return false;
    }
}

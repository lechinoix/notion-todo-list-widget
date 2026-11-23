package com.lechinoix.notiontodo;

import org.junit.*;
import org.json.*;
import static org.junit.Assert.*;
import java.util.*;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.*;

public class NotionApiTest {
    MockWebServer server;
    NotionApi api;
    JSONObject c;
    @Before public void before() throws Exception {
        server=new MockWebServer(); server.start();
        api=new NotionApi("test-secret",server.url("/v1/").toString(),new OkHttpClient.Builder().retryOnConnectionFailure(false).build());
        c=new JSONObject("{\"source\":\"source-id\",\"title\":\"title\",\"status\":\"s%3A\",\"tags\":\"tags-id\",\"todo\":\"todo-id\",\"done\":\"done-id\",\"doneName\":\"Complete\"}");
    }
    @After public void after() throws Exception {server.shutdown();}
    void reply(String body) {server.enqueue(new MockResponse().setHeader("Content-Type","application/json").setBody(body));}
    String page(String id) { return "{\"id\":\""+id+"\",\"properties\":{\"Renamed title\":{\"id\":\"title\",\"title\":[{\"plain_text\":\"Acheter \"},{\"plain_text\":\"des croquettes 🐶\"}]},\"Renamed tags\":{\"id\":\"tags-id\",\"multi_select\":[{\"id\":\"pet-id\",\"name\":\"Chien chien\"}]}}}"; }
    @Test public void extractsDatabaseNotViewId() {
        assertEquals("12345678-1234-1234-1234-123456789abc",NotionApi.extractId("https://www.notion.so/TODO-12345678123412341234123456789abc?v=ffffffffffffffffffffffffffffffff"));
    }
    @Test public void acceptsUuid() {assertEquals("12345678-1234-1234-1234-123456789abc",NotionApi.extractId("12345678-1234-1234-1234-123456789abc"));}
    @Test(expected=IllegalArgumentException.class) public void rejectsMissingId() {NotionApi.extractId("TODO");}
    @Test public void resolvesDatabaseSources() throws Exception {
        reply("{\"data_sources\":[{\"id\":\"source1\",\"name\":\"Tasks\"},{\"id\":\"source2\",\"name\":\"Notes\"}]}");
        assertEquals(2,api.sources("12345678123412341234123456789abc").length());
        assertTrue(server.takeRequest().getPath().startsWith("/v1/databases/"));
    }
    @Test public void acceptsDirectDataSourceId() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(404)); reply("{\"title\":[{\"plain_text\":\"Tasks\"}]}");
        assertEquals("Tasks",api.sources("12345678123412341234123456789abc").getJSONObject(0).getString("name"));
        server.takeRequest(); assertTrue(server.takeRequest().getPath().startsWith("/v1/data_sources/"));
    }
    @Test public void readsAllPagesAndUsesPropertyIds() throws Exception {
        reply("{\"results\":["+page("one")+"],\"has_more\":true,\"next_cursor\":\"next\"}");
        reply("{\"results\":["+page("two")+"],\"has_more\":false,\"next_cursor\":null}");
        JSONArray tasks=api.tasks(c); assertEquals(2,tasks.length());
        assertEquals("Acheter des croquettes 🐶",tasks.getJSONObject(0).getString("title"));
        RecordedRequest first=server.takeRequest();
        assertEquals("Bearer test-secret",first.getHeader("Authorization"));
        assertEquals("2025-09-03",first.getHeader("Notion-Version"));
        JSONObject body=new JSONObject(first.getBody().readUtf8());
        assertEquals("s%3A",body.getJSONObject("filter").getString("property"));
        assertEquals("Complete",body.getJSONObject("filter").getJSONObject("status").getString("does_not_equal"));
        assertEquals("next",new JSONObject(server.takeRequest().getBody().readUtf8()).getString("start_cursor"));
    }
    @Test public void createsWithStatusAndExistingTagIds() throws Exception {
        reply(page("new")); api.create("  New task  ",List.of("pet-id","pet-id"),c);
        RecordedRequest request=server.takeRequest(); assertEquals("POST",request.getMethod()); assertEquals("/v1/pages",request.getPath());
        JSONObject body=new JSONObject(request.getBody().readUtf8());
        assertEquals("source-id",body.getJSONObject("parent").getString("data_source_id"));
        JSONObject props=body.getJSONObject("properties");
        assertEquals("New task",props.getJSONObject("title").getJSONArray("title").getJSONObject(0).getJSONObject("text").getString("content"));
        assertEquals("todo-id",props.getJSONObject("s%3A").getJSONObject("status").getString("id"));
        JSONArray tags=props.getJSONObject("tags-id").getJSONArray("multi_select"); assertEquals(1,tags.length());assertEquals("pet-id",tags.getJSONObject(0).getString("id"));
        assertFalse(tags.getJSONObject(0).has("name"));
    }
    @Test public void completingOnlyPatchesStatus() throws Exception {
        reply("{}"); api.complete("task-id",c); RecordedRequest request=server.takeRequest();
        assertEquals("PATCH",request.getMethod()); assertEquals("/v1/pages/task-id",request.getPath());
        JSONObject props=new JSONObject(request.getBody().readUtf8()).getJSONObject("properties");
        assertEquals(1,props.length()); assertEquals("done-id",props.getJSONObject("s%3A").getJSONObject("status").getString("id"));
    }
    @Test public void canRemoveAllTagsWithoutTouchingOtherProperties() throws Exception {
        reply("{}"); api.tags("task-id",c,List.of());
        JSONObject props=new JSONObject(server.takeRequest().getBody().readUtf8()).getJSONObject("properties");
        assertEquals(1,props.length());assertEquals(0,props.getJSONObject("tags-id").getJSONArray("multi_select").length());
    }
    @Test public void errorsDoNotLeakServerBodyOrToken() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("test-secret"));
        try {api.tasks(c);fail();} catch(NotionApi.ApiException e) {assertEquals(401,e.status);assertFalse(e.getMessage().contains("test-secret"));}
    }
    @Test public void failedCreateIsNotRetried() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503));
        try {api.create("Task",List.of(),c);fail();} catch(NotionApi.ApiException e) {assertEquals(503,e.status);}
        assertEquals(1,server.getRequestCount());
    }
    @Test public void rejectsEmptyTitlesBeforeNetwork() throws Exception {
        try {api.create("  ",List.of(),c);fail();} catch(IllegalArgumentException expected) {assertEquals(0,server.getRequestCount());}
    }
}

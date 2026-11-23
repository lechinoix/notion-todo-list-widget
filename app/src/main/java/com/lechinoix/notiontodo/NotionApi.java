package com.lechinoix.notiontodo;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;
import okhttp3.*;

/** Small, version-pinned Notion client. Never logs requests or credentials. */
public final class NotionApi {
    private final String token, base;
    private final OkHttpClient http;
    public NotionApi(String token) { this(token, "https://api.notion.com/v1/", new OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()); }
    NotionApi(String token, String base, OkHttpClient http) { this.token=token; this.base=base; this.http=http; }
    public static final class ApiException extends IOException {
        public final int status;
        ApiException(int status, String message) { super(message); this.status=status; }
    }
    JSONObject call(String method, String path, JSONObject body) throws Exception {
        RequestBody data = body == null ? null : RequestBody.create(body.toString(), MediaType.get("application/json; charset=utf-8"));
        Request request = new Request.Builder().url(base+path).header("Authorization", "Bearer "+token)
            .header("Notion-Version", "2025-09-03").method(method,data).build();
        try (Response response=http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String message = switch (response.code()) {
                    case 401 -> "Notion rejected the token. Check your connection secret in Settings.";
                    case 403 -> "Allow Read, Insert and Update content for this Notion connection.";
                    case 404 -> "Table not found. Share the original database with your Notion connection, then check its link.";
                    case 429 -> "Notion is busy (rate limit). Wait a moment and refresh.";
                    default -> "Notion request failed (HTTP "+response.code()+"). Refresh the table setup if its properties changed.";
                };
                throw new ApiException(response.code(),message);
            }
            return new JSONObject(Objects.requireNonNull(response.body()).string());
        }
    }
    public static String extractId(String input) {
        String value=input.trim();
        if (value.startsWith("https://") || value.startsWith("http://")) value=URI.create(value).getPath();
        Matcher m=Pattern.compile("(?i)[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}").matcher(value);
        String found=null; while(m.find()) found=m.group();
        if(found==null) throw new IllegalArgumentException("Paste the original Notion database link or its 32-character ID.");
        String s=found.replace("-","");
        return s.substring(0,8)+"-"+s.substring(8,12)+"-"+s.substring(12,16)+"-"+s.substring(16,20)+"-"+s.substring(20);
    }
    public JSONArray sources(String input) throws Exception {
        String id=extractId(input);
        try { return call("GET","databases/"+id,null).getJSONArray("data_sources"); }
        catch(ApiException e) {
            if(e.status!=404) throw e;
            JSONObject source=schema(id);
            return new JSONArray().put(new JSONObject().put("id",id).put("name",plain(source.optJSONArray("title"))));
        }
    }
    public JSONObject schema(String id) throws Exception { return call("GET","data_sources/"+id,null); }
    public static String plain(JSONArray rich) {
        StringBuilder b=new StringBuilder();
        if(rich!=null) for(int i=0;i<rich.length();i++) {
            JSONObject part=rich.optJSONObject(i);
            if(part!=null) b.append(part.optString("plain_text",part.optJSONObject("text")==null ? "" : part.optJSONObject("text").optString("content")));
        }
        return b.toString();
    }
    public static JSONObject property(JSONObject schema,String id) throws Exception {
        JSONObject props=schema.getJSONObject("properties");
        for(Iterator<String> it=props.keys();it.hasNext();) {
            JSONObject p=props.getJSONObject(it.next()); if(id.equals(p.getString("id"))) return p;
        }
        throw new IllegalArgumentException("A selected property was removed. Reconnect the table in Settings.");
    }
    public static JSONObject value(JSONObject page,String id) throws Exception {
        JSONObject props=page.getJSONObject("properties");
        for(Iterator<String> it=props.keys();it.hasNext();) {
            JSONObject p=props.getJSONObject(it.next()); if(id.equals(p.optString("id"))) return p;
        }
        throw new IllegalArgumentException("A task is missing a selected property. Refresh the table setup.");
    }
    public JSONArray tasks(JSONObject config) throws Exception {
        JSONObject filter=new JSONObject().put("property",config.getString("status"))
            .put("status",new JSONObject().put("does_not_equal",config.getString("doneName")));
        JSONArray result=new JSONArray(); String cursor=null;
        do {
            JSONObject body=new JSONObject().put("page_size",100).put("filter",filter)
                .put("sorts",new JSONArray().put(new JSONObject().put("timestamp","created_time").put("direction","descending")));
            if(cursor!=null) body.put("start_cursor",cursor);
            JSONObject response=call("POST","data_sources/"+config.getString("source")+"/query",body);
            JSONArray pages=response.getJSONArray("results");
            for(int i=0;i<pages.length();i++) result.put(task(pages.getJSONObject(i),config));
            cursor=response.optBoolean("has_more") ? response.getString("next_cursor") : null;
        } while(cursor!=null);
        return result;
    }
    public static JSONObject task(JSONObject page,JSONObject config) throws Exception {
        return new JSONObject().put("id",page.getString("id"))
            .put("title",plain(value(page,config.getString("title")).getJSONArray("title")))
            .put("tags",value(page,config.getString("tags")).getJSONArray("multi_select"));
    }
    public JSONObject page(String id) throws Exception { return call("GET","pages/"+id,null); }
    public void complete(String id,JSONObject c) throws Exception {
        patch(id,new JSONObject().put(c.getString("status"),new JSONObject().put("status",new JSONObject().put("id",c.getString("done")))));
    }
    public void tags(String id,JSONObject c,Collection<String> ids) throws Exception {
        patch(id,new JSONObject().put(c.getString("tags"),new JSONObject().put("multi_select",tagIds(ids))));
    }
    public JSONObject create(String title,Collection<String> tags,JSONObject c) throws Exception {
        if(title.trim().isEmpty() || title.length()>2000) throw new IllegalArgumentException("Use a task title between 1 and 2,000 characters.");
        JSONObject props=new JSONObject()
            .put(c.getString("title"),new JSONObject().put("title",new JSONArray().put(new JSONObject().put("text",new JSONObject().put("content",title.trim())))))
            .put(c.getString("status"),new JSONObject().put("status",new JSONObject().put("id",c.getString("todo"))))
            .put(c.getString("tags"),new JSONObject().put("multi_select",tagIds(tags)));
        // No automatic retry: a failed response may still have created the page.
        return call("POST","pages",new JSONObject().put("parent",new JSONObject().put("type","data_source_id").put("data_source_id",c.getString("source"))).put("properties",props));
    }
    private static JSONArray tagIds(Collection<String> ids) throws Exception {
        JSONArray a=new JSONArray(); for(String id:new LinkedHashSet<>(ids)) a.put(new JSONObject().put("id",id)); return a;
    }
    private void patch(String id,JSONObject props) throws Exception { call("PATCH","pages/"+id,new JSONObject().put("properties",props)); }
}

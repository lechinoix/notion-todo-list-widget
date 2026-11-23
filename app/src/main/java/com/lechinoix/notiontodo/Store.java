package com.lechinoix.notiontodo;

import android.content.*;
import android.security.keystore.*;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.json.*;

public final class Store {
    // Serializes refresh, settings and writes so stale reads cannot overwrite newer results.
    public static final Object LOCK=new Object();
    private final SharedPreferences prefs;
    public Store(Context c) { prefs=c.getSharedPreferences("notion",Context.MODE_PRIVATE); }
    public JSONObject config() throws Exception { return new JSONObject(prefs.getString("config","{}")); }
    public boolean ready() { return prefs.contains("config") && prefs.contains("secret"); }
    private SecretKey key() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if(!ks.containsAlias("notion-token")) {
            KeyGenerator generator=KeyGenerator.getInstance("AES","AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder("notion-token",KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey)ks.getKey("notion-token",null);
    }
    public String token() throws Exception {
        if(!ready()) return "";
        String[] pair=prefs.getString("secret","").split(":");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(pair[0],Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(pair[1],Base64.NO_WRAP)),java.nio.charset.StandardCharsets.UTF_8);
    }
    public void configure(String token,JSONObject config,JSONArray tasks) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key());
        String secret=Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(cipher.doFinal(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)),Base64.NO_WRAP);
        if(!prefs.edit().putString("secret",secret).putString("config",config.toString()).putString("tasks",tasks.toString())
            .putLong("synced",System.currentTimeMillis()).putString("message","").commit()) throw new java.io.IOException("Could not save settings on this device.");
    }
    public JSONArray tasks() { try { return new JSONArray(prefs.getString("tasks","[]")); } catch(Exception e) { return new JSONArray(); } }
    public void tasks(JSONArray a) { prefs.edit().putString("tasks",a.toString()).apply(); }
    public void remove(String id) throws Exception {
        JSONArray a=tasks(), b=new JSONArray();
        for(int i=0;i<a.length();i++) if(!id.equals(a.getJSONObject(i).getString("id"))) b.put(a.get(i)); tasks(b);
    }
    public void message(String text) { prefs.edit().putString("message",text).apply(); }
    public String message() { return prefs.getString("message",""); }
    public long synced() { return prefs.getLong("synced",0); }
    public void refresh() throws Exception {
        NotionApi api=new NotionApi(token()); JSONObject c=config();
        JSONObject schema=api.schema(c.getString("source"));
        JSONArray statuses=NotionApi.property(schema,c.getString("status")).getJSONObject("status").getJSONArray("options");
        boolean found=false;
        for(int i=0;i<statuses.length();i++) if(c.getString("done").equals(statuses.getJSONObject(i).getString("id"))) {
            c.put("doneName",statuses.getJSONObject(i).getString("name")); found=true; break;
        }
        if(!found) throw new IllegalArgumentException("The completed status was removed. Reconnect the table in Settings.");
        JSONArray a=api.tasks(c); tasks(a);
        prefs.edit().putLong("synced",System.currentTimeMillis()).putString("message","").apply();
    }
}

package com.lechinoix.notiontodo

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** Small, version-pinned Notion client. Never logs requests or credentials. */
class NotionApi(private val token: String, private val base: String, private val http: OkHttpClient) {
    constructor(token: String) : this(token, "https://api.notion.com/v1/", OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS).retryOnConnectionFailure(false).build())

    class ApiException(@JvmField val status: Int, message: String) : IOException(message)

    private fun call(method: String, path: String, body: JSONObject? = null): JSONObject {
        val data = body?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(base + path).header("Authorization", "Bearer $token")
            .header("Notion-Version", "2025-09-03").method(method, data).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val message = when (response.code) {
                    401 -> "Notion rejected the token. Check your connection secret in Settings."
                    403 -> "Allow Read, Insert and Update content for this Notion connection."
                    404 -> "Table not found. Share the original database with your Notion connection, then check its link."
                    429 -> "Notion is busy (rate limit). Wait a moment and refresh."
                    else -> "Notion request failed (HTTP ${response.code}). Refresh the table setup if its properties changed."
                }
                throw ApiException(response.code, message)
            }
            return JSONObject(requireNotNull(response.body).string())
        }
    }

    @Throws(Exception::class)
    fun sources(input: String): JSONArray {
        val id = extractId(input)
        return try { call("GET", "databases/$id").getJSONArray("data_sources") }
        catch (e: ApiException) {
            if (e.status != 404) throw e
            JSONArray().put(JSONObject().put("id", id).put("name", plain(schema(id).optJSONArray("title"))))
        }
    }
    @Throws(Exception::class)
    fun schema(id: String): JSONObject = call("GET", "data_sources/$id")

    @Throws(Exception::class)
    fun tasks(config: JSONObject): JSONArray {
        val filter = JSONObject().put("property", config.getString("status"))
            .put("status", JSONObject().put("does_not_equal", config.getString("doneName")))
        val result = JSONArray()
        var cursor: String? = null
        do {
            val body = JSONObject().put("page_size", 100).put("filter", filter)
                .put("sorts", JSONArray().put(JSONObject().put("timestamp", "created_time").put("direction", "descending")))
            cursor?.let { body.put("start_cursor", it) }
            val response = call("POST", "data_sources/${config.getString("source")}/query", body)
            val pages = response.getJSONArray("results")
            for (i in 0 until pages.length()) result.put(task(pages.getJSONObject(i), config))
            cursor = if (response.optBoolean("has_more")) response.getString("next_cursor") else null
        } while (cursor != null)
        return result
    }
    @Throws(Exception::class)
    fun page(id: String): JSONObject = call("GET", "pages/$id")
    @Throws(Exception::class)
    fun complete(id: String, c: JSONObject) {
        patch(id, JSONObject().put(c.getString("status"), JSONObject().put("status", JSONObject().put("id", c.getString("done")))))
    }
    @Throws(Exception::class)
    fun tags(id: String, c: JSONObject, ids: Collection<String>) {
        patch(id, JSONObject().put(c.getString("tags"), JSONObject().put("multi_select", tagIds(ids))))
    }
    @Throws(Exception::class)
    fun create(title: String, tags: Collection<String>, c: JSONObject): JSONObject {
        require(title.trim().isNotEmpty() && title.length <= 2000) { "Use a task title between 1 and 2,000 characters." }
        val props = JSONObject()
            .put(c.getString("title"), JSONObject().put("title", JSONArray().put(JSONObject().put("text", JSONObject().put("content", title.trim())))))
            .put(c.getString("status"), JSONObject().put("status", JSONObject().put("id", c.getString("todo"))))
            .put(c.getString("tags"), JSONObject().put("multi_select", tagIds(tags)))
        // No automatic retry: a failed response may still have created the page.
        return call("POST", "pages", JSONObject().put("parent", JSONObject().put("type", "data_source_id")
            .put("data_source_id", c.getString("source"))).put("properties", props))
    }
    private fun patch(id: String, props: JSONObject) { call("PATCH", "pages/$id", JSONObject().put("properties", props)) }

    companion object {
        @JvmStatic fun extractId(input: String): String {
            val trimmed = input.trim()
            val value = if (trimmed.startsWith("https://") || trimmed.startsWith("http://")) URI.create(trimmed).path else trimmed
            val found = Regex("[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}", RegexOption.IGNORE_CASE)
                .findAll(value).lastOrNull()?.value ?: throw IllegalArgumentException("Paste the original Notion database link or its 32-character ID.")
            val s = found.replace("-", "")
            return "${s.substring(0,8)}-${s.substring(8,12)}-${s.substring(12,16)}-${s.substring(16,20)}-${s.substring(20)}"
        }
        @JvmStatic fun plain(rich: JSONArray?): String = buildString {
            if (rich != null) for (i in 0 until rich.length()) {
                val part = rich.optJSONObject(i) ?: continue
                append(part.optString("plain_text", part.optJSONObject("text")?.optString("content") ?: ""))
            }
        }
        @JvmStatic fun property(schema: JSONObject, id: String): JSONObject {
            val props = schema.getJSONObject("properties")
            for (name in props.keys()) {
                val p = props.getJSONObject(name)
                if (id == p.getString("id")) return p
            }
            throw IllegalArgumentException("A selected property was removed. Reconnect the table in Settings.")
        }
        @JvmStatic fun value(page: JSONObject, id: String): JSONObject {
            val props = page.getJSONObject("properties")
            for (name in props.keys()) {
                val p = props.getJSONObject(name)
                if (id == p.optString("id")) return p
            }
            throw IllegalArgumentException("A task is missing a selected property. Refresh the table setup.")
        }
        @JvmStatic fun task(page: JSONObject, config: JSONObject): JSONObject = JSONObject().put("id", page.getString("id"))
            .put("title", plain(value(page, config.getString("title")).getJSONArray("title")))
            .put("tags", value(page, config.getString("tags")).getJSONArray("multi_select"))
        private fun tagIds(ids: Collection<String>): JSONArray = JSONArray().apply {
            ids.toSet().forEach { put(JSONObject().put("id", it)) }
        }
    }
}

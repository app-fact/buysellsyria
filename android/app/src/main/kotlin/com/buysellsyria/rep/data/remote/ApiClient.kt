package com.buysellsyria.rep.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Error returned by Odoo (business rule, bad PIN...). Not retried automatically. */
class ApiException(val code: String, message: String, val httpStatus: Int) : Exception(message)

class ApiClient(private val baseUrl: () -> String, private val token: () -> String?) {

    val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val mediaJson = "application/json; charset=utf-8".toMediaType()

    private suspend fun post(path: String, body: String): String = withContext(Dispatchers.IO) {
        val url = baseUrl().trimEnd('/') + "/sales_rep/api/v1/" + path
        val builder = Request.Builder().url(url).post(body.toRequestBody(mediaJson))
        token()?.let { builder.header("Authorization", "Bearer $it") }
        http.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val err = runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonObject }.getOrNull()
                val code = err?.get("code")?.jsonPrimitive?.content ?: "http"
                val msg = err?.get("message")?.jsonPrimitive?.content ?: "HTTP ${resp.code}"
                throw ApiException(code, msg, resp.code)
            }
            if (text.trimStart().startsWith("<")) throw IOException("Server did not return JSON. Check the server address.")
            text
        }
    }

    suspend fun login(code: String, pin: String, deviceId: String): LoginResponse {
        val body = buildJsonObject {
            put("code", code); put("pin", pin); put("device_id", deviceId)
        }
        return json.decodeFromString(post("login", body.toString()))
    }

    suspend fun pull(since: String?): PullResponse {
        val body = buildJsonObject { if (since != null) put("since", since) }
        return json.decodeFromString(post("pull", body.toString()))
    }

    suspend fun push(ops: List<OpDto>): PushResponse =
        json.decodeFromString(post("push", json.encodeToString(PushRequest.serializer(), PushRequest(ops))))

    fun parsePayload(text: String): JsonObject = json.parseToJsonElement(text).jsonObject
}

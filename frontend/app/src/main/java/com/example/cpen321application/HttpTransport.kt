package com.example.cpen321application

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException

internal data class HttpPolicy(
    val connectTimeoutMs: Int,
    val readTimeoutMs: Int,
    val successLimitBytes: Int? = null,
    val errorLimitBytes: Int? = null,
)

internal data class HttpResponse(val status: Int, val text: String) {
    val successful: Boolean get() = status in 200..299
    fun jsonOrEmpty(): JSONObject = try { JSONObject(text) } catch (_: JSONException) { JSONObject() }
}

internal class ResponseTooLargeException : IOException("The service response is too large.")

/** Shared transport only; callers retain their own response and error contracts. */
internal object HttpTransport {
    fun open(baseUrl: String, path: String): HttpURLConnection {
        val uri = try { URI(baseUrl.trimEnd('/') + path) }
        catch (e: URISyntaxException) { throw IllegalArgumentException("The backend address is not configured correctly.", e) }
        require(!uri.host.isNullOrBlank() && uri.userInfo == null &&
            (uri.scheme == "https" || (BuildConfig.DEBUG && uri.scheme == "http"))) {
            "Configure a valid HTTPS backend address."
        }
        return uri.toURL().openConnection() as HttpURLConnection
    }

    fun request(connection: HttpURLConnection, method: String, body: JSONObject?, token: String?, policy: HttpPolicy): HttpResponse {
        try {
            connection.connectTimeout = policy.connectTimeoutMs
            connection.readTimeout = policy.readTimeoutMs
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/json")
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val payload = body.toString().toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(payload.size)
                connection.outputStream.use { it.write(payload) }
            }
            val status = connection.responseCode
            val success = status in 200..299
            val stream = if (success) connection.inputStream else connection.errorStream
            val limit = if (success) policy.successLimitBytes else policy.errorLimitBytes
            return HttpResponse(status, stream?.use { readBody(it, limit, truncate = !success) }.orEmpty())
        } finally { connection.disconnect() }
    }

    private fun readBody(stream: InputStream, limit: Int?, truncate: Boolean): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val length = if (truncate && limit != null) minOf(buffer.size, limit - output.size()) else buffer.size
            if (length == 0) break
            val read = stream.read(buffer, 0, length)
            if (read < 0) break
            if (limit != null && output.size() + read > limit) throw ResponseTooLargeException()
            output.write(buffer, 0, read)
        }
        return output.toString("UTF-8")
    }
}

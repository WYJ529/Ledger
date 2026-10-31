package com.example.cpen321application

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal data class Account(
    val id: String,
    val email: String,
    val displayName: String,
    val signInMethods: List<String>,
    val createdAt: String,
    val phoneNumber: String = "",
    val timeZone: String = "UTC",
    val defaultCurrency: String = "CAD",
    val avatarDataUrl: String = "",
)

internal data class Session(val token: String, val expiresAt: Long, val account: Account)

internal data class RegistrationChallenge(val verificationId: String, val email: String, val retryAfterSeconds: Int)

internal interface PasswordRegistrationApi {
    fun requestRegistrationCode(email: String): RegistrationChallenge
    fun register(email: String, password: String, displayName: String, verificationId: String, code: String): Session
}

internal sealed interface GoogleSignInResult {
    data class SignedIn(val session: Session) : GoogleSignInResult
    /** Google identity verified, but no Ledger account exists yet. */
    data class NeedsAccount(val idToken: String, val email: String, val displayName: String) : GoogleSignInResult
}

/** The backend rejected a request; [message] is written for the user. */
internal class ApiException(val status: Int, message: String, val retryAfterSeconds: Int? = null) : Exception(message)

/** Blocking calls to the account endpoints. Call them from Dispatchers.IO. */
internal object AccountApi : PasswordRegistrationApi {
    override fun requestRegistrationCode(email: String): RegistrationChallenge {
        val json = request("POST", "/api/auth/register/code", JSONObject().put("email", email))
        return RegistrationChallenge(json.getString("verificationId"), json.getString("email"), json.getInt("retryAfterSeconds"))
    }

    override fun register(email: String, password: String, displayName: String, verificationId: String, code: String): Session = parseSession(
        request("POST", "/api/auth/register", JSONObject().put("email", email).put("password", password).put("displayName", displayName)
            .put("verificationId", verificationId).put("code", code))
    )

    fun login(email: String, password: String): Session =
        parseSession(request("POST", "/api/auth/login", JSONObject().put("email", email).put("password", password)))

    fun signInWithGoogle(idToken: String): GoogleSignInResult {
        val json = request("POST", "/api/auth/google", JSONObject().put("idToken", idToken))
        if (json.optString("status") == "signed-in") return GoogleSignInResult.SignedIn(parseSession(json))
        val profile = json.getJSONObject("profile")
        return GoogleSignInResult.NeedsAccount(idToken, profile.getString("email"), profile.optString("displayName"))
    }

    fun registerWithGoogle(idToken: String, displayName: String): Session = parseSession(
        request("POST", "/api/auth/google/register", JSONObject().put("idToken", idToken).put("displayName", displayName))
    )

    fun currentAccount(token: String): Account =
        parseAccount(request("GET", "/api/users/me", token = token).getJSONObject("user"))

    fun logout(token: String) {
        request("POST", "/api/auth/logout", token = token)
    }

    fun updateAccount(token: String, changes: JSONObject): Account =
        parseAccount(request("PATCH", "/api/users/me", changes, token).getJSONObject("user"))

    internal fun request(method: String, path: String, body: JSONObject? = null, token: String? = null): JSONObject {
        val url = URL(BuildConfig.API_BASE_URL.trimEnd('/') + path)
        require(url.protocol == "https" || (BuildConfig.DEBUG && url.protocol == "http")) {
            "Release builds require an HTTPS backend."
        }
        var connection: HttpURLConnection? = null
        try {
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.setRequestProperty("Accept", "application/json")
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = try { if (text.isBlank()) JSONObject() else JSONObject(text) } catch (_: JSONException) { JSONObject() }
            if (status !in 200..299) {
                throw ApiException(status, apiErrorMessage(status, path, json.optString("error")),
                    json.optInt("retryAfterSeconds").takeIf { it > 0 })
            }
            return json
        } catch (e: IOException) {
            throw IOException("Can't reach the server. Check your connection and try again.", e)
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseSession(json: JSONObject) =
        Session(json.getString("token"), json.getLong("expiresAt"), parseAccount(json.getJSONObject("user")))
}

internal fun parseAccount(json: JSONObject): Account {
    val methods = json.optJSONArray("signInMethods")
    return Account(
        id = json.getString("id"),
        email = json.getString("email"),
        displayName = json.getString("displayName"),
        signInMethods = List(methods?.length() ?: 0) { methods!!.getString(it) },
        createdAt = json.optString("createdAt"),
        phoneNumber = json.optString("phoneNumber"),
        timeZone = json.optString("timeZone", "UTC"),
        defaultCurrency = json.optString("defaultCurrency", "CAD"),
        avatarDataUrl = json.optString("avatarDataUrl"),
    )
}

internal fun Account.toJson(): JSONObject = JSONObject()
    .put("id", id).put("email", email).put("displayName", displayName)
    .put("signInMethods", org.json.JSONArray(signInMethods)).put("createdAt", createdAt)
    .put("phoneNumber", phoneNumber).put("timeZone", timeZone).put("defaultCurrency", defaultCurrency)
    .put("avatarDataUrl", avatarDataUrl)

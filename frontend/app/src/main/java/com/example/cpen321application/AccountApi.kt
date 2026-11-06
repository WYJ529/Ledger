package com.example.cpen321application

import kotlinx.coroutines.CancellationException
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

internal data class Account(
    val id: String,
    val email: String,
    val displayName: String,
    val signInMethods: List<String>,
    val createdAt: String,
    val phoneNumber: String = "",
    val avatarDataUrl: String = "",
    val username: String = "",
)

internal data class Session(val token: String, val expiresAt: Long, val account: Account)

internal interface AuthenticationApi {
    fun register(username: String, password: String, confirmPassword: String): Session
    fun login(username: String, password: String): Session
    fun signInWithGoogle(idToken: String): GoogleSignInResult
    fun registerWithGoogle(idToken: String, username: String, password: String, confirmPassword: String): Session
    fun linkWithGoogle(idToken: String, username: String, password: String): Session
}

internal sealed interface GoogleSignInResult {
    data class SignedIn(val session: Session) : GoogleSignInResult
    /** Google identity verified, but no Ledger account exists yet. */
    data class NeedsAccount(val idToken: String, val email: String, val displayName: String) : GoogleSignInResult
}

/** The backend rejected a request; [message] is written for the user. */
internal class ApiException(val status: Int, message: String, val retryAfterSeconds: Int? = null) : Exception(message)

/**
 * Runs [block] and passes the failures a request is expected to have (an HTTP error, a lost connection,
 * a malformed response or rejected input) to [onFailure]. Cancellation and programming errors still propagate.
 */
internal inline fun handleRequestFailures(onFailure: (Exception) -> Unit, block: () -> Unit) {
    try { block() }
    catch (e: CancellationException) { throw e }
    catch (e: ApiException) { onFailure(e) }
    catch (e: IOException) { onFailure(e) }
    catch (e: JSONException) { onFailure(e) }
    catch (e: IllegalArgumentException) { onFailure(e) }
    catch (e: IllegalStateException) { onFailure(e) }
    catch (e: LedgerException) { onFailure(e) }
    catch (e: SecurityException) { onFailure(e) }
    catch (e: androidx.credentials.exceptions.GetCredentialException) { onFailure(e) }
    catch (e: com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException) { onFailure(e) }
}

/** Blocking calls to the account endpoints. Call them from Dispatchers.IO. */
internal object AccountApi : AuthenticationApi {
    override fun register(username: String, password: String, confirmPassword: String): Session = parseSession(
        request("POST", "/api/auth/register", JSONObject().put("username", username).put("password", password)
            .put("confirmPassword", confirmPassword))
    )

    override fun login(username: String, password: String): Session =
        parseSession(request("POST", "/api/auth/login", JSONObject().put("username", username).put("password", password)))

    override fun signInWithGoogle(idToken: String): GoogleSignInResult {
        val json = request("POST", "/api/auth/google", JSONObject().put("idToken", idToken))
        if (json.optString("status") == "signed-in") return GoogleSignInResult.SignedIn(parseSession(json))
        val profile = json.getJSONObject("profile")
        return GoogleSignInResult.NeedsAccount(idToken, profile.getString("email"), profile.optString("displayName"))
    }

    override fun registerWithGoogle(idToken: String, username: String, password: String, confirmPassword: String): Session = parseSession(
        request("POST", "/api/auth/google/register", JSONObject().put("idToken", idToken).put("username", username)
            .put("password", password).put("confirmPassword", confirmPassword))
    )

    override fun linkWithGoogle(idToken: String, username: String, password: String): Session = parseSession(
        request("POST", "/api/auth/google/link", JSONObject().put("idToken", idToken).put("username", username).put("password", password))
    )

    fun currentAccount(token: String): Account =
        parseAccount(request("GET", "/api/users/me", token = token).getJSONObject("user"))

    fun logout(token: String) {
        request("POST", "/api/auth/logout", token = token)
    }

    fun updateAccount(token: String, changes: JSONObject): Account =
        parseAccount(request("PATCH", "/api/users/me", changes, token).getJSONObject("user"))

    internal fun request(method: String, path: String, body: JSONObject? = null, token: String? = null): JSONObject {
        try {
            val response = HttpTransport.request(HttpTransport.open(BuildConfig.API_BASE_URL, path),
                method, body, token, HttpPolicy(10_000, 15_000))
            val json = response.jsonOrEmpty()
            if (!response.successful) {
                throw ApiException(response.status, apiErrorMessage(response.status, path, json.optString("error")),
                    json.optInt("retryAfterSeconds").takeIf { it > 0 })
            }
            return json
        } catch (e: IOException) {
            throw IOException("Can't reach the server. Check your connection and try again.", e)
        }
    }

    private fun parseSession(json: JSONObject) =
        Session(json.getString("token"), json.getLong("expiresAt"), parseAccount(json.getJSONObject("user")))
}

internal fun parseAccount(json: JSONObject): Account {
    val methods = json.optJSONArray("signInMethods")
    return Account(
        id = json.getString("id"),
        email = json.optString("email"),
        username = json.optString("username"),
        displayName = json.getString("displayName"),
        signInMethods = List(methods?.length() ?: 0) { methods!!.getString(it) },
        createdAt = json.optString("createdAt"),
        phoneNumber = json.optString("phoneNumber"),
        avatarDataUrl = json.optString("avatarDataUrl"),
    )
}

internal fun Account.toJson(): JSONObject = JSONObject()
    .put("id", id).put("username", username).put("email", email).put("displayName", displayName)
    .put("signInMethods", org.json.JSONArray(signInMethods)).put("createdAt", createdAt)
    .put("phoneNumber", phoneNumber)
    .put("avatarDataUrl", avatarDataUrl)

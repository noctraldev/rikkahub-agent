package me.rerere.rikkahub.github

import android.content.Context
import android.util.Base64
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val KEY_ALIAS = "rikkahub.github.token"
private const val PREFS = "github_connector"
private const val TOKEN = "encrypted_token"
private const val IV = "token_iv"
private const val USER = "account_login"
private const val CLIENT_ID = "oauth_client_id"
private val JSON_MEDIA = "application/vnd.github+json".toMediaType()

@Serializable
data class GitHubRepositorySummary(val id: Long, val fullName: String, val description: String? = null, val private: Boolean = false, val defaultBranch: String = "main", val htmlUrl: String? = null)
@Serializable
data class GitHubContent(val name: String, val path: String, val sha: String? = null, val type: String, val downloadUrl: String? = null, val content: String? = null)
@Serializable
data class GitHubDeviceCode(val deviceCode: String, val userCode: String, val verificationUri: String, val expiresInSeconds: Int, val intervalSeconds: Int)
@Serializable
data class GitHubAccount(val login: String, val name: String? = null, val htmlUrl: String? = null)
@Serializable
data class GitHubActionResult(val id: Long, val status: String, val conclusion: String? = null, val htmlUrl: String? = null)

class GitHubAuthException(message: String) : IllegalStateException(message)
class GitHubApiException(val statusCode: Int, message: String) : IllegalStateException(message)

/** GitHub API service with Keystore-backed credentials and explicit mutation methods. */
class GitHubConnector(
    context: Context,
    private val client: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
    @VisibleForTesting private val baseUrl: String = "https://api.github.com",
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun isConnected(): Boolean = prefs.contains(TOKEN)
    fun connectedAccount(): String? = prefs.getString(USER, null)
    fun oauthClientId(): String = prefs.getString(CLIENT_ID, "").orEmpty()
    fun saveOauthClientId(clientId: String) { prefs.edit().putString(CLIENT_ID, clientId.trim()).apply() }
    fun disconnect() = prefs.edit().remove(TOKEN).remove(IV).remove(USER).apply()

    fun saveToken(token: String, accountLogin: String? = null) {
        require(token.isNotBlank())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        prefs.edit().putString(TOKEN, Base64.encodeToString(cipher.doFinal(token.toByteArray()), Base64.NO_WRAP)).putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).apply { if (accountLogin != null) putString(USER, accountLogin) }.apply()
    }

    suspend fun beginDeviceFlow(clientId: String): GitHubDeviceCode {
        require(clientId.isNotBlank())
        val o = raw("/login/device/code", "POST", "client_id=$clientId&scope=repo,read:user,workflow".formBody(), false).jsonObject
        return GitHubDeviceCode(o.s("device_code"), o.s("user_code"), o.s("verification_uri"), o.i("expires_in"), o.i("interval").coerceAtLeast(5))
    }

    suspend fun pollDeviceFlow(clientId: String, device: GitHubDeviceCode): GitHubAccount {
        val deadline = System.currentTimeMillis() + device.expiresInSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            val o = raw("/login/oauth/access_token", "POST", "client_id=$clientId&device_code=${device.deviceCode}&grant_type=urn:ietf:params:oauth:grant-type:device_code".formBody(), false).jsonObject
            val error = o.sOrNull("error")
            if (error == null) { saveToken(o.s("access_token")); val account = currentUser(); saveToken(readToken(), account.login); return account }
            if (error != "authorization_pending" && error != "slow_down") throw GitHubAuthException(o.sOrNull("error_description") ?: error)
            delay((if (error == "slow_down") device.intervalSeconds + 5 else device.intervalSeconds) * 1000L)
        }
        throw GitHubAuthException("GitHub device authorization expired")
    }

    suspend fun currentUser(): GitHubAccount = raw("/user").jsonObject.let { GitHubAccount(it.s("login"), it.sOrNull("name"), it.sOrNull("html_url")) }
    suspend fun repositories(page: Int = 1, perPage: Int = 50): List<GitHubRepositorySummary> = raw("/user/repos?page=$page&per_page=${perPage.coerceIn(1, 100)}").jsonArray.map { it.jsonObject.let { o -> GitHubRepositorySummary(o.l("id"), o.s("full_name"), o.sOrNull("description"), o.b("private"), o.sOrNull("default_branch") ?: "main", o.sOrNull("html_url")) } }
    suspend fun contents(owner: String, repo: String, path: String = "", ref: String? = null): List<GitHubContent> {
        val e = raw("/repos/$owner/$repo/contents/${path.trimStart('/')}${ref?.let { "?ref=$it" } ?: ""}")
        return if (e is JsonArray) e.map { it.jsonObject.toContent() } else listOf(e.jsonObject.toContent())
    }
    suspend fun searchCode(query: String, page: Int = 1): JsonObject = raw("/search/code?q=${java.net.URLEncoder.encode(query, "UTF-8")}&page=$page").jsonObject
    suspend fun actionRuns(owner: String, repo: String, page: Int = 1): List<GitHubActionResult> = raw("/repos/$owner/$repo/actions/runs?page=$page").jsonObject["workflow_runs"]!!.jsonArray.map { it.jsonObject.let { o -> GitHubActionResult(o.l("id"), o.s("status"), o.sOrNull("conclusion"), o.sOrNull("html_url")) } }

    suspend fun createBranch(owner: String, repo: String, branch: String, fromSha: String) { put("/repos/$owner/$repo/git/refs", buildJsonObject { put("ref", "refs/heads/$branch"); put("sha", fromSha) }) }
    suspend fun createOrUpdateFile(owner: String, repo: String, path: String, message: String, contentBase64: String, branch: String, sha: String? = null) = put("/repos/$owner/$repo/contents/${path.trimStart('/')}", buildJsonObject { put("message", message); put("content", contentBase64); put("branch", branch); sha?.let { put("sha", it) } })
    suspend fun createIssue(owner: String, repo: String, title: String, body: String? = null, labels: List<String> = emptyList()) = post("/repos/$owner/$repo/issues", buildJsonObject { put("title", title); body?.let { put("body", it) }; put("labels", JsonArray(labels.map(::JsonPrimitive))) })
    suspend fun createPullRequest(owner: String, repo: String, title: String, head: String, base: String, body: String? = null) = post("/repos/$owner/$repo/pulls", buildJsonObject { put("title", title); put("head", head); put("base", base); body?.let { put("body", it) } })

    private suspend fun post(path: String, body: JsonObject) = raw(path, "POST", body.toString().toRequestBody(JSON_MEDIA), true)
    private suspend fun put(path: String, body: JsonObject) = raw(path, "PUT", body.toString().toRequestBody(JSON_MEDIA), true)
    private suspend fun raw(path: String, method: String = "GET", body: RequestBody? = null, authenticated: Boolean = true): JsonElement {
        val request = Request.Builder().url(baseUrl.trimEnd('/') + path).header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28").method(method, body).apply { if (authenticated) header("Authorization", "Bearer ${readToken()}") }.build()
        client.newCall(request).execute().use { response -> val text = response.body?.string().orEmpty(); if (!response.isSuccessful) throw GitHubApiException(response.code, text.take(500)); return json.parseToJsonElement(text) }
    }
    private fun readToken(): String { val encrypted = prefs.getString(TOKEN, null) ?: throw GitHubAuthException("GitHub is not connected"); val iv = prefs.getString(IV, null) ?: throw GitHubAuthException("GitHub token storage is incomplete"); return Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.DEFAULT))); String(doFinal(Base64.decode(encrypted, Base64.DEFAULT))) } }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
}

private fun String.formBody() = toRequestBody("application/x-www-form-urlencoded".toMediaType())
private fun JsonObject.s(name: String) = this[name]?.jsonPrimitive?.content ?: error("GitHub response missing $name")
private fun JsonObject.sOrNull(name: String) = this[name]?.jsonPrimitive?.contentOrNull
private fun JsonObject.i(name: String) = s(name).toInt()
private fun JsonObject.l(name: String) = s(name).toLong()
private fun JsonObject.b(name: String) = s(name).toBoolean()
private fun JsonObject.toContent() = GitHubContent(s("name"), s("path"), sOrNull("sha"), s("type"), sOrNull("download_url"), sOrNull("content"))

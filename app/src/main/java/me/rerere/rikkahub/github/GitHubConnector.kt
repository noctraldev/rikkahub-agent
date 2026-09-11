package me.rerere.rikkahub.github

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
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
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyStore
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

@Serializable data class GitHubRepositorySummary(val id: Long, val fullName: String, val description: String? = null, val private: Boolean = false, val defaultBranch: String = "main", val htmlUrl: String? = null, val archived: Boolean = false, val fork: Boolean = false)
@Serializable data class GitHubRepositoryMetadata(val id: Long, val name: String, val fullName: String, val owner: String, val description: String? = null, val private: Boolean = false, val defaultBranch: String = "main", val htmlUrl: String? = null, val cloneUrl: String? = null, val language: String? = null, val stars: Int = 0, val forks: Int = 0, val openIssues: Int = 0, val archived: Boolean = false)
@Serializable data class GitHubContent(val name: String, val path: String, val sha: String? = null, val type: String, val downloadUrl: String? = null, val content: String? = null, val size: Long = 0)
@Serializable data class GitHubBranch(val name: String, val sha: String, val protected: Boolean = false)
@Serializable data class GitHubCommit(val sha: String, val message: String, val author: String? = null, val date: String? = null, val htmlUrl: String? = null)
@Serializable data class GitHubIssue(val number: Int, val title: String, val body: String? = null, val state: String, val author: String? = null, val htmlUrl: String? = null, val labels: List<String> = emptyList())
@Serializable data class GitHubPullRequest(val number: Int, val title: String, val body: String? = null, val state: String, val head: String, val base: String, val author: String? = null, val htmlUrl: String? = null, val mergeable: Boolean? = null)
@Serializable data class GitHubReview(val id: Long, val user: String? = null, val state: String, val body: String? = null, val htmlUrl: String? = null)
@Serializable data class GitHubRelease(val id: Long, val tagName: String, val name: String? = null, val body: String? = null, val draft: Boolean = false, val prerelease: Boolean = false, val htmlUrl: String? = null)
@Serializable data class GitHubLabel(val name: String, val color: String? = null, val description: String? = null)
@Serializable data class GitHubMilestone(val number: Int, val title: String, val state: String, val description: String? = null, val openIssues: Int = 0, val closedIssues: Int = 0)
@Serializable data class GitHubActionResult(val id: Long, val name: String? = null, val status: String, val conclusion: String? = null, val branch: String? = null, val htmlUrl: String? = null, val event: String? = null)
@Serializable data class GitHubCheckResult(val id: Long, val name: String, val status: String, val conclusion: String? = null, val detailsUrl: String? = null)
@Serializable data class GitHubDeviceCode(val deviceCode: String, val userCode: String, val verificationUri: String, val expiresInSeconds: Int, val intervalSeconds: Int)
@Serializable data class GitHubAccount(val login: String, val name: String? = null, val htmlUrl: String? = null, val scopes: List<String> = emptyList())

class GitHubAuthException(message: String) : IllegalStateException(message)
class GitHubApiException(val statusCode: Int, message: String) : IllegalStateException(message)

class GitHubConnector(
    context: Context,
    private val client: OkHttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
    @VisibleForTesting private val baseUrl: String = "https://api.github.com",
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun isConnected() = prefs.contains(TOKEN) && prefs.contains(IV)
    fun connectedAccount() = prefs.getString(USER, null)
    fun grantedScopes() = prefs.getStringSet("scopes", emptySet()).orEmpty().toList().sorted()
    fun oauthClientId() = prefs.getString(CLIENT_ID, "").orEmpty()
    fun saveOauthClientId(value: String) { prefs.edit().putString(CLIENT_ID, value.trim()).apply() }
    fun disconnect() { prefs.edit().remove(TOKEN).remove(IV).remove(USER).remove("scopes").apply() }

    fun saveToken(token: String, accountLogin: String? = null, scopes: List<String> = emptyList()) {
        require(token.isNotBlank())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        prefs.edit().putString(TOKEN, Base64.encodeToString(cipher.doFinal(token.toByteArray()), Base64.NO_WRAP))
            .putString(IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).apply {
                if (accountLogin != null) putString(USER, accountLogin)
                if (scopes.isNotEmpty()) putStringSet("scopes", scopes.toSet())
            }.apply()
    }

    suspend fun beginDeviceFlow(clientId: String): GitHubDeviceCode {
        val o = raw("/login/device/code", "POST", "client_id=${q(clientId)}&scope=${q("repo read:user workflow")}".formBody(), false).jsonObject
        return GitHubDeviceCode(o.s("device_code"), o.s("user_code"), o.sOrNull("verification_uri") ?: o.s("verification_uri_complete"), o.i("expires_in"), o.i("interval").coerceAtLeast(5))
    }
    suspend fun pollDeviceFlow(clientId: String, device: GitHubDeviceCode): GitHubAccount {
        val deadline = System.currentTimeMillis() + device.expiresInSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            val o = raw("/login/oauth/access_token", "POST", "client_id=${q(clientId)}&device_code=${q(device.deviceCode)}&grant_type=urn:ietf:params:oauth:grant-type:device_code".formBody(), false).jsonObject
            val error = o.sOrNull("error")
            if (error == null) { saveToken(o.s("access_token")); val account = currentUser(); saveToken(readToken(), account.login, account.scopes); return account }
            if (error != "authorization_pending" && error != "slow_down") throw GitHubAuthException(o.sOrNull("error_description") ?: error)
            delay((if (error == "slow_down") device.intervalSeconds + 5 else device.intervalSeconds) * 1000L)
        }
        throw GitHubAuthException("GitHub device authorization expired")
    }
    suspend fun currentUser(): GitHubAccount { val response = rawResponse("/user"); val o = response.body.jsonObject; return GitHubAccount(o.s("login"), o.sOrNull("name"), o.sOrNull("html_url"), response.scopes).also { if (response.scopes.isNotEmpty()) prefs.edit().putStringSet("scopes", response.scopes.toSet()).apply() } }

    suspend fun repositories(page: Int = 1, perPage: Int = 50) = raw("/user/repos?page=$page&per_page=${perPage.coerceIn(1, 100)}&sort=updated").jsonArray.map { it.jsonObject.toRepository() }
    suspend fun repository(owner: String, repo: String) = raw("/repos/${seg(owner)}/${seg(repo)}").jsonObject.toMetadata()
    suspend fun branches(owner: String, repo: String, page: Int = 1) = raw("/repos/${seg(owner)}/${seg(repo)}/branches?page=$page&per_page=100").jsonArray.map { it.jsonObject.let { o -> GitHubBranch(o.s("name"), o["commit"]!!.jsonObject.s("sha"), o["protected"]?.jsonPrimitive?.booleanOrNull ?: false) } }
    suspend fun contents(owner: String, repo: String, path: String = "", ref: String? = null): List<GitHubContent> { val clean = path.trim('/').split('/').filter(String::isNotBlank).joinToString("/") { seg(it) }; val e = raw("/repos/${seg(owner)}/${seg(repo)}/contents/$clean${ref?.let { "?ref=${q(it)}" } ?: ""}"); return if (e is JsonArray) e.map { it.jsonObject.toContent() } else listOf(e.jsonObject.toContent()) }
    suspend fun commits(owner: String, repo: String, branch: String? = null, page: Int = 1) = raw("/repos/${seg(owner)}/${seg(repo)}/commits?page=$page&per_page=50${branch?.let { "&sha=${q(it)}" } ?: ""}").jsonArray.map { it.jsonObject.toCommit() }
    suspend fun searchCode(query: String, page: Int = 1) = raw("/search/code?q=${q(query)}&page=$page").jsonObject
    suspend fun actionRuns(owner: String, repo: String, page: Int = 1) = raw("/repos/${seg(owner)}/${seg(repo)}/actions/runs?page=$page&per_page=50").jsonObject["workflow_runs"]!!.jsonArray.map { it.jsonObject.toAction() }
    suspend fun checkRuns(owner: String, repo: String, ref: String) = raw("/repos/${seg(owner)}/${seg(repo)}/commits/${seg(ref)}/check-runs").jsonObject["check_runs"]!!.jsonArray.map { it.jsonObject.toCheck() }
    suspend fun issues(owner: String, repo: String, state: String = "open", page: Int = 1) = raw("/repos/${seg(owner)}/${seg(repo)}/issues?state=${q(state)}&page=$page").jsonArray.filterNot { it.jsonObject.containsKey("pull_request") }.map { it.jsonObject.toIssue() }
    suspend fun pullRequests(owner: String, repo: String, state: String = "open", page: Int = 1) = raw("/repos/${seg(owner)}/${seg(repo)}/pulls?state=${q(state)}&page=$page").jsonArray.map { it.jsonObject.toPullRequest() }
    suspend fun pullRequest(owner: String, repo: String, number: Int) = raw("/repos/${seg(owner)}/${seg(repo)}/pulls/$number").jsonObject.toPullRequest()
    suspend fun reviews(owner: String, repo: String, number: Int) = raw("/repos/${seg(owner)}/${seg(repo)}/pulls/$number/reviews").jsonArray.map { it.jsonObject.toReview() }
    suspend fun releases(owner: String, repo: String, page: Int = 1) = raw("/repos/${seg(owner)}/${seg(repo)}/releases?page=$page").jsonArray.map { it.jsonObject.toRelease() }
    suspend fun labels(owner: String, repo: String) = raw("/repos/${seg(owner)}/${seg(repo)}/labels?per_page=100").jsonArray.map { it.jsonObject.toLabel() }
    suspend fun milestones(owner: String, repo: String, state: String = "open") = raw("/repos/${seg(owner)}/${seg(repo)}/milestones?state=${q(state)}").jsonArray.map { it.jsonObject.toMilestone() }
    suspend fun discussions(owner: String, repo: String): JsonElement = graphql("query { repository(owner: ${g(owner)}, name: ${g(repo)}) { discussions(first: 50) { nodes { number title body url category { name } } } } }")

    suspend fun createBranch(owner: String, repo: String, branch: String, fromSha: String) = put("/repos/${seg(owner)}/${seg(repo)}/git/refs", buildJsonObject { put("ref", "refs/heads/$branch"); put("sha", fromSha) })
    suspend fun createOrUpdateFile(owner: String, repo: String, path: String, message: String, contentBase64: String, branch: String, sha: String? = null) = put("/repos/${seg(owner)}/${seg(repo)}/contents/${path.trim('/').split('/').filter(String::isNotBlank).joinToString("/") { seg(it) }}", buildJsonObject { put("message", message); put("content", contentBase64); put("branch", branch); sha?.let { put("sha", it) } })
    suspend fun createCommit(owner: String, repo: String, message: String, treeSha: String, parentSha: String) = post("/repos/${seg(owner)}/${seg(repo)}/git/commits", buildJsonObject { put("message", message); put("tree", treeSha); put("parents", buildJsonArray { add(parentSha) }) })
    suspend fun createIssue(owner: String, repo: String, title: String, body: String? = null, labels: List<String> = emptyList(), milestone: Int? = null) = post("/repos/${seg(owner)}/${seg(repo)}/issues", buildJsonObject { put("title", title); body?.let { put("body", it) }; put("labels", JsonArray(labels.map(::JsonPrimitive))); milestone?.let { put("milestone", it) } })
    suspend fun commentIssue(owner: String, repo: String, number: Int, body: String) = post("/repos/${seg(owner)}/${seg(repo)}/issues/$number/comments", buildJsonObject { put("body", body) })
    suspend fun createPullRequest(owner: String, repo: String, title: String, head: String, base: String, body: String? = null) = post("/repos/${seg(owner)}/${seg(repo)}/pulls", buildJsonObject { put("title", title); put("head", head); put("base", base); body?.let { put("body", it) } })
    suspend fun reviewPullRequest(owner: String, repo: String, number: Int, event: String, body: String? = null) = post("/repos/${seg(owner)}/${seg(repo)}/pulls/$number/reviews", buildJsonObject { put("event", event); body?.let { put("body", it) } })
    suspend fun createRelease(owner: String, repo: String, tag: String, name: String? = null, body: String? = null, draft: Boolean = false, prerelease: Boolean = false) = post("/repos/${seg(owner)}/${seg(repo)}/releases", buildJsonObject { put("tag_name", tag); name?.let { put("name", it) }; body?.let { put("body", it) }; put("draft", draft); put("prerelease", prerelease) })

    private suspend fun post(path: String, body: JsonObject) = raw(path, "POST", body.toString().toRequestBody(JSON_MEDIA))
    private suspend fun put(path: String, body: JsonObject) = raw(path, "PUT", body.toString().toRequestBody(JSON_MEDIA))
    private suspend fun graphql(query: String) = rawGraphql(buildJsonObject { put("query", query) })
    private suspend fun raw(path: String, method: String = "GET", body: RequestBody? = null, authenticated: Boolean = true) = rawResponse(path, method, body, authenticated).body
    private data class Response(val body: JsonElement, val scopes: List<String>)
    private suspend fun rawResponse(path: String, method: String = "GET", body: RequestBody? = null, authenticated: Boolean = true): Response { val request = Request.Builder().url(baseUrl.trimEnd('/') + path).header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28").method(method, body).apply { if (authenticated) header("Authorization", "Bearer ${readToken()}") }.build(); client.newCall(request).execute().use { response -> val text = response.body?.string().orEmpty(); if (!response.isSuccessful) throw GitHubApiException(response.code, text.take(500)); return Response(json.parseToJsonElement(text), response.header("X-OAuth-Scopes")?.split(',')?.map(String::trim).orEmpty()) } }
    private suspend fun rawGraphql(body: JsonObject): JsonElement { val request = Request.Builder().url("https://api.github.com/graphql").header("Authorization", "Bearer ${readToken()}").post(body.toString().toRequestBody(JSON_MEDIA)).build(); client.newCall(request).execute().use { response -> val text = response.body?.string().orEmpty(); if (!response.isSuccessful) throw GitHubApiException(response.code, text.take(500)); return json.parseToJsonElement(text) } }
    private fun readToken(): String { val encrypted = prefs.getString(TOKEN, null) ?: throw GitHubAuthException("GitHub is not connected"); val iv = prefs.getString(IV, null) ?: throw GitHubAuthException("GitHub token storage is incomplete"); return Cipher.getInstance("AES/GCM/NoPadding").run { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.DEFAULT))); String(doFinal(Base64.decode(encrypted, Base64.DEFAULT)), StandardCharsets.UTF_8) } }
    private fun key(): SecretKey { val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }; return (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").apply { init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build()) }.generateKey() }
    private fun seg(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
    private fun q(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)
    private fun g(value: String) = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
}

private fun String.formBody() = toRequestBody("application/x-www-form-urlencoded".toMediaType())
private fun JsonObject.s(name: String) = this[name]?.jsonPrimitive?.content ?: error("GitHub response missing $name")
private fun JsonObject.sOrNull(name: String) = this[name]?.jsonPrimitive?.contentOrNull
private fun JsonObject.i(name: String) = this[name]?.jsonPrimitive?.intOrNull ?: 0
private fun JsonObject.l(name: String) = this[name]?.jsonPrimitive?.longOrNull ?: 0L
private fun JsonObject.toRepository() = GitHubRepositorySummary(l("id"), s("full_name"), sOrNull("description"), this["private"]?.jsonPrimitive?.booleanOrNull ?: false, sOrNull("default_branch") ?: "main", sOrNull("html_url"), this["archived"]?.jsonPrimitive?.booleanOrNull ?: false, this["fork"]?.jsonPrimitive?.booleanOrNull ?: false)
private fun JsonObject.toMetadata() = GitHubRepositoryMetadata(l("id"), s("name"), s("full_name"), this["owner"]!!.jsonObject.s("login"), sOrNull("description"), this["private"]?.jsonPrimitive?.booleanOrNull ?: false, sOrNull("default_branch") ?: "main", sOrNull("html_url"), sOrNull("clone_url"), sOrNull("language"), i("stargazers_count"), i("forks_count"), i("open_issues_count"), this["archived"]?.jsonPrimitive?.booleanOrNull ?: false)
private fun JsonObject.toContent() = GitHubContent(s("name"), s("path"), sOrNull("sha"), s("type"), sOrNull("download_url"), sOrNull("content"), l("size"))
private fun JsonObject.toCommit() = GitHubCommit(s("sha"), this["commit"]!!.jsonObject.s("message"), this["commit"]!!.jsonObject["author"]?.jsonObject?.sOrNull("name"), this["commit"]!!.jsonObject["author"]?.jsonObject?.sOrNull("date"), sOrNull("html_url"))
private fun JsonObject.toIssue() = GitHubIssue(i("number"), s("title"), sOrNull("body"), s("state"), this["user"]?.jsonObject?.sOrNull("login"), sOrNull("html_url"), this["labels"]?.jsonArray?.mapNotNull { it.jsonObject.sOrNull("name") } ?: emptyList())
private fun JsonObject.toPullRequest() = GitHubPullRequest(i("number"), s("title"), sOrNull("body"), s("state"), this["head"]!!.jsonObject.s("ref"), this["base"]!!.jsonObject.s("ref"), this["user"]?.jsonObject?.sOrNull("login"), sOrNull("html_url"), this["mergeable"]?.jsonPrimitive?.booleanOrNull)
private fun JsonObject.toReview() = GitHubReview(l("id"), this["user"]?.jsonObject?.sOrNull("login"), s("state"), sOrNull("body"), sOrNull("html_url"))
private fun JsonObject.toRelease() = GitHubRelease(l("id"), s("tag_name"), sOrNull("name"), sOrNull("body"), this["draft"]?.jsonPrimitive?.booleanOrNull ?: false, this["prerelease"]?.jsonPrimitive?.booleanOrNull ?: false, sOrNull("html_url"))
private fun JsonObject.toLabel() = GitHubLabel(s("name"), sOrNull("color"), sOrNull("description"))
private fun JsonObject.toMilestone() = GitHubMilestone(i("number"), s("title"), s("state"), sOrNull("description"), i("open_issues"), i("closed_issues"))
private fun JsonObject.toAction() = GitHubActionResult(l("id"), sOrNull("name"), s("status"), sOrNull("conclusion"), sOrNull("head_branch"), sOrNull("html_url"), sOrNull("event"))
private fun JsonObject.toCheck() = GitHubCheckResult(l("id"), s("name"), s("status"), sOrNull("conclusion"), sOrNull("details_url"))
private fun JsonObject.sOrNull(name: String, nested: String) = this[name]?.jsonObject?.sOrNull(nested)

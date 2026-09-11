package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.json.*
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.github.GitHubConnector

private fun result(value: JsonElement) = listOf<UIMessagePart>(UIMessagePart.Text(value.toString()))
private fun error(t: Throwable) = result(buildJsonObject { put("error", "github_request_failed"); put("detail", t.message ?: t::class.simpleName.orEmpty()) })
private fun str(o: JsonObject, key: String) = o[key]?.jsonPrimitive?.contentOrNull
private fun prop(type: String) = buildJsonObject { put("type", type) }
private fun schema(vararg required: String, properties: JsonObject) = InputSchema.Obj(properties, required.toList())

fun githubTools(connector: GitHubConnector): List<Tool> = listOf(
    Tool(name = "github_status", description = "Return whether GitHub is connected and the authorized account.", parameters = { schema(properties = buildJsonObject {}) }, execute = { result(buildJsonObject { put("connected", connector.isConnected()); connector.connectedAccount()?.let { put("account", it) } }) }),
    Tool(name = "github_list_repositories", description = "List repositories visible to the connected GitHub account.", parameters = { schema(properties = buildJsonObject { put("page", prop("integer")) }) }, execute = { runCatching { result(Json.encodeToJsonElement(connector.repositories(it.jsonObject["page"]?.jsonPrimitive?.intOrNull ?: 1))) }.getOrElse(::error) }),
    Tool(name = "github_read_file", description = "Read a file or directory from a GitHub repository.", parameters = { schema("owner", "repo", "path", properties = buildJsonObject { put("owner", prop("string")); put("repo", prop("string")); put("path", prop("string")); put("ref", prop("string")) }) }, execute = { val o = it.jsonObject; runCatching { result(Json.encodeToJsonElement(connector.contents(str(o, "owner")!!, str(o, "repo")!!, str(o, "path")!!, str(o, "ref")))) }.getOrElse(::error) }),
    Tool(name = "github_search_code", description = "Search code in repositories accessible to the connected account.", parameters = { schema("query", properties = buildJsonObject { put("query", prop("string")) }) }, execute = { runCatching { result(connector.searchCode(str(it.jsonObject, "query")!!)) }.getOrElse(::error) }),
    Tool(name = "github_actions_status", description = "Read recent GitHub Actions workflow runs for a repository.", parameters = { schema("owner", "repo", properties = buildJsonObject { put("owner", prop("string")); put("repo", prop("string")) }) }, execute = { val o = it.jsonObject; runCatching { result(Json.encodeToJsonElement(connector.actionRuns(str(o, "owner")!!, str(o, "repo")!!))) }.getOrElse(::error) }),
    Tool(name = "github_create_branch", description = "Create a branch. Remote changes always require user approval.", parameters = { schema("owner", "repo", "branch", "from_sha", properties = buildJsonObject { put("owner", prop("string")); put("repo", prop("string")); put("branch", prop("string")); put("from_sha", prop("string")) }) }, needsApproval = { true }, execute = { val o = it.jsonObject; runCatching { connector.createBranch(str(o, "owner")!!, str(o, "repo")!!, str(o, "branch")!!, str(o, "from_sha")!!); result(buildJsonObject { put("ok", true) }) }.getOrElse(::error) }),
    Tool(name = "github_create_pull_request", description = "Create a pull request. Remote changes always require user approval.", parameters = { schema("owner", "repo", "title", "head", "base", properties = buildJsonObject { put("owner", prop("string")); put("repo", prop("string")); put("title", prop("string")); put("head", prop("string")); put("base", prop("string")); put("body", prop("string")) }) }, needsApproval = { true }, execute = { val o = it.jsonObject; runCatching { result(connector.createPullRequest(str(o, "owner")!!, str(o, "repo")!!, str(o, "title")!!, str(o, "head")!!, str(o, "base")!!, str(o, "body"))) }.getOrElse(::error) }),
)

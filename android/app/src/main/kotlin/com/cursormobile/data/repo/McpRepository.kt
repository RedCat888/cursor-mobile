package com.cursormobile.data.repo

import com.cursormobile.data.net.McpListOk
import com.cursormobile.data.net.McpMutationOk
import com.cursormobile.data.net.McpServer
import com.cursormobile.data.net.MessageTypes
import com.cursormobile.data.net.RemoteClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

@Singleton
class McpRepository @Inject constructor(private val client: RemoteClient) {
    private val json get() = client.json

    suspend fun list(): McpListOk {
        val env = client.request(MessageTypes.MCP_LIST, buildJsonObject {}, MessageTypes.MCP_LIST_OK, timeoutMs = 60_000)
        return json.decodeFromJsonElement(McpListOk.serializer(), env.body!!)
    }

    suspend fun upsert(scope: String, projectPath: String?, server: McpServer): List<McpServer> {
        val env = client.request(
            MessageTypes.MCP_UPSERT,
            buildJsonObject {
                put("scope", scope)
                if (projectPath != null) put("projectPath", projectPath)
                put("server", json.encodeToJsonElement(McpServer.serializer(), server))
            },
            MessageTypes.MCP_UPSERT_OK,
        )
        return json.decodeFromJsonElement(McpMutationOk.serializer(), env.body!!).servers
    }

    suspend fun delete(scope: String, projectPath: String?, name: String): List<McpServer> {
        val env = client.request(
            MessageTypes.MCP_DELETE,
            buildJsonObject {
                put("scope", scope)
                if (projectPath != null) put("projectPath", projectPath)
                put("name", name)
            },
            MessageTypes.MCP_DELETE_OK,
        )
        return json.decodeFromJsonElement(McpMutationOk.serializer(), env.body!!).servers
    }

    suspend fun toggle(scope: String, projectPath: String?, name: String, enabled: Boolean): List<McpServer> {
        val env = client.request(
            MessageTypes.MCP_TOGGLE,
            buildJsonObject {
                put("scope", scope)
                if (projectPath != null) put("projectPath", projectPath)
                put("name", name); put("enabled", enabled)
            },
            MessageTypes.MCP_TOGGLE_OK,
        )
        return json.decodeFromJsonElement(McpMutationOk.serializer(), env.body!!).servers
    }
}

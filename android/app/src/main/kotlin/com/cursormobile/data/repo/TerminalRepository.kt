package com.cursormobile.data.repo

import android.util.Base64
import com.cursormobile.data.net.MessageTypes
import com.cursormobile.data.net.PtyExitBody
import com.cursormobile.data.net.PtyListOk
import com.cursormobile.data.net.PtyOpenedBody
import com.cursormobile.data.net.PtyStdoutBody
import com.cursormobile.data.net.RemoteClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Singleton
class TerminalRepository @Inject constructor(private val client: RemoteClient) {
    private val json get() = client.json

    suspend fun openTerminal(cwd: String?, cols: Int, rows: Int): PtyOpenedBody {
        val env = client.request(
            MessageTypes.PTY_OPEN,
            buildJsonObject {
                if (cwd != null) put("cwd", cwd)
                put("cols", cols); put("rows", rows)
            },
            MessageTypes.PTY_OPENED,
        )
        return json.decodeFromJsonElement(PtyOpenedBody.serializer(), env.body!!)
    }

    suspend fun listTerminals(): PtyListOk {
        val env = client.request(MessageTypes.PTY_LIST, buildJsonObject {}, MessageTypes.PTY_LIST_OK)
        return json.decodeFromJsonElement(PtyListOk.serializer(), env.body!!)
    }

    suspend fun writeStdin(ptyId: String, bytes: ByteArray) {
        client.send(MessageTypes.PTY_STDIN, buildJsonObject {
            put("ptyId", ptyId)
            put("dataB64", Base64.encodeToString(bytes, Base64.NO_WRAP))
        })
    }

    suspend fun resize(ptyId: String, cols: Int, rows: Int) {
        client.send(MessageTypes.PTY_RESIZE, buildJsonObject {
            put("ptyId", ptyId); put("cols", cols); put("rows", rows)
        })
    }

    suspend fun close(ptyId: String) {
        client.send(MessageTypes.PTY_CLOSE, buildJsonObject { put("ptyId", ptyId) })
    }

    suspend fun attach(ptyId: String, sinceSeq: Long) {
        client.send(MessageTypes.PTY_ATTACH, buildJsonObject {
            put("ptyId", ptyId); put("sinceSeq", sinceSeq)
        })
    }

    fun stdoutFor(ptyId: String): Flow<ByteArray> {
        return client.incoming
            .filter { it.type == MessageTypes.PTY_STDOUT }
            .map { json.decodeFromJsonElement(PtyStdoutBody.serializer(), it.body!!) }
            .filter { it.ptyId == ptyId }
            .map { Base64.decode(it.dataB64, Base64.NO_WRAP) }
    }

    fun exits(): Flow<PtyExitBody> {
        return client.incoming
            .filter { it.type == MessageTypes.PTY_EXIT }
            .map { json.decodeFromJsonElement(PtyExitBody.serializer(), it.body!!) }
    }
}

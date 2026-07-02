package com.cursormobile.data.repo

import android.util.Base64
import com.cursormobile.data.net.FsListOk
import com.cursormobile.data.net.FsReadOk
import com.cursormobile.data.net.FsWorkspacesOk
import com.cursormobile.data.net.FsWriteOk
import com.cursormobile.data.net.MessageTypes
import com.cursormobile.data.net.RemoteClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Singleton
class FilesRepository @Inject constructor(private val client: RemoteClient) {
    private val json get() = client.json

    suspend fun list(path: String): FsListOk {
        android.util.Log.i("CursorMobile.Files", "list begin path=$path")
        val env = try {
            client.request(
                MessageTypes.FS_LIST,
                buildJsonObject { put("path", path) },
                MessageTypes.FS_LIST_OK,
                timeoutMs = 30_000,
            )
        } catch (t: Throwable) {
            android.util.Log.e("CursorMobile.Files", "list request threw: ${t.message}")
            throw t
        }
        val ok = try {
            json.decodeFromJsonElement(FsListOk.serializer(), env.body!!)
        } catch (t: Throwable) {
            android.util.Log.e("CursorMobile.Files", "decode failed: ${t.message}, body=${env.body.toString().take(500)}")
            throw t
        }
        android.util.Log.i("CursorMobile.Files", "list($path) -> ${ok.entries.size} entries first=${ok.entries.firstOrNull()?.name}")
        return ok
    }

    suspend fun read(path: String, maxBytes: Int? = null): Pair<FsReadOk, String> {
        val env = client.request(
            MessageTypes.FS_READ,
            buildJsonObject {
                put("path", path)
                if (maxBytes != null) put("maxBytes", maxBytes)
            },
            MessageTypes.FS_READ_OK,
        )
        val ok = json.decodeFromJsonElement(FsReadOk.serializer(), env.body!!)
        val text = if (ok.encoding == "utf8")
            Base64.decode(ok.contentB64, Base64.NO_WRAP).toString(Charsets.UTF_8)
        else "<binary ${ok.contentB64.length} chars base64>"
        return ok to text
    }

    suspend fun write(path: String, text: String): FsWriteOk {
        val b64 = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val env = client.request(
            MessageTypes.FS_WRITE,
            buildJsonObject { put("path", path); put("contentB64", b64) },
            MessageTypes.FS_WRITE_OK,
        )
        return json.decodeFromJsonElement(FsWriteOk.serializer(), env.body!!)
    }

    suspend fun openInIde(path: String) {
        client.send(MessageTypes.FS_OPEN_IN_IDE, buildJsonObject { put("path", path) })
    }

    suspend fun workspaces(): FsWorkspacesOk {
        val env = client.request(
            MessageTypes.FS_WORKSPACES,
            buildJsonObject {},
            MessageTypes.FS_WORKSPACES_OK,
            timeoutMs = 30_000,
        )
        return json.decodeFromJsonElement(FsWorkspacesOk.serializer(), env.body!!)
    }
}

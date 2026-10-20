package com.mynok.myagent.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mynok.myagent.tools.android.SafFileWorkspace
import kotlinx.coroutines.flow.first

private val Context.workspaceStore by preferencesDataStore(name = "myagent_workspace")

/**
 * 已授权目录的持久化。
 *
 * SAF 授权的 tree URI 需要 `takePersistableUriPermission` 才能在重启后继续使用，
 * 这里把 URI 列表存进 DataStore。
 */
class WorkspaceStore(private val context: Context) {

    suspend fun load(workspace: SafFileWorkspace) {
        val raw = context.workspaceStore.data.first()[KEY_ROOTS] ?: return
        val uris = raw.split('\n').mapNotNull { it.trim().takeIf(String::isNotEmpty) }
            .mapNotNull { runCatching { Uri.parse(it) }.getOrNull() }
        workspace.setRoots(
            uris.mapIndexed { index, uri ->
                SafFileWorkspace.root(context, "r${index + 1}", uri)
            },
        )
    }

    /** 持久化新的授权列表（用户可能撤销了某个目录）。 */
    suspend fun save(uris: List<Uri>) {
        persistPermissions(uris)
        context.workspaceStore.edit {
            it[KEY_ROOTS] = uris.joinToString("\n") { it.toString() }
        }
    }

    private fun persistPermissions(uris: List<Uri>) {
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
    }

    companion object {
        private val KEY_ROOTS = stringPreferencesKey("workspace_roots")
    }
}

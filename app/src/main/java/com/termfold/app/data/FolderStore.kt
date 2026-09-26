package com.termfold.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.termfold.app.core.AppData
import com.termfold.app.core.Folder
import com.termfold.app.core.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "termfold")

class FolderStore(private val context: Context) {

    private val key = stringPreferencesKey("folders_json")

    val data: Flow<AppData> = context.dataStore.data.map { preferences ->
        decode(preferences[key])
    }

    suspend fun update(transform: (AppData) -> AppData) {
        context.dataStore.edit { preferences ->
            val next = transform(decode(preferences[key]))
            preferences[key] = encode(next)
        }
    }

    private fun decode(raw: String?): AppData {
        if (raw.isNullOrBlank()) return AppData()
        return runCatching {
            val root = JSONObject(raw)
            val folders = root.optJSONArray("folders") ?: JSONArray()
            AppData(
                folders = List(folders.length()) { index ->
                    folders.getJSONObject(index).toFolder()
                }
            )
        }.getOrElse { AppData() }
    }

    private fun encode(data: AppData): String {
        val folders = JSONArray()
        data.folders.forEach { folders.put(it.toJson()) }
        return JSONObject().put("folders", folders).toString()
    }

    private fun JSONObject.toFolder(): Folder {
        val sessions = optJSONArray("sessions") ?: JSONArray()
        return Folder(
            id = getString("id"),
            name = getString("name"),
            path = optString("path"),
            tint = optInt("tint"),
            sessions = List(sessions.length()) { index ->
                val session = sessions.getJSONObject(index)
                Session(
                    id = session.getString("id"),
                    name = session.getString("name"),
                    command = session.optString("command"),
                    tint = session.optInt("tint"),
                    acpAgentId = session.optString("acpAgentId"),
                )
            },
        )
    }

    private fun Folder.toJson(): JSONObject {
        val sessionArray = JSONArray()
        sessions.forEach { session ->
            sessionArray.put(
                JSONObject()
                    .put("id", session.id)
                    .put("name", session.name)
                    .put("command", session.command)
                    .put("tint", session.tint)
                    .put("acpAgentId", session.acpAgentId)
            )
        }
        return JSONObject()
            .put("id", id)
            .put("name", name)
            .put("path", path)
            .put("tint", tint)
            .put("sessions", sessionArray)
    }
}

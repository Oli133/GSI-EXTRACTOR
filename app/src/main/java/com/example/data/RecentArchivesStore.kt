package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.archive.core.RecentArchiveRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.recentArchivesDataStore: DataStore<Preferences> by
  preferencesDataStore(name = "recent_archives")

class RecentArchivesStore(private val context: Context) {
  private val keyRecentList = stringPreferencesKey("recent_archives_json")

  val recentArchives: Flow<List<RecentArchiveRecord>> =
    context.recentArchivesDataStore.data.map { prefs ->
      parseJson(prefs[keyRecentList].orEmpty())
    }

  suspend fun recordOpened(record: RecentArchiveRecord) {
    context.recentArchivesDataStore.edit { prefs ->
      val current = parseJson(prefs[keyRecentList].orEmpty()).toMutableList()
      current.removeAll { it.uriString == record.uriString }
      current.add(0, record)
      val trimmed = current.take(12)
      prefs[keyRecentList] = serializeJson(trimmed)
    }
  }

  suspend fun removeRecord(uriString: String) {
    context.recentArchivesDataStore.edit { prefs ->
      val current = parseJson(prefs[keyRecentList].orEmpty()).filterNot { it.uriString == uriString }
      prefs[keyRecentList] = serializeJson(current)
    }
  }

  private fun parseJson(raw: String): List<RecentArchiveRecord> {
    if (raw.isBlank()) return emptyList()
    return runCatching {
      val arr = JSONArray(raw)
      buildList {
        for (i in 0 until arr.length()) {
          val obj = arr.getJSONObject(i)
          add(
            RecentArchiveRecord(
              uriString = obj.getString("uri"),
              displayName = obj.getString("name"),
              formatLabel = obj.getString("format"),
              sizeBytes = obj.getLong("size"),
              openedAtMillis = obj.getLong("openedAt"),
            )
          )
        }
      }
    }.getOrDefault(emptyList())
  }

  private fun serializeJson(items: List<RecentArchiveRecord>): String {
    val arr = JSONArray()
    for (item in items) {
      arr.put(
        JSONObject().apply {
          put("uri", item.uriString)
          put("name", item.displayName)
          put("format", item.formatLabel)
          put("size", item.sizeBytes)
          put("openedAt", item.openedAtMillis)
        }
      )
    }
    return arr.toString()
  }
}

package com.rohan.livedash.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "livedash_prefs")

object SessionStore {
    private val KEY_SENDER_NAME = stringPreferencesKey("sender_name")
    private val KEY_LAST_IP = stringPreferencesKey("last_ip")
    private val KEY_LAST_PORT = intPreferencesKey("last_port")
    private val KEY_SESSION_ID = stringPreferencesKey("session_id")

    fun senderName(ctx: Context): Flow<String> =
        ctx.dataStore.data.map { it[KEY_SENDER_NAME] ?: "" }

    fun lastIp(ctx: Context): Flow<String> =
        ctx.dataStore.data.map { it[KEY_LAST_IP] ?: "192.168.43.1" }

    fun lastPort(ctx: Context): Flow<Int> =
        ctx.dataStore.data.map { it[KEY_LAST_PORT] ?: 8765 }

    suspend fun saveSenderName(ctx: Context, name: String) {
        ctx.dataStore.edit { it[KEY_SENDER_NAME] = name }
    }

    suspend fun saveConnection(ctx: Context, ip: String, port: Int, sessionId: String) {
        ctx.dataStore.edit {
            it[KEY_LAST_IP] = ip
            it[KEY_LAST_PORT] = port
            it[KEY_SESSION_ID] = sessionId
        }
    }

    suspend fun clearSession(ctx: Context) {
        ctx.dataStore.edit { it.remove(KEY_SESSION_ID) }
    }
}

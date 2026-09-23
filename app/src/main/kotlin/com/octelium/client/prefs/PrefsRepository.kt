package com.octelium.client.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.octelium.client.core.prefs.Prefs
import com.octelium.client.core.prefs.ThemeMode
import com.octelium.client.core.prefs.normalizePrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.prefsDataStore by preferencesDataStore(name = "prefs")

class PrefsRepository(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.prefsDataStore)

    val prefs: Flow<Prefs> = dataStore.data
        .catch { err ->
            if (err !is IOException) {
                throw err
            }
            emit(emptyPreferences())
        }
        .map { itm ->
            normalizePrefs(itm[THEME], itm[PRIMARY_DOMAIN], itm[MULTI_CLUSTER])
        }

    val hasRequestedNotifications: Flow<Boolean> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[HAS_REQUESTED_NOTIFICATIONS] ?: false }

    suspend fun get(): Prefs = prefs.first()

    suspend fun setHasRequestedNotifications() = edit {
        it[HAS_REQUESTED_NOTIFICATIONS] = true
    }

    suspend fun setTheme(arg: ThemeMode) = edit {
        it[THEME] = arg.key
    }

    suspend fun setPrimaryDomain(arg: String?) = edit {
        if (arg.isNullOrEmpty()) {
            it.remove(PRIMARY_DOMAIN)
        } else {
            it[PRIMARY_DOMAIN] = arg
        }
    }

    suspend fun setMultiCluster(arg: Boolean) = edit {
        it[MULTI_CLUSTER] = arg
    }

    private suspend fun edit(fn: (MutablePreferences) -> Unit) {
        dataStore.edit(fn)
    }

    companion object {
        private val THEME = stringPreferencesKey("theme")
        private val PRIMARY_DOMAIN = stringPreferencesKey("primaryDomain")
        private val MULTI_CLUSTER = booleanPreferencesKey("multiCluster")
        private val HAS_REQUESTED_NOTIFICATIONS = booleanPreferencesKey("hasRequestedNotifications")
    }
}

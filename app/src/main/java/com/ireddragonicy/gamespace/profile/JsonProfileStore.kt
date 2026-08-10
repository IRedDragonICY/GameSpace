package com.ireddragonicy.gamespace.profile

import android.content.Context
import android.os.UserHandle
import android.provider.Settings
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ireddragonicy.gamespace.data.SettingsIO
import java.lang.reflect.Type

/** Generic JSON array profile storage over Settings.System or SettingsIO. */
class JsonProfileStore<T : NamedProfile>(
    private val context: Context,
    private val settingsKey: String,
    private val itemType: Type,
    private val gson: Gson = Gson(),
    private val io: SettingsIO = SettingsIO(context),
) {
    fun load(): List<T> {
        val json = io.read(settingsKey)
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val list: List<T> = gson.fromJson(json, itemType)
            list.filterNotNull()
        }.getOrDefault(emptyList())
    }

    fun save(items: List<T>) {
        val json = gson.toJson(items)
        io.write(settingsKey, json)
    }
}

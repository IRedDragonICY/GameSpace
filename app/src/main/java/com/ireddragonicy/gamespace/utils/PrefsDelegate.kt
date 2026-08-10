package com.ireddragonicy.gamespace.utils

import android.content.SharedPreferences
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

class BooleanPref(
    private val db: SharedPreferences,
    private val key: String,
    private val default: Boolean
) : ReadWriteProperty<Any?, Boolean> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean =
        db.getBoolean(key, default)

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
        db.edit().putBoolean(key, value).apply()
    }
}

class IntPref(
    private val db: SharedPreferences,
    private val key: String,
    private val default: Int
) : ReadWriteProperty<Any?, Int> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): Int =
        db.getInt(key, default)

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
        db.edit().putInt(key, value).apply()
    }
}

class StringPref(
    private val db: SharedPreferences,
    private val key: String,
    private val default: String
) : ReadWriteProperty<Any?, String> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): String =
        db.getString(key, default) ?: default

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
        db.edit().putString(key, value).apply()
    }
}

class IntClampedPref(
    private val db: SharedPreferences,
    private val key: String,
    private val default: Int,
    private val range: IntRange
) : ReadWriteProperty<Any?, Int> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): Int =
        db.getInt(key, default).coerceIn(range.first, range.last)

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
        db.edit().putInt(key, value.coerceIn(range.first, range.last)).apply()
    }
}

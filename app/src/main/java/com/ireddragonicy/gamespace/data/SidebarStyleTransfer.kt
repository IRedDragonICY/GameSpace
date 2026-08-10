/*
* Copyright (C) 2026 IRedDragonICY
* SPDX-License-Identifier: Apache-2.0
*/
package com.ireddragonicy.gamespace.data

import android.content.Context
import android.content.Intent
import org.json.JSONObject

/**
 * Import/export style sebagai JSON — pola yang sama dengan
 * [com.ireddragonicy.gamespace.data.fpsstats.FpsStatsRepository.exportSession]:
 * serialize ke string, share lewat ACTION_SEND. Bedanya di sini payload kecil
 * (satu objek), jadi tidak perlu file — langsung EXTRA_TEXT.
 */
object SidebarStyleTransfer {

    private const val MIME = "application/json"
    private const val SCHEMA = "gamespace.sidebar.style"
    private const val VERSION = 2

    /** Bungkus dalam envelope ber-schema supaya import bisa menolak file asing. */
    fun export(style: SidebarStyle): String = JSONObject().apply {
        put("schema", SCHEMA)
        put("version", VERSION)
        put("style", style.toJson())
    }.toString(2)

    /** Parse hasil export; null kalau bukan style GameSpace yang valid. */
    fun import(json: String): SidebarStyle? = runCatching {
        val o = JSONObject(json)
        if (o.optString("schema") != SCHEMA) return null
        SidebarStyle.fromJson(o.optJSONObject("style"))
    }.getOrNull()

    /** Share sheet sistem. */
    fun share(context: Context, style: SidebarStyle) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME
            putExtra(Intent.EXTRA_TEXT, export(style))
            putExtra(Intent.EXTRA_SUBJECT, "GameSpace Sidebar Style")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(send, "Share sidebar style")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

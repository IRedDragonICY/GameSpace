/*
* Copyright (C) 2026 IRedDragonICY
* SPDX-License-Identifier: Apache-2.0
*/
package com.ireddragonicy.gamespace.data

import com.ireddragonicy.gamespace.R

/**
 * Registry tombol-tombol non-aplikasi yang bisa muncul di strip.
 *
 * Studio membaca registry ini untuk membangun daftar toggle "tombol mana yang
 * tampil", dan strip asli (VerticalAppSidebar) membaca [SidebarStyle.visibleButtons]
 * untuk memutuskan render. Menambah tombol baru = tambah satu entry di sini;
 * UI studio & strip otomatis mengenalinya.
 */
data class StripButtonSpec(
    val id: String,
    val label: String,
    val iconRes: Int,
    /** Urutan render default di strip (atas→bawah / kiri→kanan). */
    val order: Int,
) {
    companion object {
        const val ID_TIMER = "timer"
        const val ID_PING = "ping"
        const val ID_RECORD = "record"
        const val ID_BUBBLE = "bubble"
        const val ID_ADD = "add"

        val ALL: List<StripButtonSpec> = listOf(
            StripButtonSpec(ID_TIMER, "Session timer",
                R.drawable.materialsymbols_ic_timer_rounded_filled, 0),
            StripButtonSpec(ID_PING, "Network ping",
                R.drawable.materialsymbols_ic_network_check_rounded_filled, 1),
            StripButtonSpec(ID_RECORD, "Screen record",
                R.drawable.materialsymbols_ic_videocam_rounded_filled, 2),
            StripButtonSpec(ID_BUBBLE, "Bubble / Freeform",
                R.drawable.materialsymbols_ic_bubble_rounded_filled, 3),
            StripButtonSpec(ID_ADD, "Add app",
                R.drawable.materialsymbols_ic_add_rounded_filled, 4),
        )

        val DEFAULT_VISIBLE: Set<String> =
            setOf(ID_TIMER, ID_PING, ID_RECORD, ID_BUBBLE, ID_ADD)

        fun byId(id: String): StripButtonSpec? = ALL.firstOrNull { it.id == id }

        /** Tombol yang tampil, terurut — dipakai strip asli & preview. */
        fun visible(style: SidebarStyle): List<StripButtonSpec> =
            ALL.filter { it.id in style.visibleButtons }.sortedBy { it.order }
    }
}

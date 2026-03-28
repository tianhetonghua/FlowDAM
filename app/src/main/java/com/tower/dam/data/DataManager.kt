package com.tower.dam.data

import android.content.Context
import android.graphics.drawable.Drawable

data class AppItem(
    val name: String,
    val packageName: String,
    val icon: Drawable,
    val uid: Int,
    val flags: Int,
    var isSelected: Boolean
)

object DataManager {
    private const val PREFS = "dam_prefs"
    fun saveUids(context: Context, uids: Set<Int>) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet("uids", uids.map { it.toString() }.toSet()).apply()

    fun getUids(context: Context): Set<Int> = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getStringSet("uids", emptySet())?.map { it.toInt() }?.toSet() ?: emptySet()
}
package com.focusguardpro.app.calisthenics

import android.content.Context

object CalisthenicsPrefs {

    private const val FILE = "calisthenics_prefs"
    private const val KEY_FAVORITES = "favorites"
    private const val KEY_RECENTS = "recents"
    private const val KEY_REST = "rest_seconds"
    private const val MAX_RECENTS = 12

    val REST_OPTIONS = listOf(30, 45, 60, 90, 120)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun favorites(context: Context): Set<String> =
        HashSet(prefs(context).getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet())

    fun isFavorite(context: Context, id: String): Boolean = favorites(context).contains(id)

    fun toggleFavorite(context: Context, id: String): Boolean {
        val set: MutableSet<String> = HashSet(favorites(context))
        val isFav = set.contains(id)
        if (isFav) {
            set.remove(id)
        } else {
            set.add(id)
        }
        prefs(context).edit().putStringSet(KEY_FAVORITES, set).apply()
        return !isFav
    }

    fun recents(context: Context): List<String> =
        (prefs(context).getString(KEY_RECENTS, "") ?: "").split(",").filter { it.isNotBlank() }

    fun pushRecent(context: Context, id: String) {
        val list = recents(context).toMutableList()
        list.remove(id)
        list.add(0, id)
        val trimmed = list.take(MAX_RECENTS)
        prefs(context).edit().putString(KEY_RECENTS, trimmed.joinToString(",")).apply()
    }

    fun restSeconds(context: Context): Int =
        prefs(context).getInt(KEY_REST, 60).let { if (it in REST_OPTIONS) it else 60 }

    fun setRestSeconds(context: Context, seconds: Int) {
        prefs(context).edit().putInt(KEY_REST, seconds).apply()
    }
}

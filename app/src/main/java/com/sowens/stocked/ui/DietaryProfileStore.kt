package com.sowens.stocked.ui

import android.content.Context

/** Device-local dietary profile (iOS DietaryProfileView); never uploaded or shared with the household. */
object DietaryProfileStore {
    data class Profile(val diet: String = "", val allergens: Set<String> = emptySet())
    private const val PREFS = "stocked-dietary-profile"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun load(context: Context): Profile { val prefs = prefs(context); return Profile(prefs.getString("diet", "").orEmpty(), prefs.getStringSet("allergens", emptySet()).orEmpty().toSet()) }
    fun save(context: Context, profile: Profile) {
        val allergens = profile.allergens.map { it.trim().take(60) }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.take(40).toSet()
        check(prefs(context).edit().putString("diet", profile.diet).putStringSet("allergens", allergens).commit()) { "Dietary profile could not be saved." }
    }
}

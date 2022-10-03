package co.daily.core.dailydemo

import android.content.Context

class Preferences constructor(ctx: Context) {

    companion object {
        private val LAST_URL = "pref_last_url"
    }

    private val prefs = ctx.applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    private fun getString(key: String): String? {
        return prefs.getString(key, null)
    }

    private fun setString(key: String, value: String?) {
        return prefs.edit().putString(key, value).apply()
    }

    var lastUrl: String?
        get() = getString(LAST_URL)
        set(value) = setString(LAST_URL, value)
}

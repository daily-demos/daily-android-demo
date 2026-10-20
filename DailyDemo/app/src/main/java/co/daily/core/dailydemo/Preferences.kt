package co.daily.core.dailydemo

import android.content.Context

class Preferences constructor(ctx: Context) {

    companion object {
        private val LAST_URL = "pref_last_url"
        private val LAST_USERNAME = "pref_last_username"
        private val PREFERRED_CODEC = "pref_preferred_codec"
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

    var lastUsername: String?
        get() = getString(LAST_USERNAME)
        set(value) = setString(LAST_USERNAME, value)

    // The codec the camera is sent with, as a `CodecNameUpdate` name.
    var preferredCodec: String?
        get() = getString(PREFERRED_CODEC)
        set(value) = setString(PREFERRED_CODEC, value)
}

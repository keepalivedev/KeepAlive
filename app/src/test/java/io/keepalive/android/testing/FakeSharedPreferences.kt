package io.keepalive.android.testing

import android.content.SharedPreferences

/**
 * In-memory SharedPreferences for pure-JVM tests.
 *
 * Doesn't support listeners (KeepAlive's code never registers any). commit()
 * and apply() both write synchronously to the backing map.
 */
class FakeSharedPreferences : SharedPreferences {

    private val data = mutableMapOf<String, Any?>()

    @Suppress("UNCHECKED_CAST")
    override fun getAll(): MutableMap<String, *> = HashMap(data) as MutableMap<String, *>

    override fun getString(key: String?, defValue: String?): String? {
        val v = data[key] ?: return defValue
        return v as? String ?: defValue
    }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        val v = data[key] ?: return defValues
        return (v as? MutableSet<String>) ?: defValues
    }

    override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
    override fun contains(key: String?): Boolean = data.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        /* no-op */
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        /* no-op */
    }

    // Test-only helper: seed a value without going through Editor.
    fun seed(key: String, value: Any?) {
        if (value == null) data.remove(key) else data[key] = value
    }

    private inner class Editor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = also { pending[key!!] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = also { pending[key!!] = values }
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = also { pending[key!!] = value }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = also { pending[key!!] = value }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = also { pending[key!!] = value }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = also { pending[key!!] = value }
        override fun remove(key: String?): SharedPreferences.Editor = also { removed.add(key!!) }
        override fun clear(): SharedPreferences.Editor = also { clearAll = true }

        override fun commit(): Boolean {
            if (clearAll) data.clear()
            removed.forEach { data.remove(it) }
            pending.forEach { (k, v) -> if (v == null) data.remove(k) else data[k] = v }
            return true
        }

        override fun apply() { commit() }
    }
}

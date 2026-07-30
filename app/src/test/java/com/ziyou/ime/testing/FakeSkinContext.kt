package com.ziyou.ime.testing

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File

/**
 * 皮肤模块 JVM 单测用的 [Context] 测试替身。
 *
 * 纯 JVM 环境（unitTests.isReturnDefaultValues = true）下 Android 存根的
 * SharedPreferences / filesDir 均不可用，此处以内存 prefs + 临时目录替代，
 * 覆盖 [com.ziyou.ime.skin.SkinRepository] 依赖的全部 Context 能力。
 */
class FakeSkinContext(private val filesRoot: File) : ContextWrapper(null) {

    private val prefsByName = mutableMapOf<String, InMemorySharedPreferences>()

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        prefsByName.getOrPut(name) { InMemorySharedPreferences() }

    override fun getFilesDir(): File = filesRoot

    override fun getApplicationContext(): Context = this
}

/** 内存 [SharedPreferences]（apply/commit 立即生效，无监听器分发）。 */
class InMemorySharedPreferences : SharedPreferences {

    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = HashMap(values)

    override fun getString(key: String, defValue: String?): String? =
        values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        values[key] as? MutableSet<String> ?: defValues

    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String, defValue: Float): Float =
        values[key] as? Float ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String, value: String?) = apply { pending[key] = value }

        override fun putStringSet(key: String, value: MutableSet<String>?) =
            apply { pending[key] = value }

        override fun putInt(key: String, value: Int) = apply { pending[key] = value }

        override fun putLong(key: String, value: Long) = apply { pending[key] = value }

        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }

        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }

        override fun remove(key: String) = apply { removals += key }

        override fun clear() = apply { clearAll = true }

        override fun commit(): Boolean {
            if (clearAll) values.clear()
            values.keys.removeAll(removals)
            for ((key, value) in pending) {
                if (value == null) values.remove(key) else values[key] = value
            }
            return true
        }

        override fun apply() {
            commit()
        }
    }
}

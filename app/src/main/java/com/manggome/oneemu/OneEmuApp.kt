package com.manggome.oneemu

import android.app.Application
import com.manggome.oneemu.core.CoreRegistry
import com.manggome.oneemu.data.Settings
import com.manggome.oneemu.data.db.AppDatabase
import com.manggome.oneemu.library.RomScanner
import com.manggome.oneemu.util.AppDirs
import com.manggome.oneemu.model.SystemId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Application + tiny service locator. Features get their dependencies through [OneEmuApp.get].
 */
class OneEmuApp : Application() {
    val settings: Settings by lazy { Settings(this) }
    val db: AppDatabase by lazy { AppDatabase.get(this) }
    val cores: CoreRegistry by lazy { CoreRegistry(this) }
    val dirs: AppDirs by lazy { AppDirs(this) }
    val scanner: RomScanner by lazy { RomScanner(this, db, cores, dirs) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Mirror the per-system default-core preference into CoreRegistry so synchronous callers see it.
        appScope.launch {
            settings.flow.map { prefs ->
                SystemId.entries.mapNotNull { sys -> prefs[Settings.Keys.coreForSystem(sys.id)]?.takeIf { it.isNotEmpty() }?.let { sys.id to it } }.toMap()
            }.collect { cores.preferredCoreIds = it }
        }
    }

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        lateinit var instance: OneEmuApp
            private set

        fun get(): OneEmuApp = instance
    }
}

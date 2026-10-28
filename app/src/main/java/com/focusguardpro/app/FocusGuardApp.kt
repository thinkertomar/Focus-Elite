package com.focusguardpro.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.room.Room
import androidx.room.migration.Migration
import com.focusguardpro.app.db.AppDatabase

open class FocusGuardApp : Application() {

    override fun onCreate() {
        super.onCreate()
        SystemDiagnosticsAndUpdatesManager.installGlobalExceptionLogger(this)
        ensureRoomMainThreadQueries(this)
        try {
            AppCategoryPrefs.getStudyApps(this)
        } catch (_: Throwable) {}

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
                ensureRoomMainThreadQueries(this@FocusGuardApp)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                ensureRoomMainThreadQueries(this@FocusGuardApp)
            }
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {
                ensureRoomMainThreadQueries(this@FocusGuardApp)
            }
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    companion object {
        @Volatile
        private var configuredDbRef: AppDatabase? = null

        @Synchronized
        fun ensureRoomMainThreadQueries(context: Context) {
            try {
                val dbClass = AppDatabase::class.java
                val instanceField = dbClass.getDeclaredField("INSTANCE")
                instanceField.isAccessible = true
                val existing = instanceField.get(null) as? AppDatabase

                // Only reuse existing if it is the exact instance we built with allowMainThreadQueries()
                if (existing != null && existing === configuredDbRef && existing.isOpen) {
                    return
                }

                // If another code path created an instance without allowMainThreadQueries(), close it safely
                if (existing != null && existing !== configuredDbRef) {
                    try {
                        existing.close()
                    } catch (_: Throwable) {}
                }

                val migrations = mutableListOf<Migration>()
                for (name in listOf(
                    "MIGRATION_1_2",
                    "MIGRATION_2_3",
                    "MIGRATION_3_4",
                    "MIGRATION_4_5",
                    "MIGRATION_5_6",
                    "MIGRATION_6_7"
                )) {
                    try {
                        val f = dbClass.getDeclaredField(name)
                        f.isAccessible = true
                        (f.get(null) as? Migration)?.let { migrations.add(it) }
                    } catch (_: Throwable) {}
                }

                val builder = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "focusguard_fitness_db"
                )
                if (migrations.isNotEmpty()) {
                    builder.addMigrations(*migrations.toTypedArray())
                }
                val db = builder
                    .fallbackToDestructiveMigration()
                    .allowMainThreadQueries()
                    .build()

                configuredDbRef = db
                instanceField.set(null, db)
            } catch (e: Throwable) {
                android.util.Log.e("FocusEliteApp", "Failed to init Room with allowMainThreadQueries", e)
            }
        }
    }
}

class FocusEliteApp : FocusGuardApp()

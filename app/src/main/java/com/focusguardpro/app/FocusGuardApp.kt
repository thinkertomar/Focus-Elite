package com.focusguardpro.app

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.room.Room
import androidx.room.migration.Migration
import com.focusguardpro.app.db.AppDatabase

open class FocusGuardApp : Application() {

    private val packageRemovalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            if (action != Intent.ACTION_PACKAGE_REMOVED && action != Intent.ACTION_PACKAGE_FULLY_REMOVED) return
            // Ignore app updates (EXTRA_REPLACING = true); only capture real uninstalls
            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
            val pkg = intent.data?.schemeSpecificPart ?: return
            if (pkg.isBlank() || pkg == context.packageName) return
            AppMetadataCache.onPackageRemoved(pkg)
            UsageStatsHelper.onPackageUninstalled(context.applicationContext, pkg)
        }
    }

    override fun onCreate() {
        super.onCreate()
        SystemDiagnosticsAndUpdatesManager.installGlobalExceptionLogger(this)
        ensureRoomMainThreadQueries(this)
        try {
            AppCategoryPrefs.getStudyApps(this)
            AppMetadataCache.init(this)
            AppMetadataCache.warmUpAsync(this)
            BlockPolicyEngine.warmCacheAsync(this)
            UsageStatsHelper.warmUpAsync(this)
            TaskReminderScheduler.install()
            TaskReminderScheduler.rescheduleAllPendingReminders(this)
        } catch (_: Throwable) {}

        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
                addDataScheme("package")
            }
            ContextCompat.registerReceiver(
                this,
                packageRemovalReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
        } catch (_: Throwable) {}

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
                ensureRoomMainThreadQueries(this@FocusGuardApp)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                ensureRoomMainThreadQueries(this@FocusGuardApp)
            }
            override fun onActivityStarted(activity: Activity) {
                if (activity !is SelfThemedDisciplineActivity) {
                    UiThemePrefs.applyWindowAndViewTreeTheme(activity)
                }
            }
            override fun onActivityResumed(activity: Activity) {
                FocusSelfUsageTracker.onActivityResumed(activity)
                if (activity !is SelfThemedDisciplineActivity) {
                    UiThemePrefs.applyWindowAndViewTreeTheme(activity)
                    activity.window?.decorView?.post {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            UiThemePrefs.applyWindowAndViewTreeTheme(activity)
                        }
                    }
                }
            }
            override fun onActivityPaused(activity: Activity) {
                FocusSelfUsageTracker.onActivityPaused(activity)
            }
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

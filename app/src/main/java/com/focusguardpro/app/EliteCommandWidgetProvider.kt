package com.focusguardpro.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import android.widget.Toast

/**
 * All-in-One Command Deck Home Screen Widget Provider.
 * Renders Dual Concentric Neon Telemetry Rings (Screen Time vs Steps),
 * Live Focus Layer Shield Status, and 1-Tap 25m + 40Hz Gamma Deep Focus ignition.
 */
class EliteCommandWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE_FOCUS = "com.focusguardpro.app.ACTION_TOGGLE_FOCUS"
        const val ACTION_QUICK_FOCUS = "com.focusguardpro.app.ACTION_QUICK_FOCUS"
        const val ACTION_REFRESH_WIDGET = "com.focusguardpro.app.ACTION_REFRESH_WIDGET"

        fun updateAllWidgets(context: Context) {
            try {
                val appContext = context.applicationContext
                val manager = AppWidgetManager.getInstance(appContext) ?: return
                val component = ComponentName(appContext, EliteCommandWidgetProvider::class.java)
                val ids = manager.getAppWidgetIds(component)
                if (ids != null && ids.isNotEmpty()) {
                    for (id in ids) {
                        updateSingleWidget(appContext, manager, id)
                    }
                }
            } catch (_: Throwable) {}
        }

        fun isAnyWidgetPinned(context: Context): Boolean {
            return try {
                val manager = AppWidgetManager.getInstance(context.applicationContext) ?: return false
                val component = ComponentName(context.applicationContext, EliteCommandWidgetProvider::class.java)
                val ids = manager.getAppWidgetIds(component)
                ids != null && ids.isNotEmpty()
            } catch (_: Throwable) {
                false
            }
        }

        fun requestPinCommandDeck(context: Context): Boolean {
            updateAllWidgets(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val appWidgetManager = context.getSystemService(AppWidgetManager::class.java)
                val provider = ComponentName(context, EliteCommandWidgetProvider::class.java)
                if (appWidgetManager != null && appWidgetManager.isRequestPinAppWidgetSupported) {
                    return appWidgetManager.requestPinAppWidget(provider, null, null)
                }
            }
            return false
        }

        private fun updateSingleWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_focus_overview)

            // 1. Render high-DPI Command Deck Canvas Bitmap
            val bitmap = CommandDeckWidgetPrefs.renderCommandDeckBitmap(
                context = context,
                widthPx = 880,
                heightPx = 340,
                animFraction = 1f
            )
            views.setImageViewBitmap(R.id.iv_widget_command_canvas, bitmap)

            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

            // 2. Bind Zone 1 (Left Rings) -> Open Physical Activity & Screen Time Breakdown
            val ringsIntent = Intent(context, ActivityStatsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val ringsPi = PendingIntent.getActivity(context, 101, ringsIntent, flags)
            views.setOnClickPendingIntent(R.id.layout_widget_ring, ringsPi)

            // 3. Bind Zone 2 (Center Focus Shield) -> Broadcast Toggle Focus Shield
            val shieldIntent = Intent(context, EliteCommandWidgetProvider::class.java).apply {
                action = ACTION_TOGGLE_FOCUS
            }
            val shieldPi = PendingIntent.getBroadcast(context, 102, shieldIntent, flags)
            views.setOnClickPendingIntent(R.id.btn_widget_focus_toggle, shieldPi)

            // 4. Bind Zone 3 Top (1-Tap 25m + 40Hz Deep Focus) -> Broadcast Quick Focus
            val quickFocusIntent = Intent(context, EliteCommandWidgetProvider::class.java).apply {
                action = ACTION_QUICK_FOCUS
            }
            val quickFocusPi = PendingIntent.getBroadcast(context, 103, quickFocusIntent, flags)
            views.setOnClickPendingIntent(R.id.btn_widget_quick_focus, quickFocusPi)

            // 5. Bind Zone 3 Bottom (Studio & Skins) -> Open WidgetStudioActivity
            val studioIntent = Intent(context, WidgetStudioActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val studioPi = PendingIntent.getActivity(context, 104, studioIntent, flags)
            views.setOnClickPendingIntent(R.id.btn_widget_open_studio, studioPi)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        FocusGuardApp.ensureRoomMainThreadQueries(context)
        for (appWidgetId in appWidgetIds) {
            updateSingleWidget(context, appWidgetManager, appWidgetId)
        }
        FocusWidgetService.syncTelemetryNow(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        FocusGuardApp.ensureRoomMainThreadQueries(context)
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_TOGGLE_FOCUS -> {
                FocusWidgetService.toggleShieldFromWidget(context)
            }

            ACTION_QUICK_FOCUS -> {
                FocusWidgetService.triggerQuickFocusFromWidget(context)
            }

            ACTION_REFRESH_WIDGET -> {
                FocusWidgetService.syncTelemetryNow(context)
            }
        }
    }
}

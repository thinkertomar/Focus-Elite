package com.focusguardpro.app

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

class AppAdapter(
    private var apps: List<AppModel>,
    private var usedMinutesByPkg: Map<String, Int> = emptyMap(),
    private var launchCountByPkg: Map<String, Int> = emptyMap(),
    private var totalBrowserMinutesByPkg: Map<String, Int> = emptyMap(),
    private var studyApps: Set<String> = emptySet(),
    private var improveApps: Set<String> = emptySet(),
    private var ignoredApps: Set<String> = emptySet(),
    private var hardBlockedApps: Set<String> = emptySet(),
    private var limitMinsByPkg: Map<String, Int> = emptyMap(),
    private var unblockedTodayPkgs: Set<String> = emptySet(),
    private var defaultCap: Int = 30,
    private val onLimitClick: (AppModel) -> Unit,
    private val onToggle: (AppModel, Boolean) -> Unit
) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

    fun updateData(
        newApps: List<AppModel>,
        newUsedMinutesByPkg: Map<String, Int>,
        newLaunchCountByPkg: Map<String, Int>,
        newTotalBrowserMinutesByPkg: Map<String, Int>,
        newStudyApps: Set<String>,
        newImproveApps: Set<String>,
        newIgnoredApps: Set<String>,
        newHardBlockedApps: Set<String>,
        newLimitMinsByPkg: Map<String, Int>,
        newUnblockedTodayPkgs: Set<String>,
        newDefaultCap: Int
    ) {
        this.apps = newApps
        this.usedMinutesByPkg = newUsedMinutesByPkg
        this.launchCountByPkg = newLaunchCountByPkg
        this.totalBrowserMinutesByPkg = newTotalBrowserMinutesByPkg
        this.studyApps = newStudyApps
        this.improveApps = newImproveApps
        this.ignoredApps = newIgnoredApps
        this.hardBlockedApps = newHardBlockedApps
        this.limitMinsByPkg = newLimitMinsByPkg
        this.unblockedTodayPkgs = newUnblockedTodayPkgs
        this.defaultCap = newDefaultCap
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.appIcon)
        val name: TextView = view.findViewById(R.id.appName)
        val limitButton: TextView = view.findViewById(R.id.limitButton)
        val toggle: Switch = view.findViewById(R.id.appToggle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_block, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        val context = holder.itemView.context
        val palette = UiThemePrefs.getSelectedPalette(context)
        val density = context.resources.displayMetrics.density

        // Apply theme card background and compact padding if enabled
        holder.itemView.background = UiThemePrefs.createCardBackground(context, cornerDp = 14f, isHero = false)
        val vertPad = if (UiThemePrefs.isCompactListEnabled(context)) {
            (9f * density).toInt()
        } else {
            (12f * density).toInt()
        }
        val horizPad = (12f * density).toInt()
        holder.itemView.setPadding(horizPad, vertPad, horizPad, vertPad)

        holder.icon.setImageDrawable(app.icon)

        val isBrowser = BlockPolicyEngine.isBrowserPackage(app.packageName)
        val usedMinutesToday = usedMinutesByPkg[app.packageName] ?: 0
        val totalBrowserMins = totalBrowserMinutesByPkg[app.packageName] ?: usedMinutesToday
        val studyBrowserMins = if (isBrowser) {
            BlockPolicyEngine.getStudyBrowserMinutesForPackage(context, app.packageName).toInt()
        } else {
            0
        }
        val launchesToday = launchCountByPkg[app.packageName] ?: 0
        val limitMins = limitMinsByPkg[app.packageName] ?: 0
        val hardBlocked = hardBlockedApps.contains(app.packageName)
        val isStudy = studyApps.contains(app.packageName)
        val isImprove = improveApps.contains(app.packageName)
        val isIgnored = ignoredApps.contains(app.packageName)
        val isWhitelisted = ((isStudy || isImprove) && !isIgnored) || unblockedTodayPkgs.contains(app.packageName)

        val isOffDayWebPaused = isBrowser && BlockPolicyEngine.isWebShieldPausedForOffDay(context)
        val browserWinStatus = if (isBrowser && !isOffDayWebPaused) {
            BlockPolicyEngine.getBrowserAccessWindowStatus(context)
        } else {
            null
        }
        val browserBreakAllowance = if (isBrowser && browserWinStatus?.isEnabled == true) {
            browserWinStatus.durationMinutes
        } else {
            0
        }
        val effectiveLimit = when {
            isIgnored -> if (limitMins in 1..29) limitMins else 30
            isBrowser && browserBreakAllowance > 0 -> maxOf(limitMins, browserBreakAllowance)
            limitMins > 0 -> limitMins
            else -> defaultCap
        }
        val isBrowserFreeWindow = browserWinStatus?.isActiveNow == true
        val isLimitReached = usedMinutesToday >= effectiveLimit && !isWhitelisted && !isOffDayWebPaused && !isBrowserFreeWindow

        // Show Blue Tick (✓) whenever app is blocked (hard block, suggestion block, or limit reached)
        val showBlueTick = !isOffDayWebPaused && !isBrowserFreeWindow && (app.isBlocked || hardBlocked || isLimitReached)

        val isWhiteBg = UiThemePrefs.isWhiteBackgroundActive(context)
        val isBlackBg = UiThemePrefs.isBlackBackgroundActive(context)
        val primaryNameColor = UiThemePrefs.getScreenHeaderTextColor(context)
        holder.name.setTextColor(primaryNameColor)

        val showBadgeDetails = UiThemePrefs.isShowBlueTickStatusEnabled(context)
        val blueTickColor = if (isWhiteBg) Color.parseColor("#0284C7") else Color.parseColor("#38BDF8")
        val opensSuffix = if (launchesToday > 0) " • $launchesToday opens" else ""
        val browserStudySuffix = if (isBrowser) " · Study ${studyBrowserMins}m" else ""
        val quotaBar = buildInlineQuotaBar(usedMinutesToday, effectiveLimit, isWhitelisted)

        if (showBlueTick && showBadgeDetails) {
            val sb = SpannableStringBuilder()
            sb.append(app.appName)
            val tickStart = sb.length
            sb.append("  ☑")
            sb.setSpan(
                ForegroundColorSpan(blueTickColor),
                tickStart,
                sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )

            val statusLine = when {
                isBrowser && isLimitReached ->
                    "\n$quotaBar ✓ Study 24/7 • Non-Study Limit (${usedMinutesToday}m / ${effectiveLimit}m$browserStudySuffix)$opensSuffix"
                isBrowser ->
                    "\n$quotaBar ✓ Study 24/7 • Non-Study: ${usedMinutesToday}m / ${effectiveLimit}m$browserStudySuffix$opensSuffix"
                isLimitReached && isIgnored ->
                    "\n$quotaBar ✓ Ignored 30m Cap Reached (${usedMinutesToday}m / ${effectiveLimit}m)$opensSuffix"
                isLimitReached ->
                    "\n$quotaBar ✓ Auto-Blocked • Limit reached (${usedMinutesToday}m / ${effectiveLimit}m)$opensSuffix"
                hardBlocked && limitMins > 0 ->
                    "\n$quotaBar ✓ Blocked • ${limitMins}m cap (${usedMinutesToday}m today)$opensSuffix"
                hardBlocked ->
                    "\n$quotaBar ✓ Hard Blocked (${usedMinutesToday}m today)$opensSuffix"
                limitMins > 0 ->
                    "\n$quotaBar ✓ Active Cap: ${limitMins}m (${usedMinutesToday}m used)$opensSuffix"
                else ->
                    "\n$quotaBar ✓ Blocked (${usedMinutesToday}m today)$opensSuffix"
            }
            val subStart = sb.length
            sb.append(statusLine)
            sb.setSpan(
                ForegroundColorSpan(blueTickColor),
                subStart,
                sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            sb.setSpan(
                RelativeSizeSpan(0.79f),
                subStart,
                sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            holder.name.text = sb
        } else if ((usedMinutesToday > 0 || totalBrowserMins > 0 || launchesToday > 0 || isBrowser || isStudy || isImprove || isIgnored) && showBadgeDetails) {
            val sb = SpannableStringBuilder()
            sb.append(app.appName)
            val subStart = sb.length
            val subText = when {
                isOffDayWebPaused ->
                    "\n🌿 Off-Day: Web Shield Paused • All Sites Open (${totalBrowserMins}m today)$opensSuffix"
                isBrowserFreeWindow ->
                    "\n🎁 Browser Access Window (${browserWinStatus?.remainingMinutes ?: 0}m left) • Non-Study Open (${totalBrowserMins}m today)$opensSuffix"
                isBrowser && (isStudy || isImprove) && !isIgnored ->
                    "\n🟢 Study Browser 24/7 • Distraction Sites Guarded • ${totalBrowserMins}m today$opensSuffix"
                isBrowser ->
                    "\n$quotaBar Study Sites 24/7 • Non-Study: ${usedMinutesToday}m / ${effectiveLimit}m$browserStudySuffix$opensSuffix"
                isIgnored ->
                    "\n🟠 Ignored (${effectiveLimit}m Cap) • ${usedMinutesToday}m / ${effectiveLimit}m today$opensSuffix"
                isStudy ->
                    "\n🟢 Study 24/7 • ${usedMinutesToday}m today$opensSuffix"
                isImprove ->
                    "\n🔵 Improve 24/7 • ${usedMinutesToday}m today$opensSuffix"
                else ->
                    "\n$quotaBar ${usedMinutesToday}m / ${effectiveLimit}m today$opensSuffix"
            }
            sb.append(subText)
            val subColor = when {
                isOffDayWebPaused -> if (isWhiteBg) Color.parseColor("#059669") else Color.parseColor("#34D399")
                isBrowserFreeWindow -> if (isWhiteBg) Color.parseColor("#0284C7") else Color.parseColor("#38BDF8")
                isIgnored -> if (isWhiteBg) Color.parseColor("#D97706") else Color.parseColor("#F59E0B")
                isStudy -> if (isWhiteBg) Color.parseColor("#059669") else Color.parseColor("#34D399")
                isImprove -> if (isWhiteBg) Color.parseColor("#0284C7") else Color.parseColor("#38BDF8")
                usedMinutesToday >= (effectiveLimit * 0.8f).roundToInt() -> if (isWhiteBg) Color.parseColor("#D97706") else Color.parseColor("#F59E0B")
                else -> UiThemePrefs.getScreenSubHeaderTextColor(context)
            }
            sb.setSpan(
                ForegroundColorSpan(subColor),
                subStart,
                sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            sb.setSpan(
                RelativeSizeSpan(0.78f),
                subStart,
                sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            holder.name.text = sb
        } else {
            holder.name.text = app.appName
        }

        // Format limit button as a crisp pill badge with proper padding inside its box
        val pillStrokeColor = if (showBlueTick) blueTickColor else palette.accentColor
        val pillBgColor = when {
            isWhiteBg && showBlueTick -> Color.parseColor("#E0F2FE")
            isWhiteBg -> Color.parseColor("#F1F5F9")
            isBlackBg -> Color.parseColor("#0D1117")
            showBlueTick -> Color.parseColor("#13233A")
            else -> Color.parseColor("#182032")
        }
        holder.limitButton.background = GradientDrawable().apply {
            cornerRadius = 10f * density
            setColor(pillBgColor)
            setStroke((1f * density).roundToInt(), pillStrokeColor)
        }
        val padH = (10f * density).roundToInt()
        val padV = (5f * density).roundToInt()
        holder.limitButton.setPadding(padH, padV, padH, padV)
        holder.limitButton.gravity = Gravity.CENTER
        holder.limitButton.includeFontPadding = false
        holder.limitButton.typeface = Typeface.DEFAULT_BOLD
        holder.limitButton.textSize = 12.5f

        when {
            showBlueTick && limitMins > 0 -> {
                holder.limitButton.text = "✓ ${limitMins}m"
                holder.limitButton.setTextColor(blueTickColor)
            }
            showBlueTick -> {
                holder.limitButton.text = "✓ ${effectiveLimit}m"
                holder.limitButton.setTextColor(blueTickColor)
            }
            limitMins > 0 -> {
                holder.limitButton.text = "${limitMins}m ⚙"
                holder.limitButton.setTextColor(palette.accentColor)
            }
            else -> {
                holder.limitButton.text = "⚙ Cap"
                holder.limitButton.setTextColor(palette.accentColor)
            }
        }

        holder.limitButton.setOnClickListener {
            onLimitClick(app)
        }

        // Style the Switch with a distinct Blue Tick tint when active
        val thumbStates = arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_checked)
        )
        val thumbColors = intArrayOf(
            Color.parseColor("#3B82F6"), // Blue tick switch thumb when checked
            Color.parseColor("#64748B")
        )
        val trackColors = intArrayOf(
            Color.parseColor("#663B82F6"),
            Color.parseColor("#33475569")
        )
        holder.toggle.thumbTintList = ColorStateList(thumbStates, thumbColors)
        holder.toggle.trackTintList = ColorStateList(thumbStates, trackColors)

        holder.toggle.setOnCheckedChangeListener(null)
        holder.toggle.isChecked = showBlueTick
        holder.toggle.setOnCheckedChangeListener { _, isChecked ->
            SensoryFeedbackEngine.toggle(context, holder.toggle)
            app.isBlocked = isChecked
            onToggle(app, isChecked)
            holder.itemView.post {
                if (holder.bindingAdapterPosition != RecyclerView.NO_POSITION) {
                    notifyItemChanged(holder.bindingAdapterPosition)
                }
            }
        }
    }

    private fun buildInlineQuotaBar(usedMins: Int, limitMins: Int, isWhitelisted: Boolean): String {
        if (isWhitelisted || limitMins <= 0) return ""
        val ratio = (usedMins.toFloat() / limitMins.toFloat()).coerceIn(0f, 1f)
        val rawFilled = (ratio * 5f).roundToInt()
        val filled = when {
            usedMins <= 0 -> 0
            usedMins >= limitMins -> 5
            rawFilled == 0 -> 1
            else -> rawFilled.coerceIn(1, 5)
        }
        val empty = 5 - filled
        val pct = (ratio * 100f).roundToInt().coerceIn(0, 100)
        return "[" + "▰".repeat(filled) + "▱".repeat(empty) + " ${pct}%]"
    }

    override fun getItemCount(): Int = apps.size
}

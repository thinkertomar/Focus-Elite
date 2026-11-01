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
    private val apps: List<AppModel>,
    private val usedMinutesByPkg: Map<String, Int> = emptyMap(),
    private val launchCountByPkg: Map<String, Int> = emptyMap(),
    private val totalBrowserMinutesByPkg: Map<String, Int> = emptyMap(),
    private val onLimitClick: (AppModel) -> Unit,
    private val onToggle: (AppModel, Boolean) -> Unit
) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

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

        val usedMinutesToday = usedMinutesByPkg[app.packageName] ?: 0
        val totalBrowserMins = totalBrowserMinutesByPkg[app.packageName] ?: usedMinutesToday
        val studyBrowserMins = (totalBrowserMins - usedMinutesToday).coerceAtLeast(0)
        val launchesToday = launchCountByPkg[app.packageName] ?: 0
        val limitMins = TimeLimitManager.getLimitMinutes(context, app.packageName)
        val hardBlocked = HardBlockManager.isHardBlocked(context, app.packageName)
        val isBrowser = BlockPolicyEngine.isBrowserPackage(app.packageName)
        val isStudy = AppCategoryPrefs.getStudyApps(context).contains(app.packageName)
        val isImprove = AppCategoryPrefs.getImprovementApps(context).contains(app.packageName)
        val isWhitelisted = isStudy || isImprove || BlockPolicyEngine.isUnblockedForToday(context, app.packageName)

        val defaultCap = UiThemePrefs.getDefaultSuggestionLimitMinutes(context)
        val effectiveLimit = if (limitMins > 0) limitMins else defaultCap
        val isLimitReached = usedMinutesToday >= effectiveLimit && !isWhitelisted

        val policyBlocked = try {
            BlockPolicyEngine.isAppBlocked(context, app.packageName)
        } catch (_: Throwable) {
            false
        }

        // Show Blue Tick (✓) whenever app is blocked (hard block, suggestion block, or 30m limit reached)
        val showBlueTick = app.isBlocked || hardBlocked || isLimitReached || (limitMins > 0 && policyBlocked)

        val showBadgeDetails = UiThemePrefs.isShowBlueTickStatusEnabled(context)
        val blueTickColor = Color.parseColor("#38BDF8") // Vibrant sky-blue tick badge
        val opensSuffix = if (launchesToday > 0) " • $launchesToday opens" else ""
        val browserStudySuffix = if (isBrowser && studyBrowserMins > 0) " · Study ${studyBrowserMins}m" else ""
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
        } else if ((usedMinutesToday > 0 || totalBrowserMins > 0 || launchesToday > 0 || isBrowser || isStudy || isImprove) && showBadgeDetails) {
            val sb = SpannableStringBuilder()
            sb.append(app.appName)
            val subStart = sb.length
            val subText = when {
                isBrowser ->
                    "\n$quotaBar Study Sites 24/7 • Non-Study: ${usedMinutesToday}m / ${effectiveLimit}m$browserStudySuffix$opensSuffix"
                isStudy ->
                    "\n🟢 Study 24/7 • ${usedMinutesToday}m today$opensSuffix"
                isImprove ->
                    "\n🔵 Improve 24/7 • ${usedMinutesToday}m today$opensSuffix"
                else ->
                    "\n$quotaBar ${usedMinutesToday}m / ${effectiveLimit}m today$opensSuffix"
            }
            sb.append(subText)
            val subColor = when {
                isStudy -> Color.parseColor("#34D399")
                isImprove -> Color.parseColor("#38BDF8")
                usedMinutesToday >= (effectiveLimit * 0.8f).roundToInt() -> Color.parseColor("#F59E0B")
                else -> Color.parseColor("#94A3B8")
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
        val pillBgColor = if (showBlueTick) Color.parseColor("#13233A") else Color.parseColor("#182032")
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
        val filled = (ratio * 5f).roundToInt().coerceIn(0, 5)
        val empty = 5 - filled
        return "[" + "▰".repeat(filled) + "▱".repeat(empty) + "]"
    }

    override fun getItemCount(): Int = apps.size
}

package com.focusguardpro.app

import android.content.res.ColorStateList
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppAdapter(
    private val apps: List<AppModel>,
    private val usedMinutesByPkg: Map<String, Int> = emptyMap(),
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
            (8f * density).toInt()
        } else {
            (12f * density).toInt()
        }
        val horizPad = (12f * density).toInt()
        holder.itemView.setPadding(horizPad, vertPad, horizPad, vertPad)

        holder.icon.setImageDrawable(app.icon)

        val usedMinutesToday = usedMinutesByPkg[app.packageName] ?: 0
        val limitMins = TimeLimitManager.getLimitMinutes(context, app.packageName)
        val hardBlocked = HardBlockManager.isHardBlocked(context, app.packageName)
        val isWhitelisted = AppCategoryPrefs.getStudyApps(context).contains(app.packageName) ||
            AppCategoryPrefs.getImprovementApps(context).contains(app.packageName)

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
                hardBlocked && limitMins > 0 -> "\n✓ Blocked • ${limitMins}m cap (${usedMinutesToday}m today)"
                hardBlocked -> "\n✓ Hard Blocked (${usedMinutesToday}m today)"
                isLimitReached -> "\n✓ Auto-Blocked • Limit reached (${usedMinutesToday}m / ${effectiveLimit}m)"
                limitMins > 0 -> "\n✓ Active Cap: ${limitMins}m (${usedMinutesToday}m used)"
                else -> "\n✓ Blocked (${usedMinutesToday}m today)"
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
                RelativeSizeSpan(0.80f),
                subStart,
                sb.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            holder.name.text = sb
        } else if (usedMinutesToday > 0 && showBadgeDetails) {
            val sb = SpannableStringBuilder()
            sb.append(app.appName)
            val subStart = sb.length
            sb.append("\n${usedMinutesToday}m used today")
            sb.setSpan(
                ForegroundColorSpan(Color.parseColor("#94A3B8")),
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

        // Format limit button with blue tick when capped/blocked
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
                holder.limitButton.text = context.getString(R.string.limits_gear)
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

    override fun getItemCount(): Int = apps.size
}

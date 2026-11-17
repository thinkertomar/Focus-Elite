package com.focusguardpro.app.calisthenics

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.focusguardpro.app.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val ACCENT = "#6C63FF"

fun formatInstructions(steps: List<String>): CharSequence {
    if (steps.isEmpty()) {
        return "Perform with strict form, controlled tempo and full range of motion."
    }
    val sb = SpannableStringBuilder()
    steps.forEachIndexed { idx, step ->
        val num = "${idx + 1}"
        val start = sb.length
        sb.append(num)
        sb.setSpan(ForegroundColorSpan(Color.parseColor(ACCENT)), start, start + num.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.BOLD), start, start + num.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append("   ").append(step.trim())
        if (idx != steps.lastIndex) sb.append("\n\n")
    }
    return sb
}

fun styleLevelBadge(tv: TextView, level: String?) {
    val text = level?.replaceFirstChar(Char::titlecase) ?: "Beginner"
    tv.text = text
    when (text.lowercase()) {
        "beginner" -> {
            tv.setTextColor(Color.parseColor("#3DDC97"))
            tv.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#1E3A30"))
        }
        "intermediate" -> {
            tv.setTextColor(Color.parseColor("#FFD600"))
            tv.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#3A3314"))
        }
        else -> {
            tv.setTextColor(Color.parseColor("#FF6B6B"))
            tv.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#3A1E22"))
        }
    }
}

fun styleEquipmentBadge(tv: TextView, ex: CalisthenicsExercise) {
    if (ex.isBodyweight) {
        tv.text = "Bodyweight"
        tv.setTextColor(Color.parseColor("#00E5FF"))
        tv.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#162E3B"))
    } else {
        tv.text = ex.equipment?.replaceFirstChar(Char::titlecase) ?: "Home item"
        tv.setTextColor(Color.parseColor("#FFD54F"))
        tv.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#332B14"))
    }
}

fun bindThumb(image: ImageView, initial: TextView, ex: CalisthenicsExercise?, widthPx: Int) {
    initial.text = (ex?.name?.firstOrNull() ?: '•').uppercaseChar().toString()
    val path = ex?.images?.firstOrNull()
    if (path == null || ExerciseImageLoader.isKnownMissing(path)) {
        image.tag = null
        image.setImageDrawable(null)
        initial.visibility = View.VISIBLE
        return
    }
    val tagKey = "$path@$widthPx"
    image.tag = tagKey
    val cached = ExerciseImageLoader.peek(path, widthPx)
    if (cached != null) {
        image.setImageBitmap(cached)
        initial.visibility = View.GONE
        return
    }
    image.setImageDrawable(null)
    initial.visibility = View.VISIBLE
    ExerciseImageLoader.load(image.context, path, widthPx) { bmp ->
        if (image.tag == tagKey) {
            if (bmp != null) {
                image.setImageBitmap(bmp)
                initial.visibility = View.GONE
            } else {
                initial.visibility = View.VISIBLE
            }
        }
    }
}

class CalisthenicsCatalogueAdapter(
    private var exercises: List<CalisthenicsExercise>,
    private val isFavorite: (String) -> Boolean,
    private val onToggleFavorite: (CalisthenicsExercise) -> Unit,
    private val onSelectExercise: (CalisthenicsExercise) -> Unit
) : RecyclerView.Adapter<CalisthenicsCatalogueAdapter.ViewHolder>() {

    private val expandedIds = mutableSetOf<String>()

    fun updateList(newList: List<CalisthenicsExercise>) {
        exercises = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_calisthenics_exercise, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(exercises[position])
    }

    override fun getItemCount(): Int = exercises.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivThumb: ImageView = itemView.findViewById(R.id.ivThumb)
        private val tvThumbInitial: TextView = itemView.findViewById(R.id.tvThumbInitial)
        private val tvName: TextView = itemView.findViewById(R.id.tvExerciseName)
        private val tvLevel: TextView = itemView.findViewById(R.id.tvLevelBadge)
        private val tvEquipment: TextView = itemView.findViewById(R.id.tvEquipmentBadge)
        private val tvMeta: TextView = itemView.findViewById(R.id.tvMetaLine)
        private val tvFavorite: TextView = itemView.findViewById(R.id.tvFavorite)
        private val btnSelect: Button = itemView.findViewById(R.id.btnSelectExercise)
        private val layoutInstructionsToggle: View = itemView.findViewById(R.id.layoutInstructionsToggle)
        private val tvToggleInstructions: TextView = itemView.findViewById(R.id.tvToggleInstructions)
        private val tvChevron: TextView = itemView.findViewById(R.id.tvInstructionsChevron)
        private val demoView: ExerciseDemoView = itemView.findViewById(R.id.demoViewItem)
        private val tvInstructionsBody: TextView = itemView.findViewById(R.id.tvInstructionsBody)

        fun bind(ex: CalisthenicsExercise) {
            tvName.text = ex.name
            bindThumb(ivThumb, tvThumbInitial, ex, THUMB_PX)

            styleLevelBadge(tvLevel, ex.level)
            styleEquipmentBadge(tvEquipment, ex)

            val secondary = ex.secondaryMuscles.take(2).joinToString(", ") { it.replaceFirstChar(Char::titlecase) }
            tvMeta.text = buildString {
                append(ex.muscleGroup)
                append(" · ")
                append(ex.categoryLabel)
                append(" · ")
                append(if (ex.isHoldBased) "Hold" else "Reps")
                if (secondary.isNotEmpty()) append(" · +$secondary")
            }

            renderFavorite(isFavorite(ex.id))
            tvFavorite.setOnClickListener {
                onToggleFavorite(ex)
                renderFavorite(isFavorite(ex.id))
            }

            val expanded = expandedIds.contains(ex.id)
            tvInstructionsBody.visibility = if (expanded) View.VISIBLE else View.GONE
            tvChevron.text = if (expanded) "▲" else "▼"
            tvToggleInstructions.text =
                if (expanded) "Hide demo & steps" else "Demo & steps (${ex.instructions.size})"

            if (expanded) {
                tvInstructionsBody.text = formatInstructions(ex.instructions)
                demoView.bind(ex.images)
            } else {
                demoView.bind(emptyList())
            }

            layoutInstructionsToggle.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    if (!expandedIds.add(ex.id)) expandedIds.remove(ex.id)
                    notifyItemChanged(pos)
                }
            }

            btnSelect.setOnClickListener { onSelectExercise(ex) }
        }

        private fun renderFavorite(fav: Boolean) {
            tvFavorite.text = if (fav) "★" else "☆"
            tvFavorite.setTextColor(Color.parseColor(if (fav) "#FFD54F" else "#9AA3B2"))
        }
    }

    companion object {
        private const val THUMB_PX = 240
    }
}

class WorkoutHistoryAdapter(
    private var entries: List<HistoryEntry>,
    private val onItemClicked: ((HistoryEntry) -> Unit)? = null
) : RecyclerView.Adapter<WorkoutHistoryAdapter.ViewHolder>() {

    private val zone = ZoneId.systemDefault()
    private val timeFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
    private val dateFmt = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
    private val exerciseCache = HashMap<String, CalisthenicsExercise?>()

    fun updateList(newEntries: List<HistoryEntry>) {
        entries = newEntries
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history_log, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(entries[position])
    }

    override fun getItemCount(): Int = entries.size

    private fun whenLabel(ts: Long): String {
        val dt = Instant.ofEpochMilli(ts).atZone(zone)
        val today = LocalDate.now(zone)
        val day = dt.toLocalDate()
        val dayLabel = when (day) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> dateFmt.format(dt)
        }
        return "$dayLabel · ${timeFmt.format(dt)}"
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivThumb: ImageView = itemView.findViewById(R.id.ivHistoryThumb)
        private val tvInitial: TextView = itemView.findViewById(R.id.tvHistoryThumbInitial)
        private val tvName: TextView = itemView.findViewById(R.id.tvHistoryExerciseName)
        private val tvDate: TextView = itemView.findViewById(R.id.tvHistoryDate)
        private val tvPb: TextView = itemView.findViewById(R.id.tvHistoryPb)
        private val tvSets: TextView = itemView.findViewById(R.id.tvHistorySets)
        private val tvTotals: TextView = itemView.findViewById(R.id.tvHistoryTotals)

        fun bind(entry: HistoryEntry) {
            val ex = exerciseCache.getOrPut(entry.exerciseName) {
                CalisthenicsRepository.findByName(itemView.context, entry.exerciseName)
            }
            bindThumb(ivThumb, tvInitial, ex ?: CalisthenicsExercise(name = entry.exerciseName), 160)

            tvName.text = entry.exerciseName
            tvDate.text = whenLabel(entry.timestamp)
            tvPb.visibility = if (entry.isPersonalBest) View.VISIBLE else View.GONE

            val setBreakdown = entry.sets.mapIndexed { idx, s ->
                val r = if (s.reps > 0) "${s.reps} reps" else ""
                val h = if (s.durationSeconds > 0) "${s.durationSeconds}s hold" else ""
                val detail = listOf(r, h).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { "0" }
                "Set ${idx + 1}: $detail"
            }.joinToString("  •  ")
            tvSets.text = setBreakdown

            tvTotals.text = buildString {
                append("${entry.sets.size} ${if (entry.sets.size == 1) "set" else "sets"}")
                if (entry.totalReps > 0) append(" · ${entry.totalReps} total reps")
                if (entry.totalHold > 0) append(" · ${entry.totalHold}s total hold")
            }

            itemView.setOnClickListener {
                onItemClicked?.invoke(entry)
            }
        }
    }
}

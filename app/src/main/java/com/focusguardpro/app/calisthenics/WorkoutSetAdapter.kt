package com.focusguardpro.app.calisthenics

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.focusguardpro.app.R
import com.focusguardpro.app.SensoryFeedbackEngine
import com.focusguardpro.app.UiThemePrefs

class WorkoutSetAdapter(
    private var sets: MutableList<ActiveWorkoutSet>,
    private val onSetToggled: (ActiveWorkoutSet, Boolean) -> Unit,
    private val onSetRemoved: (Int) -> Unit,
    private val onValuesChanged: () -> Unit = {}
) : RecyclerView.Adapter<WorkoutSetAdapter.SetViewHolder>() {

    fun submitSets(newSets: MutableList<ActiveWorkoutSet>) {
        sets = newSets
        notifyDataSetChanged()
    }

    inner class SetViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val rowRoot: LinearLayout = view.findViewById(R.id.rowRoot)
        val tvSetNumber: TextView = view.findViewById(R.id.tvSetNumber)
        val tvPrevious: TextView = view.findViewById(R.id.tvPrevious)
        val llRepsStepper: LinearLayout = view.findViewById(R.id.llRepsStepper)
        val llHoldStepper: LinearLayout = view.findViewById(R.id.llHoldStepper)
        val btnRepsMinus: TextView = view.findViewById(R.id.btnRepsMinus)
        val etRepsValue: EditText = view.findViewById(R.id.etRepsValue)
        val btnRepsPlus: TextView = view.findViewById(R.id.btnRepsPlus)
        val btnHoldMinus: TextView = view.findViewById(R.id.btnHoldMinus)
        val etHoldValue: EditText = view.findViewById(R.id.etHoldValue)
        val btnHoldPlus: TextView = view.findViewById(R.id.btnHoldPlus)
        val btnCheckSet: TextView = view.findViewById(R.id.btnCheckSet)

        var repsWatcher: TextWatcher? = null
        var holdWatcher: TextWatcher? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SetViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_workout_set, parent, false)
        return SetViewHolder(v)
    }

    override fun onBindViewHolder(holder: SetViewHolder, position: Int) {
        val item = sets[position]
        val ctx = holder.itemView.context
        val density = ctx.resources.displayMetrics.density
        val isLight = UiThemePrefs.getMode(ctx) == UiThemePrefs.MODE_WHITE

        item.setNumber = position + 1
        holder.tvSetNumber.text = item.setNumber.toString()
        holder.tvPrevious.text = item.previousSummary

        holder.repsWatcher?.let { holder.etRepsValue.removeTextChangedListener(it) }
        holder.holdWatcher?.let { holder.etHoldValue.removeTextChangedListener(it) }

        if (holder.etRepsValue.text.toString() != item.reps.toString()) {
            holder.etRepsValue.setText(item.reps.toString())
        }
        if (holder.etHoldValue.text.toString() != item.holdSeconds.toString()) {
            holder.etHoldValue.setText(item.holdSeconds.toString())
        }

        // Zero-padding pill backgrounds so digits in REPS and HOLD (s) never clip
        val stepperBgColor = if (isLight) Color.parseColor("#F1F5F9") else Color.parseColor("#111728")
        val stepperStrokeColor = if (isLight) Color.parseColor("#CBD5E1") else Color.parseColor("#26324D")
        val primaryTextColor = if (isLight) Color.parseColor("#0F172A") else Color.WHITE
        val secondaryTextColor = if (isLight) Color.parseColor("#475569") else Color.parseColor("#9AA3B2")

        holder.llRepsStepper.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * density
            setColor(stepperBgColor)
            setStroke((1f * density).toInt(), stepperStrokeColor)
        }
        holder.llRepsStepper.setPadding((2 * density).toInt(), 0, (2 * density).toInt(), 0)

        holder.llHoldStepper.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * density
            setColor(stepperBgColor)
            setStroke((1f * density).toInt(), stepperStrokeColor)
        }
        holder.llHoldStepper.setPadding((2 * density).toInt(), 0, (2 * density).toInt(), 0)

        holder.etRepsValue.setTextColor(primaryTextColor)
        holder.etHoldValue.setTextColor(primaryTextColor)

        val hasPrev = item.prevReps >= 0 || item.prevHold >= 0
        holder.tvPrevious.setTextColor(
            if (hasPrev) Color.parseColor(if (isLight) "#0284C7" else "#4FD1FF") else secondaryTextColor
        )
        holder.tvPrevious.setOnClickListener {
            if (!hasPrev || item.isCompleted) return@setOnClickListener
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            if (item.prevReps >= 0) sets[pos].reps = item.prevReps
            if (item.prevHold >= 0) sets[pos].holdSeconds = item.prevHold
            notifyItemChanged(pos)
            onValuesChanged()
        }

        val repsWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && pos < sets.size) {
                    val v = s?.toString()?.toIntOrNull() ?: 0
                    sets[pos].reps = v.coerceIn(0, 999)
                    onValuesChanged()
                }
            }
        }
        val holdWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && pos < sets.size) {
                    val v = s?.toString()?.toIntOrNull() ?: 0
                    sets[pos].holdSeconds = v.coerceIn(0, 3600)
                    onValuesChanged()
                }
            }
        }
        holder.etRepsValue.addTextChangedListener(repsWatcher)
        holder.etHoldValue.addTextChangedListener(holdWatcher)
        holder.repsWatcher = repsWatcher
        holder.holdWatcher = holdWatcher

        applyRowDecoration(holder, item.isCompleted, density, isLight)

        holder.btnRepsMinus.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                SensoryFeedbackEngine.tap(ctx, holder.btnRepsMinus)
                sets[pos].reps = (sets[pos].reps - 1).coerceAtLeast(0)
                holder.etRepsValue.setText(sets[pos].reps.toString())
                onValuesChanged()
            }
        }

        holder.btnRepsPlus.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                SensoryFeedbackEngine.tap(ctx, holder.btnRepsPlus)
                sets[pos].reps = (sets[pos].reps + 1).coerceAtMost(999)
                holder.etRepsValue.setText(sets[pos].reps.toString())
                onValuesChanged()
            }
        }

        holder.btnHoldMinus.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                SensoryFeedbackEngine.tap(ctx, holder.btnHoldMinus)
                val step = if (sets[pos].holdSeconds <= 15) 1 else 5
                sets[pos].holdSeconds = (sets[pos].holdSeconds - step).coerceAtLeast(0)
                holder.etHoldValue.setText(sets[pos].holdSeconds.toString())
                onValuesChanged()
            }
        }

        holder.btnHoldPlus.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                SensoryFeedbackEngine.tap(ctx, holder.btnHoldPlus)
                val step = if (sets[pos].holdSeconds < 15) 1 else 5
                sets[pos].holdSeconds = (sets[pos].holdSeconds + step).coerceAtMost(3600)
                holder.etHoldValue.setText(sets[pos].holdSeconds.toString())
                onValuesChanged()
            }
        }

        holder.btnCheckSet.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                val target = sets[pos]
                target.isCompleted = !target.isCompleted
                applyRowDecoration(holder, target.isCompleted, density, isLight)
                if (target.isCompleted) {
                    SensoryFeedbackEngine.setCompleted(ctx, holder.btnCheckSet)
                } else {
                    SensoryFeedbackEngine.tap(ctx, holder.btnCheckSet)
                }
                onSetToggled(target, target.isCompleted)
                onValuesChanged()
            }
        }

        holder.tvSetNumber.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) {
                onSetRemoved(pos)
                true
            } else {
                false
            }
        }
    }

    private fun applyRowDecoration(
        holder: SetViewHolder,
        completed: Boolean,
        density: Float,
        isLight: Boolean
    ) {
        if (completed) {
            holder.btnCheckSet.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * density
                setColor(Color.parseColor("#10B981"))
            }
            holder.btnCheckSet.setPadding(0, 0, 0, 0)
            holder.btnCheckSet.setTextColor(Color.WHITE)

            holder.tvSetNumber.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#6C63FF"))
            }
            holder.tvSetNumber.setPadding(0, 0, 0, 0)
            holder.tvSetNumber.setTextColor(Color.WHITE)

            holder.rowRoot.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f * density
                setColor(if (isLight) Color.parseColor("#ECFDF5") else Color.parseColor("#0F2421"))
                setStroke((1.5f * density).toInt(), Color.parseColor("#10B981"))
            }
            val pad = (8 * density).toInt()
            holder.rowRoot.setPadding(pad, pad, pad, pad)
        } else {
            val pillBg = if (isLight) Color.parseColor("#E2E8F0") else Color.parseColor("#1C2338")
            val pillStroke = if (isLight) Color.parseColor("#CBD5E1") else Color.parseColor("#2D3A59")
            holder.btnCheckSet.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12f * density
                setColor(pillBg)
                setStroke((1f * density).toInt(), pillStroke)
            }
            holder.btnCheckSet.setPadding(0, 0, 0, 0)
            holder.btnCheckSet.setTextColor(if (isLight) Color.parseColor("#475569") else Color.parseColor("#9AA3B2"))

            holder.tvSetNumber.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(pillBg)
                setStroke((1f * density).toInt(), pillStroke)
            }
            holder.tvSetNumber.setPadding(0, 0, 0, 0)
            holder.tvSetNumber.setTextColor(if (isLight) Color.parseColor("#0F172A") else Color.WHITE)

            holder.rowRoot.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16f * density
                setColor(if (isLight) Color.parseColor("#F8FAFC") else Color.parseColor("#131826"))
                setStroke((1f * density).toInt(), if (isLight) Color.parseColor("#E2E8F0") else Color.parseColor("#20273B"))
            }
            val pad = (8 * density).toInt()
            holder.rowRoot.setPadding(pad, pad, pad, pad)
        }
    }

    override fun getItemCount(): Int = sets.size
}

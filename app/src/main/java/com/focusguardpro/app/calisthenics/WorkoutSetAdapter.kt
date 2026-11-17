package com.focusguardpro.app.calisthenics

import android.content.res.ColorStateList
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.focusguardpro.app.R

class WorkoutSetAdapter(
    private var sets: MutableList<ActiveWorkoutSet>,
    private val onSetToggled: (ActiveWorkoutSet, Boolean) -> Unit,
    private val onSetRemoved: (Int) -> Unit,
    private val onValuesChanged: () -> Unit = {}
) : RecyclerView.Adapter<WorkoutSetAdapter.ViewHolder>() {

    fun submitSets(newSets: MutableList<ActiveWorkoutSet>) {
        sets = newSets
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_workout_set, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(sets[position], position)
    }

    override fun getItemCount(): Int = sets.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvSetNumber: TextView = itemView.findViewById(R.id.tvSetNumber)
        private val tvPrevious: TextView = itemView.findViewById(R.id.tvPrevious)
        private val btnRepsMinus: ImageButton = itemView.findViewById(R.id.btnRepsMinus)
        private val etRepsValue: EditText = itemView.findViewById(R.id.etRepsValue)
        private val btnRepsPlus: ImageButton = itemView.findViewById(R.id.btnRepsPlus)
        private val btnHoldMinus: ImageButton = itemView.findViewById(R.id.btnHoldMinus)
        private val etHoldValue: EditText = itemView.findViewById(R.id.etHoldValue)
        private val btnHoldPlus: ImageButton = itemView.findViewById(R.id.btnHoldPlus)
        private val btnCheckSet: Button = itemView.findViewById(R.id.btnCheckSet)

        private var repsWatcher: TextWatcher? = null
        private var holdWatcher: TextWatcher? = null

        fun bind(set: ActiveWorkoutSet, position: Int) {
            set.setNumber = position + 1
            tvSetNumber.text = "${position + 1}"
            tvPrevious.text = set.previousSummary

            val hasPrev = set.prevReps >= 0 || set.prevHold >= 0
            tvPrevious.setTextColor(
                if (hasPrev) Color.parseColor("#8E87FF")
                else ContextCompat.getColor(itemView.context, R.color.text_secondary)
            )
            tvPrevious.setOnClickListener {
                if (hasPrev && !set.isCompleted) {
                    if (set.prevReps >= 0) set.reps = set.prevReps
                    if (set.prevHold >= 0) set.holdSeconds = set.prevHold
                    setTexts(set)
                    onValuesChanged()
                }
            }

            repsWatcher?.let { etRepsValue.removeTextChangedListener(it) }
            holdWatcher?.let { etHoldValue.removeTextChangedListener(it) }
            repsWatcher = null
            holdWatcher = null

            setTexts(set)
            updateCompletionUi(set.isCompleted)

            btnRepsMinus.setOnClickListener {
                if (set.reps > 0) { set.reps -= 1; setTexts(set); onValuesChanged() }
            }
            btnRepsPlus.setOnClickListener {
                set.reps += 1; setTexts(set); onValuesChanged()
            }

            repsWatcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    set.reps = s?.toString()?.toIntOrNull() ?: 0
                    onValuesChanged()
                }
            }
            etRepsValue.addTextChangedListener(repsWatcher)

            btnHoldMinus.setOnClickListener {
                set.holdSeconds = if (set.holdSeconds >= 5) set.holdSeconds - 5 else 0
                setTexts(set); onValuesChanged()
            }
            btnHoldPlus.setOnClickListener {
                set.holdSeconds += 5; setTexts(set); onValuesChanged()
            }

            holdWatcher = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    set.holdSeconds = s?.toString()?.toIntOrNull() ?: 0
                    onValuesChanged()
                }
            }
            etHoldValue.addTextChangedListener(holdWatcher)

            btnCheckSet.setOnClickListener {
                set.isCompleted = !set.isCompleted
                updateCompletionUi(set.isCompleted)
                onSetToggled(set, set.isCompleted)
            }

            tvSetNumber.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onSetRemoved(pos)
                true
            }
        }

        private fun setTexts(set: ActiveWorkoutSet) {
            repsWatcher?.let { etRepsValue.removeTextChangedListener(it) }
            holdWatcher?.let { etHoldValue.removeTextChangedListener(it) }
            etRepsValue.setText(set.reps.toString())
            etHoldValue.setText(set.holdSeconds.toString())
            repsWatcher?.let { etRepsValue.addTextChangedListener(it) }
            holdWatcher?.let { etHoldValue.addTextChangedListener(it) }
        }

        private fun updateCompletionUi(completed: Boolean) {
            if (completed) {
                btnCheckSet.setTextColor(Color.parseColor("#0B0F1A"))
                btnCheckSet.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#3DDC97"))
                tvSetNumber.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#3DDC97"))
                tvSetNumber.setTextColor(Color.parseColor("#0B0F1A"))
            } else {
                btnCheckSet.setTextColor(ContextCompat.getColor(itemView.context, R.color.text_secondary))
                btnCheckSet.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#1F2533"))
                tvSetNumber.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#262D3D"))
                tvSetNumber.setTextColor(Color.WHITE)
            }
        }
    }
}

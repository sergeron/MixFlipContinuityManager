package com.sergeron.mixflipcontinuity

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.sergeron.mixflipcontinuity.databinding.ItemAppBinding

enum class AdapterMode {
    CONTINUITY,
    COVER_SCREEN
}

class AppListAdapter(
    private var mode: AdapterMode = AdapterMode.CONTINUITY,
    private val onToggleListener: (AppEntry, Boolean, AdapterMode) -> Unit
) : ListAdapter<AppEntry, AppListAdapter.AppViewHolder>(DIFF_CALLBACK) {

    private val iconCache = object : android.util.LruCache<String, android.graphics.drawable.Drawable>(250) {}
    private val iconExecutor = java.util.concurrent.Executors.newFixedThreadPool(2)

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<AppEntry>() {
            override fun areItemsTheSame(oldItem: AppEntry, newItem: AppEntry): Boolean {
                return oldItem.packageName == newItem.packageName
            }

            override fun areContentsTheSame(oldItem: AppEntry, newItem: AppEntry): Boolean {
                return oldItem.continuityEnabled == newItem.continuityEnabled &&
                        oldItem.coverScreenAllowed == newItem.coverScreenAllowed &&
                        oldItem.isBusy == newItem.isBusy &&
                        oldItem.label == newItem.label
            }
        }
    }

    fun setMode(newMode: AdapterMode) {
        if (this.mode != newMode) {
            this.mode = newMode
            notifyDataSetChanged()
        }
    }

    fun getMode(): AdapterMode = mode

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AppViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class AppViewHolder(private val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: AppEntry) {
            val cachedIcon = item.icon ?: iconCache.get(item.packageName)
            if (cachedIcon != null) {
                binding.imageIcon.setImageDrawable(cachedIcon)
            } else {
                binding.imageIcon.setImageResource(R.drawable.ic_flip_logo)
                val targetPkg = item.packageName
                binding.imageIcon.tag = targetPkg
                iconExecutor.execute {
                    try {
                        val pm = binding.root.context.packageManager
                        val appInfo = pm.getApplicationInfo(targetPkg, 0)
                        val icon = pm.getApplicationIcon(appInfo)
                        iconCache.put(targetPkg, icon)
                        item.icon = icon
                        binding.root.post {
                            if (binding.imageIcon.tag == targetPkg) {
                                binding.imageIcon.setImageDrawable(icon)
                            }
                        }
                    } catch (ignored: Throwable) {}
                }
            }
            binding.textLabel.text = item.label
            binding.textPackage.text = item.packageName
            binding.tagSystem.visibility = if (item.systemApp) View.VISIBLE else View.GONE

            if (item.isBusy) {
                binding.progressItem.visibility = View.VISIBLE
                binding.switchContinuity.visibility = View.INVISIBLE
                binding.switchContinuity.isEnabled = false
            } else {
                binding.progressItem.visibility = View.GONE
                binding.switchContinuity.visibility = View.VISIBLE
                binding.switchContinuity.isEnabled = true
            }

            // Zapobiegamy wyzwalaniu listenera podczas recyclingu widoków
            binding.switchContinuity.setOnCheckedChangeListener(null)
            val isEnabledInCurrentMode = when (mode) {
                AdapterMode.CONTINUITY -> item.continuityEnabled
                AdapterMode.COVER_SCREEN -> item.coverScreenAllowed
            }
            binding.switchContinuity.isChecked = isEnabledInCurrentMode

            binding.switchContinuity.setOnCheckedChangeListener { buttonView, isChecked ->
                if (buttonView.isPressed && !item.isBusy) {
                    onToggleListener(item, isChecked, mode)
                }
            }

            // Kliknięcie w cały wiersz również może przełączać switch
            binding.root.setOnClickListener {
                if (!item.isBusy && binding.switchContinuity.isEnabled) {
                    val nextState = !binding.switchContinuity.isChecked
                    binding.switchContinuity.isChecked = nextState
                    onToggleListener(item, nextState, mode)
                }
            }
        }
    }
}

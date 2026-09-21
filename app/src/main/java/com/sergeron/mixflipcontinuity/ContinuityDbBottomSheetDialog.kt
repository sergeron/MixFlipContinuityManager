package com.sergeron.mixflipcontinuity

import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.sergeron.mixflipcontinuity.databinding.DialogContinuityDbBinding
import com.sergeron.mixflipcontinuity.databinding.ItemContinuityDbRowBinding
import java.util.concurrent.Executors

class ContinuityDbBottomSheetDialog(
    private val onDbChanged: () -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: DialogContinuityDbBinding? = null
    private val binding get() = _binding!!

    private val continuityRepo = ContinuityRepository()
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var adapter: DbRowAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogContinuityDbBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = DbRowAdapter(requireContext().packageManager) { row ->
            confirmDeleteRow(row)
        }

        binding.recyclerDbRows.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerDbRows.adapter = adapter

        binding.btnRefreshDb.setOnClickListener {
            loadDbRows()
        }

        binding.btnClearDisabledDb.setOnClickListener {
            confirmClearDisabled()
        }

        loadDbRows()
    }

    override fun onDestroyView() {
        executor.shutdownNow()
        _binding = null
        super.onDestroyView()
    }

    private fun loadDbRows() {
        binding.progressDb.visibility = View.VISIBLE
        binding.textDbSummary.setText(R.string.db_loading_records)
        binding.btnRefreshDb.isEnabled = false
        binding.btnClearDisabledDb.isEnabled = false

        executor.execute {
            val res = continuityRepo.queryRawDbRows()
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                binding.progressDb.visibility = View.GONE
                binding.btnRefreshDb.isEnabled = true
                binding.btnClearDisabledDb.isEnabled = true

                res.onSuccess { rows ->
                    adapter.submitList(rows)
                    binding.textDbEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                    val activeCount = rows.count { it.enable == 1 }
                    val disabledCount = rows.count { it.enable == 0 }
                    binding.textDbSummary.text =
                        getString(R.string.db_summary, rows.size, activeCount, disabledCount)
                }.onFailure { err ->
                    binding.textDbSummary.text = err.message
                    Toast.makeText(requireContext(), "${err.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun confirmDeleteRow(row: ContinuityDbRow) {
        val pm = requireContext().packageManager
        val appName = try {
            val info = pm.getApplicationInfo(row.packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (t: Throwable) {
            row.packageName
        }

        val stateStr = if (row.enable == 1) {
            getString(R.string.db_badge_active)
        } else {
            getString(R.string.db_badge_disabled)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.db_delete_confirm_title)
            .setMessage(getString(R.string.db_delete_confirm_msg, appName, row.packageName, stateStr))
            .setPositiveButton(R.string.db_delete_btn) { _, _ ->
                deleteRow(row)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteRow(row: ContinuityDbRow) {
        binding.progressDb.visibility = View.VISIBLE
        binding.btnRefreshDb.isEnabled = false
        binding.btnClearDisabledDb.isEnabled = false

        executor.execute {
            val result = continuityRepo.deleteRawDbRow(requireContext(), row.packageName, row.userId)
            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                result.onSuccess {
                    Toast.makeText(requireContext(), getString(R.string.db_delete_success, row.packageName), Toast.LENGTH_SHORT).show()
                    onDbChanged()
                    loadDbRows()
                }.onFailure { err ->
                    binding.progressDb.visibility = View.GONE
                    binding.btnRefreshDb.isEnabled = true
                    binding.btnClearDisabledDb.isEnabled = true
                    Toast.makeText(requireContext(), "${err.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun confirmClearDisabled() {
        val currentRows = adapter.currentList
        val disabled = currentRows.filter { it.enable == 0 }
        if (disabled.isEmpty()) {
            Toast.makeText(requireContext(), R.string.db_clear_disabled_none, Toast.LENGTH_SHORT).show()
            return
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.db_clear_disabled_title)
            .setMessage(getString(R.string.db_clear_disabled_msg, disabled.size))
            .setPositiveButton(getString(R.string.db_clear_disabled_btn, disabled.size)) { _, _ ->
                clearDisabledRows(disabled)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun clearDisabledRows(disabledRows: List<ContinuityDbRow>) {
        binding.progressDb.visibility = View.VISIBLE
        binding.btnRefreshDb.isEnabled = false
        binding.btnClearDisabledDb.isEnabled = false

        executor.execute {
            var count = 0
            for (row in disabledRows) {
                val res = continuityRepo.deleteRawDbRow(requireContext(), row.packageName, row.userId)
                if (res.isSuccess) count++
            }

            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                Toast.makeText(requireContext(), getString(R.string.db_clear_disabled_success, count), Toast.LENGTH_SHORT).show()
                onDbChanged()
                loadDbRows()
            }
        }
    }

    private class DbRowAdapter(
        private val pm: PackageManager,
        private val onDeleteClick: (ContinuityDbRow) -> Unit
    ) : ListAdapter<ContinuityDbRow, DbRowAdapter.ViewHolder>(DIFF) {

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<ContinuityDbRow>() {
                override fun areItemsTheSame(old: ContinuityDbRow, new: ContinuityDbRow) =
                    old.packageName == new.packageName && old.userId == new.userId

                override fun areContentsTheSame(old: ContinuityDbRow, new: ContinuityDbRow) =
                    old == new
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemContinuityDbRowBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        inner class ViewHolder(private val binding: ItemContinuityDbRowBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(item: ContinuityDbRow) {
                var label = item.packageName
                var icon: Drawable? = null
                try {
                    val appInfo = pm.getApplicationInfo(item.packageName, 0)
                    label = pm.getApplicationLabel(appInfo).toString()
                    icon = pm.getApplicationIcon(appInfo)
                } catch (ignored: Throwable) {}

                binding.textAppLabel.text = label
                binding.textPackageName.text = item.packageName
                if (icon != null) {
                    binding.imgAppIcon.setImageDrawable(icon)
                } else {
                    binding.imgAppIcon.setImageResource(R.drawable.ic_flip_logo)
                }

                if (item.enable == 1) {
                    binding.badgeStatus.setText(R.string.db_badge_active)
                    binding.badgeStatus.setTextColor(Color.parseColor("#4CAF50"))
                } else {
                    binding.badgeStatus.setText(R.string.db_badge_disabled)
                    binding.badgeStatus.setTextColor(Color.parseColor("#9E9E9E"))
                }

                binding.badgeUserId.text = "user: ${item.userId}"

                binding.btnDeleteRow.setOnClickListener {
                    onDeleteClick(item)
                }
            }
        }
    }
}

package com.sergeron.mixflipcontinuity

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.sergeron.mixflipcontinuity.databinding.ActivityMainBinding
import rikka.shizuku.Shizuku
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val SHIZUKU_REQUEST_CODE = 4201
    }

    private lateinit var binding: ActivityMainBinding
    private val continuityRepo = ContinuityRepository()
    private lateinit var coverScreenRepo: CoverScreenRepository
    private val backgroundExecutor = Executors.newFixedThreadPool(4)

    private val allApps = mutableListOf<AppEntry>()
    private val continuityStateMap = mutableMapOf<String, Int>()
    private val coverScreenAllowedSet = mutableSetOf<String>()

    private var currentMode: AdapterMode = AdapterMode.CONTINUITY
    private lateinit var adapter: AppListAdapter

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread {
            updateShizukuStatusUi()
            if (ShizukuShell.hasPermission()) {
                refreshData()
            }
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread {
            updateShizukuStatusUi()
            toast(getString(R.string.shizuku_lost_connection))
        }
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_REQUEST_CODE) {
            runOnUiThread {
                updateShizukuStatusUi()
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    toast(getString(R.string.shizuku_perm_granted))
                    refreshData()
                } else {
                    toast(getString(R.string.shizuku_perm_denied))
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        coverScreenRepo = CoverScreenRepository(this)

        setupEdgeToEdgeInsets()
        setupRecyclerView()
        setupTabs()
        setupListeners()
        setupToolbarMenu()

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)

        updateShizukuStatusUi()
        loadInstalledApps()

        if (OnboardingGuideBottomSheetDialog.shouldShow(this)) {
            showOnboardingGuide()
        }
    }

    override fun onResume() {
        super.onResume()
        updateShizukuStatusUi()
        if (ShizukuShell.hasPermission()) {
            refreshData()
        }
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        backgroundExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun setupEdgeToEdgeInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.appBarLayout.updatePadding(top = systemBars.top)
            binding.recyclerApps.updatePadding(bottom = systemBars.bottom + dpToPx(16))
            insets
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun setupRecyclerView() {
        adapter = AppListAdapter(currentMode) { appEntry, isChecked, mode ->
            handleAppToggle(appEntry, isChecked, mode)
        }
        binding.recyclerApps.layoutManager = LinearLayoutManager(this)
        binding.recyclerApps.adapter = adapter
    }

    private fun setupTabs() {
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> {
                        currentMode = AdapterMode.CONTINUITY
                        binding.cardContinuityHeader.visibility = View.VISIBLE
                        binding.cardCoverHeader.visibility = View.GONE
                        adapter.setMode(AdapterMode.CONTINUITY)
                        filterApps()
                    }
                    1 -> {
                        currentMode = AdapterMode.COVER_SCREEN
                        binding.cardContinuityHeader.visibility = View.GONE
                        binding.cardCoverHeader.visibility = View.VISIBLE
                        adapter.setMode(AdapterMode.COVER_SCREEN)
                        filterApps()
                    }
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupListeners() {
        binding.btnShizukuPermission.setOnClickListener {
            requestShizukuPermission()
        }

        binding.switchGlobal.setOnCheckedChangeListener { buttonView, isChecked ->
            if (buttonView.isPressed) {
                handleGlobalToggle(isChecked)
            }
        }

        binding.swipeRefresh.setOnRefreshListener {
            refreshData()
        }

        binding.editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterApps()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.checkShowSystem.setOnCheckedChangeListener { _, _ ->
            filterApps()
        }

        // Akcje dla ekranu zewnętrznego
        binding.btnEnableAllCover.setOnClickListener {
            handleEnableAllCoverApps()
        }

        binding.btnApplySavedCover.setOnClickListener {
            handleApplySavedCoverApps()
        }

        binding.btnDisableAllCover.setOnClickListener {
            handleDisableAllCoverApps()
        }
    }

    private fun setupToolbarMenu() {
        binding.topAppBar.inflateMenu(R.menu.main_menu)
        binding.topAppBar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_help -> {
                    showOnboardingGuide()
                    true
                }
                R.id.action_db_manager -> {
                    showContinuityDbDialog()
                    true
                }
                else -> false
            }
        }
    }

    private fun showOnboardingGuide() {
        val dialog = OnboardingGuideBottomSheetDialog()
        dialog.show(supportFragmentManager, "OnboardingGuideBottomSheetDialog")
    }

    private fun showContinuityDbDialog() {
        val dialog = ContinuityDbBottomSheetDialog {
            refreshData()
        }
        dialog.show(supportFragmentManager, "ContinuityDbBottomSheetDialog")
    }

    private fun updateShizukuStatusUi() {
        val available = ShizukuShell.isShizukuAvailable()
        val hasPerm = ShizukuShell.hasPermission()

        when {
            !available -> {
                binding.textShizukuStatus.setText(R.string.shizuku_waiting)
                binding.btnShizukuPermission.isEnabled = true
                binding.btnShizukuPermission.setText(R.string.shizuku_connect_refresh)
                binding.switchGlobal.isEnabled = false
                enableCoverScreenButtons(false)
            }
            !hasPerm -> {
                binding.textShizukuStatus.setText(R.string.shizuku_requires_auth)
                binding.btnShizukuPermission.isEnabled = true
                binding.btnShizukuPermission.setText(R.string.shizuku_grant_btn)
                binding.switchGlobal.isEnabled = false
                enableCoverScreenButtons(false)
            }
            else -> {
                binding.textShizukuStatus.setText(R.string.shizuku_active)
                binding.btnShizukuPermission.isEnabled = true
                binding.btnShizukuPermission.setText(R.string.refresh)
                binding.switchGlobal.isEnabled = true
                enableCoverScreenButtons(true)
            }
        }
    }

    private fun enableCoverScreenButtons(enabled: Boolean) {
        binding.btnEnableAllCover.isEnabled = enabled
        binding.btnApplySavedCover.isEnabled = enabled
        binding.btnDisableAllCover.isEnabled = enabled
    }

    private fun requestShizukuPermission() {
        if (!ShizukuShell.isShizukuAvailable()) {
            try {
                rikka.shizuku.ShizukuProvider.requestBinderForNonProviderProcess(this)
            } catch (ignored: Throwable) {}

            if (!ShizukuShell.isShizukuAvailable()) {
                toast(getString(R.string.shizuku_not_available))
                updateShizukuStatusUi()
                return
            }
        }

        if (ShizukuShell.hasPermission()) {
            toast(getString(R.string.shizuku_connected_authorized))
            refreshData()
            return
        }

        try {
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
        } catch (t: Throwable) {
            toast("${t.message}")
        }
    }

    private fun loadInstalledApps() {
        binding.progressBar.visibility = View.VISIBLE

        backgroundExecutor.execute {
            val pm = packageManager
            val installedAppsList = mutableListOf<AppEntry>()

            try {
                val appInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstalledApplications(0)
                }

                val savedCoverAllowed = coverScreenRepo.getSavedAllowedPackages()
                coverScreenAllowedSet.clear()
                coverScreenAllowedSet.addAll(savedCoverAllowed)

                for (info in appInfos) {
                    if (info.packageName == packageName) continue

                    val label = try {
                        pm.getApplicationLabel(info).toString()
                    } catch (t: Throwable) {
                        info.packageName
                    }

                    val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val currentContinuity = continuityStateMap[info.packageName] ?: 0

                    installedAppsList.add(
                        AppEntry(
                            packageName = info.packageName,
                            label = label,
                            icon = null, // Leniwe ładowanie w tle przez AppListAdapter
                            systemApp = isSystem,
                            continuityEnabled = (currentContinuity == 1),
                            coverScreenAllowed = coverScreenAllowedSet.contains(info.packageName)
                        )
                    )
                }

                installedAppsList.sortBy { it.label.lowercase(Locale.getDefault()) }

                runOnUiThread {
                    allApps.clear()
                    allApps.addAll(installedAppsList)
                    binding.progressBar.visibility = View.GONE
                    filterApps()

                    if (ShizukuShell.hasPermission()) {
                        refreshData()
                    }
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    toast(getString(R.string.error_loading_apps, t.message ?: ""))
                }
            }
        }
    }

    private fun refreshData() {
        updateShizukuStatusUi()
        if (!ShizukuShell.hasPermission()) {
            binding.swipeRefresh.isRefreshing = false
            return
        }

        binding.progressBar.visibility = View.VISIBLE

        backgroundExecutor.execute {
            val globalResult = continuityRepo.isGlobalContinuityEnabled()
            val recordsResult = continuityRepo.queryAllRecords()
            val liveCoverResult = coverScreenRepo.queryLiveCoverScreenPackages()
            val savedCoverAllowed = coverScreenRepo.getSavedAllowedPackages()

            runOnUiThread {
                binding.progressBar.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false

                globalResult.onSuccess { enabled ->
                    binding.switchGlobal.setOnCheckedChangeListener(null)
                    binding.switchGlobal.isChecked = enabled
                    binding.switchGlobal.setOnCheckedChangeListener { buttonView, isChecked ->
                        if (buttonView.isPressed) {
                            handleGlobalToggle(isChecked)
                        }
                    }
                }.onFailure { err ->
                    toast("Błąd odczytu globalnego continuity: ${err.message}")
                }

                recordsResult.onSuccess { map ->
                    continuityStateMap.clear()
                    continuityStateMap.putAll(map)
                }.onFailure { err ->
                    toast("Błąd odczytu bazy continuity: ${err.message}")
                }

                val effectiveCoverAllowed = liveCoverResult.getOrDefault(savedCoverAllowed)
                coverScreenAllowedSet.clear()
                coverScreenAllowedSet.addAll(effectiveCoverAllowed)

                for (app in allApps) {
                    val state = continuityStateMap[app.packageName] ?: 0
                    app.continuityEnabled = (state == 1)
                    app.coverScreenAllowed = coverScreenAllowedSet.contains(app.packageName)
                    app.isBusy = false
                }

                filterApps()
            }
        }
    }

    private fun handleGlobalToggle(enabled: Boolean) {
        if (!ShizukuShell.hasPermission()) {
            toast(getString(R.string.shizuku_required_toast))
            binding.switchGlobal.isChecked = !enabled
            return
        }

        binding.switchGlobal.isEnabled = false

        backgroundExecutor.execute {
            val result = continuityRepo.setGlobalContinuityEnabled(enabled)
            runOnUiThread {
                binding.switchGlobal.isEnabled = true
                result.onSuccess {
                    binding.switchGlobal.isChecked = enabled
                    toast(getString(if (enabled) R.string.global_continuity_on else R.string.global_continuity_off))
                }.onFailure { err ->
                    binding.switchGlobal.isChecked = !enabled
                    toast("${err.message}")
                }
            }
        }
    }

    private fun handleAppToggle(appEntry: AppEntry, isChecked: Boolean, mode: AdapterMode) {
        if (!ShizukuShell.hasPermission()) {
            toast(getString(R.string.shizuku_required_toast))
            filterApps()
            return
        }

        appEntry.isBusy = true
        adapter.notifyDataSetChanged()

        backgroundExecutor.execute {
            when (mode) {
                AdapterMode.CONTINUITY -> {
                    val result = continuityRepo.setAppContinuity(appEntry.packageName, isChecked)
                    runOnUiThread {
                        appEntry.isBusy = false
                        result.onSuccess {
                            appEntry.continuityEnabled = isChecked
                            continuityStateMap[appEntry.packageName] = if (isChecked) 1 else 0

                            if (isChecked && !binding.switchGlobal.isChecked) {
                                binding.switchGlobal.setOnCheckedChangeListener(null)
                                binding.switchGlobal.isChecked = true
                                binding.switchGlobal.setOnCheckedChangeListener { buttonView, checked ->
                                    if (buttonView.isPressed) handleGlobalToggle(checked)
                                }
                            }

                            adapter.notifyDataSetChanged()
                            filterApps()
                            val resId = if (isChecked) R.string.continuity_app_enabled else R.string.continuity_app_disabled
                            toast(getString(resId, appEntry.label))
                        }.onFailure { err ->
                            appEntry.continuityEnabled = !isChecked
                            adapter.notifyDataSetChanged()
                            toast("${appEntry.label}: ${err.message}")
                        }
                    }
                }
                AdapterMode.COVER_SCREEN -> {
                    val result = coverScreenRepo.setAppAllowed(appEntry.packageName, isChecked)
                    runOnUiThread {
                        appEntry.isBusy = false
                        result.onSuccess {
                            appEntry.coverScreenAllowed = isChecked
                            if (isChecked) {
                                coverScreenAllowedSet.add(appEntry.packageName)
                            } else {
                                coverScreenAllowedSet.remove(appEntry.packageName)
                            }

                            adapter.notifyDataSetChanged()
                            filterApps()
                            val resId = if (isChecked) R.string.cover_screen_app_enabled else R.string.cover_screen_app_disabled
                            toast(getString(resId, appEntry.label))
                        }.onFailure { err ->
                            appEntry.coverScreenAllowed = !isChecked
                            adapter.notifyDataSetChanged()
                            toast("${appEntry.label}: ${err.message}")
                        }
                    }
                }
            }
        }
    }

    private fun handleEnableAllCoverApps() {
        if (!ShizukuShell.hasPermission()) {
            toast(getString(R.string.shizuku_required_toast))
            return
        }

        val packagesToEnable = allApps.map { it.packageName }
        if (packagesToEnable.isEmpty()) return

        enableCoverScreenButtons(false)
        binding.progressBatchCover.visibility = View.VISIBLE
        binding.textBatchStatus.visibility = View.VISIBLE
        binding.textBatchStatus.text = getString(R.string.batch_enabling, packagesToEnable.size)

        backgroundExecutor.execute {
            val result = coverScreenRepo.setAllAppsAllowed(packagesToEnable, true) { current, total ->
                runOnUiThread {
                    binding.textBatchStatus.text = getString(R.string.batch_enabling_progress, current, total)
                }
            }

            if (binding.checkOptimizeScale.isChecked) {
                coverScreenRepo.applyDefaultAppScales()
            }

            runOnUiThread {
                enableCoverScreenButtons(true)
                binding.progressBatchCover.visibility = View.GONE
                binding.textBatchStatus.visibility = View.GONE

                result.onSuccess { count ->
                    coverScreenAllowedSet.clear()
                    coverScreenAllowedSet.addAll(packagesToEnable)
                    for (app in allApps) {
                        app.coverScreenAllowed = true
                    }
                    adapter.notifyDataSetChanged()
                    filterApps()
                    toast(getString(R.string.batch_enable_success, count))
                }.onFailure { err ->
                    toast("${err.message}")
                }
            }
        }
    }

    private fun handleApplySavedCoverApps() {
        if (!ShizukuShell.hasPermission()) {
            toast(getString(R.string.shizuku_required_toast))
            return
        }

        val savedPackages = coverScreenRepo.getSavedAllowedPackages().toList()
        if (savedPackages.isEmpty()) {
            toast(getString(R.string.batch_apply_saved_empty))
            return
        }

        enableCoverScreenButtons(false)
        binding.progressBatchCover.visibility = View.VISIBLE
        binding.textBatchStatus.visibility = View.VISIBLE
        binding.textBatchStatus.text = getString(R.string.batch_applying_saved, savedPackages.size)

        backgroundExecutor.execute {
            val result = coverScreenRepo.setAllAppsAllowed(savedPackages, true) { current, total ->
                runOnUiThread {
                    binding.textBatchStatus.text = getString(R.string.batch_applying_progress, current, total)
                }
            }

            if (binding.checkOptimizeScale.isChecked) {
                coverScreenRepo.applyDefaultAppScales()
            }

            runOnUiThread {
                enableCoverScreenButtons(true)
                binding.progressBatchCover.visibility = View.GONE
                binding.textBatchStatus.visibility = View.GONE

                result.onSuccess { count ->
                    coverScreenAllowedSet.clear()
                    coverScreenAllowedSet.addAll(savedPackages)
                    for (app in allApps) {
                        app.coverScreenAllowed = coverScreenAllowedSet.contains(app.packageName)
                    }
                    adapter.notifyDataSetChanged()
                    filterApps()
                    toast(getString(R.string.batch_apply_saved_success, count))
                }.onFailure { err ->
                    toast("${err.message}")
                }
            }
        }
    }

    private fun handleDisableAllCoverApps() {
        if (!ShizukuShell.hasPermission()) {
            toast(getString(R.string.shizuku_required_toast))
            return
        }

        val packagesToDisable = allApps.map { it.packageName }
        if (packagesToDisable.isEmpty()) return

        enableCoverScreenButtons(false)
        binding.progressBatchCover.visibility = View.VISIBLE
        binding.textBatchStatus.visibility = View.VISIBLE
        binding.textBatchStatus.setText(R.string.batch_disabling)

        backgroundExecutor.execute {
            val result = coverScreenRepo.setAllAppsAllowed(packagesToDisable, false) { current, total ->
                runOnUiThread {
                    binding.textBatchStatus.text = getString(R.string.batch_disabling_progress, current, total)
                }
            }

            runOnUiThread {
                enableCoverScreenButtons(true)
                binding.progressBatchCover.visibility = View.GONE
                binding.textBatchStatus.visibility = View.GONE

                result.onSuccess { count ->
                    coverScreenAllowedSet.clear()
                    for (app in allApps) {
                        app.coverScreenAllowed = false
                    }
                    adapter.notifyDataSetChanged()
                    filterApps()
                    toast(getString(R.string.batch_disable_success, count))
                }.onFailure { err ->
                    toast("${err.message}")
                }
            }
        }
    }

    private fun filterApps() {
        val query = binding.editSearch.text?.toString()?.trim()?.lowercase(Locale.getDefault()) ?: ""
        val showSystem = binding.checkShowSystem.isChecked

        val filtered = allApps.filter { app ->
            val matchesSystem = showSystem || !app.systemApp
            val matchesSearch = query.isEmpty() ||
                    app.label.lowercase(Locale.getDefault()).contains(query) ||
                    app.packageName.lowercase(Locale.getDefault()).contains(query)
            matchesSystem && matchesSearch
        }

        adapter.submitList(filtered)
        binding.textEmpty.isVisible = filtered.isEmpty() && allApps.isNotEmpty()

        val activeCount = when (currentMode) {
            AdapterMode.CONTINUITY -> filtered.count { it.continuityEnabled }
            AdapterMode.COVER_SCREEN -> filtered.count { it.coverScreenAllowed }
        }

        val countText = when (currentMode) {
            AdapterMode.CONTINUITY -> getString(R.string.app_count_continuity, filtered.size, activeCount)
            AdapterMode.COVER_SCREEN -> getString(R.string.app_count_cover, filtered.size, activeCount)
        }
        binding.textAppCount.text = countText
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

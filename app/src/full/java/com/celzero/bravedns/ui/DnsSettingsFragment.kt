/*
 * Copyright 2022 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.widget.CompoundButton
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.databinding.FragmentDnsConfigureBinding
import com.celzero.bravedns.scheduler.WorkScheduler
import com.celzero.bravedns.scheduler.WorkScheduler.Companion.BLOCKLIST_UPDATE_CHECK_JOB_TAG
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.LoggerConstants
import com.celzero.bravedns.util.UiUtils.fetchColor
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isPlayStoreFlavour
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import java.util.concurrent.TimeUnit

class DnsSettingsFragment :
    Fragment(R.layout.fragment_dns_configure),
    LocalBlocklistsBottomSheet.OnBottomSheetDialogFragmentDismiss {
    private val b by viewBinding(FragmentDnsConfigureBinding::bind)

    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()

    private lateinit var animation: Animation

    companion object {
        fun newInstance() = DnsSettingsFragment()
        private const val REFRESH_TIMEOUT: Long = 4000

        private const val ANIMATION_DURATION = 750L
        private const val ANIMATION_REPEAT_COUNT = -1
        private const val ANIMATION_PIVOT_VALUE = 0.5f
        private const val ANIMATION_START_DEGREE = 0.0f
        private const val ANIMATION_END_DEGREE = 360.0f
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        initObservers()
        initClickListeners()
    }

    override fun onResume() {
        super.onResume()
        // update selected dns values
        updateSelectedDns()
        // update local blocklist ui
        updateLocalBlocklistUi()
    }

    private fun initView() {
        // init animation
        initAnimation()
        // display fav icon in dns logs
        b.dcFaviconSwitch.isChecked = persistentState.fetchFavIcon
        // prevent dns leaks
        b.dcPreventDnsLeaksSwitch.isChecked = persistentState.preventDnsLeaks
        // periodically check for blocklist update
        b.dcCheckUpdateSwitch.isChecked = persistentState.periodicallyCheckBlocklistUpdate
        // use custom download manager
        b.dcDownloaderSwitch.isChecked = persistentState.useCustomDownloadManager
        // enable per-app domain rules (dns alg)
        b.dcAlgSwitch.isChecked = persistentState.enableDnsAlg
        // enable dns caching in tunnel
        b.dcEnableCacheSwitch.isChecked = persistentState.enableDnsCache

        b.connectedStatusTitle.text = getConnectedDnsType()
    }

    private fun updateLocalBlocklistUi() {
        if (isPlayStoreFlavour()) {
            b.dcLocalBlocklistRl.visibility = View.GONE
            return
        }

        if (persistentState.blocklistEnabled) {
            b.dcLocalBlocklistCount.text = getString(R.string.dc_local_block_enabled)
            b.dcLocalBlocklistDesc.text =
                getString(
                    R.string.settings_local_blocklist_in_use,
                    persistentState.numberOfLocalBlocklists.toString()
                )
            b.dcLocalBlocklistCount.setTextColor(
                fetchColor(requireContext(), R.attr.secondaryTextColor)
            )
            return
        }

        b.dcLocalBlocklistCount.setTextColor(fetchColor(requireContext(), R.attr.accentBad))
        b.dcLocalBlocklistCount.text = getString(R.string.lbl_disabled)
    }

    private fun initObservers() {
        observeBraveMode()
        observeAppState()
    }

    private fun observeAppState() {
        VpnController.connectionStatus.observe(viewLifecycleOwner) {
            if (it == BraveVPNService.State.PAUSED) {
                val intent = Intent(requireContext(), PauseActivity::class.java)
                startActivity(intent)
            }
        }

        appConfig.getConnectedDnsObservable().observe(viewLifecycleOwner) {
            updateConnectedStatus(it)
        }
    }

    private fun updateConnectedStatus(connectedDns: String) {
        when (appConfig.getDnsType()) {
            AppConfig.DnsType.DOH -> {
                b.connectedStatusTitleUrl.text =
                    resources.getString(R.string.configure_dns_connected_doh_status)
                b.connectedStatusTitle.text =
                    resources.getString(R.string.configure_dns_connection_name, connectedDns)
            }
            AppConfig.DnsType.DNSCRYPT -> {
                b.connectedStatusTitleUrl.text =
                    resources.getString(R.string.configure_dns_connected_dns_crypt_status)
                b.connectedStatusTitle.text =
                    resources.getString(R.string.configure_dns_connection_name, connectedDns)
            }
            AppConfig.DnsType.DNS_PROXY -> {
                b.connectedStatusTitleUrl.text =
                    resources.getString(R.string.configure_dns_connected_dns_proxy_status)
                b.connectedStatusTitle.text =
                    resources.getString(R.string.configure_dns_connection_name, connectedDns)
            }
            AppConfig.DnsType.RETHINK_REMOTE -> {
                b.connectedStatusTitleUrl.text =
                    resources.getString(R.string.configure_dns_connected_doh_status)
                b.connectedStatusTitle.text =
                    resources.getString(R.string.configure_dns_connection_name, connectedDns)
            }
            AppConfig.DnsType.NETWORK_DNS -> {
                b.connectedStatusTitleUrl.text =
                    resources.getString(R.string.configure_dns_connected_dns_proxy_status)
                b.connectedStatusTitle.text =
                    resources.getString(R.string.configure_dns_connection_name, connectedDns)
            }
        }
    }

    private fun isSystemDns(): Boolean {
        return appConfig.isSystemDns()
    }

    private fun isRethinkDns(): Boolean {
        return appConfig.isRethinkDnsConnected()
    }

    private fun updateSelectedDns() {

        if (isSystemDns()) {
            b.networkDnsRb.isChecked = true
            return
        }

        if (isRethinkDns()) {
            b.rethinkPlusDnsRb.isChecked = true
            return
        }

        // connected to custom dns, update the dns details
        b.customDnsRb.isChecked = true
    }

    private fun getConnectedDnsType(): String {
        return when (appConfig.getDnsType()) {
            AppConfig.DnsType.RETHINK_REMOTE -> {
                getString(R.string.dc_rethink_dns)
            }
            AppConfig.DnsType.DOH -> {
                resources.getString(R.string.dc_doh)
            }
            AppConfig.DnsType.DNSCRYPT -> {
                resources.getString(R.string.dc_dns_crypt)
            }
            AppConfig.DnsType.DNS_PROXY -> {
                resources.getString(R.string.dc_dns_proxy)
            }
            AppConfig.DnsType.NETWORK_DNS -> {
                resources.getString(R.string.dc_dns_proxy)
            }
        }
    }

    private fun observeBraveMode() {
        appConfig.getConnectedDnsObservable().observe(viewLifecycleOwner) { updateSelectedDns() }
    }

    private fun initClickListeners() {

        b.dcLocalBlocklistRl.setOnClickListener { openLocalBlocklist() }

        b.dcLocalBlocklistImg.setOnClickListener { openLocalBlocklist() }

        b.dcCheckUpdateRl.setOnClickListener {
            b.dcCheckUpdateSwitch.isChecked = !b.dcCheckUpdateSwitch.isChecked
        }

        b.dcAlgSwitch.setOnCheckedChangeListener { _: CompoundButton, enabled: Boolean ->
            enableAfterDelay(TimeUnit.SECONDS.toMillis(1), b.dcAlgSwitch)
            persistentState.enableDnsAlg = enabled
        }

        b.dcAlgRl.setOnClickListener { b.dcAlgSwitch.isChecked = !b.dcAlgSwitch.isChecked }

        b.dcCheckUpdateSwitch.setOnCheckedChangeListener { _: CompoundButton, enabled: Boolean ->
            persistentState.periodicallyCheckBlocklistUpdate = enabled
            if (enabled) {
                get<WorkScheduler>().scheduleBlocklistUpdateCheckJob()
            } else {
                Log.i(
                    LoggerConstants.LOG_TAG_SCHEDULER,
                    "Cancel all the work related to blocklist update check"
                )
                WorkManager.getInstance(requireContext().applicationContext)
                    .cancelAllWorkByTag(BLOCKLIST_UPDATE_CHECK_JOB_TAG)
            }
        }

        b.dcFaviconRl.setOnClickListener {
            b.dcFaviconSwitch.isChecked = !b.dcFaviconSwitch.isChecked
        }

        b.dcFaviconSwitch.setOnCheckedChangeListener { _: CompoundButton, enabled: Boolean ->
            enableAfterDelay(TimeUnit.SECONDS.toMillis(1), b.dcFaviconSwitch)
            persistentState.fetchFavIcon = enabled
        }

        b.dcPreventDnsLeaksRl.setOnClickListener {
            b.dcPreventDnsLeaksSwitch.isChecked = !b.dcPreventDnsLeaksSwitch.isChecked
        }

        b.dcPreventDnsLeaksSwitch.setOnCheckedChangeListener { _: CompoundButton, enabled: Boolean
            ->
            enableAfterDelay(TimeUnit.SECONDS.toMillis(1), b.dcPreventDnsLeaksSwitch)
            persistentState.preventDnsLeaks = enabled
        }

        b.rethinkPlusDnsRb.setOnClickListener {
            // rethink dns plus
            invokeRethinkActivity(ConfigureRethinkBasicActivity.FragmentLoader.DB_LIST)
        }

        b.customDnsRb.setOnClickListener {
            // custom dns
            showCustomDns()
        }

        b.networkDnsRb.setOnClickListener {
            // network dns proxy
            setNetworkDns()
        }

        b.dcDownloaderRl.setOnClickListener {
            b.dcDownloaderSwitch.isChecked = !b.dcDownloaderSwitch.isChecked
        }

        b.dcDownloaderSwitch.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            persistentState.useCustomDownloadManager = b
        }

        b.dcEnableCacheRl.setOnClickListener {
            b.dcEnableCacheSwitch.isChecked = !b.dcEnableCacheSwitch.isChecked
        }

        b.dcEnableCacheSwitch.setOnCheckedChangeListener { _, b ->
            persistentState.enableDnsCache = b
        }

        b.dcRefresh.setOnClickListener {
            b.dcRefresh.isEnabled = false
            b.dcRefresh.animation = animation
            b.dcRefresh.startAnimation(animation)
            io { VpnController.refresh() }
            Utilities.delay(REFRESH_TIMEOUT, lifecycleScope) {
                if (isAdded) {
                    b.dcRefresh.isEnabled = true
                    b.dcRefresh.clearAnimation()
                    Utilities.showToastUiCentered(
                        requireContext(),
                        getString(R.string.dc_refresh_toast),
                        Toast.LENGTH_SHORT
                    )
                }
            }
        }
    }

    private fun initAnimation() {
        animation =
            RotateAnimation(
                ANIMATION_START_DEGREE,
                ANIMATION_END_DEGREE,
                Animation.RELATIVE_TO_SELF,
                ANIMATION_PIVOT_VALUE,
                Animation.RELATIVE_TO_SELF,
                ANIMATION_PIVOT_VALUE
            )
        animation.repeatCount = ANIMATION_REPEAT_COUNT
        animation.duration = ANIMATION_DURATION
    }

    // open local blocklist bottom sheet
    private fun openLocalBlocklist() {
        val bottomSheetFragment = LocalBlocklistsBottomSheet()
        bottomSheetFragment.setDismissListener(this)
        bottomSheetFragment.show(parentFragmentManager, bottomSheetFragment.tag)
    }

    private fun invokeRethinkActivity(type: ConfigureRethinkBasicActivity.FragmentLoader) {
        val intent = Intent(requireContext(), ConfigureRethinkBasicActivity::class.java)
        intent.putExtra(ConfigureRethinkBasicActivity.INTENT, type.ordinal)
        requireContext().startActivity(intent)
    }

    private fun showCustomDns() {
        val intent = Intent(requireContext(), DnsListActivity::class.java)
        intent.putExtra(Constants.VIEW_PAGER_SCREEN_TO_LOAD, customDnsScreenToLoad())
        intent.flags = Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        startActivity(intent)
    }

    private fun customDnsScreenToLoad(): Int {
        return when (appConfig.getDnsType()) {
            AppConfig.DnsType.RETHINK_REMOTE -> {
                DnsListActivity.Tabs.DOH.screen
            }
            AppConfig.DnsType.DOH -> {
                DnsListActivity.Tabs.DOH.screen
            }
            AppConfig.DnsType.DNSCRYPT -> {
                DnsListActivity.Tabs.DNSCRYPT.screen
            }
            AppConfig.DnsType.DNS_PROXY -> {
                DnsListActivity.Tabs.DNSPROXY.screen
            }
            AppConfig.DnsType.NETWORK_DNS -> {
                DnsListActivity.Tabs.DOH.screen
            }
        }
    }

    private fun setNetworkDns() {
        // set network dns
        io { appConfig.enableSystemDns() }
    }

    private fun enableAfterDelay(ms: Long, vararg views: View) {
        for (v in views) v.isEnabled = false

        Utilities.delay(ms, lifecycleScope) {
            if (!isAdded) return@delay

            for (v in views) v.isEnabled = true
        }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch { withContext(Dispatchers.IO) { f() } }
    }

    override fun onBtmSheetDismiss() {
        if (!isAdded) return

        updateLocalBlocklistUi()
    }
}

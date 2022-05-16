package com.nll.helper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textview.MaterialTextView
import com.nll.helper.databinding.ActivityMainBinding
import com.nll.helper.recorder.CLog
import com.nll.helper.server.ClientContentProviderHelper
import com.nll.helper.support.AccessibilityCallRecordingService
import com.nll.helper.update.UpdateChecker
import com.nll.helper.update.UpdateResult
import kotlinx.coroutines.launch


class MainActivity : AppCompatActivity() {
    private val logTag = "CR_MainActivity"
    private var askedClientToConnect = false
    private var audioRecordPermissionStatusDefaultTextColor: ColorStateList? = null

    /**
     * Sometimes Android thinks service is cached while it reports enabled.
     * So we let user open the settings to see
     */
    private var openHelperServiceSettingsIfNeededClickCount = 0
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainActivityViewModel by viewModels {
        MainActivityViewModel.Factory(application)
    }

    private val recordAudioPermission = activityResultRegistry.register(
        "audio",
        ActivityResultContracts.RequestPermission()
    ) { hasAudioRecordPermission ->
        if (!hasAudioRecordPermission) {
            Toast.makeText(this, R.string.permission_all_required, Toast.LENGTH_SHORT).show()
        }
        updateAudioPermissionDisplay(hasAudioRecordPermission)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setTitle(R.string.app_name_helper_long)

        binding.installMainAppCardActionButton.isVisible =
            StoreConfigImpl.canLinkToGooglePlayStore()
        binding.webSiteLink.isVisible = StoreConfigImpl.canLinkToWebSite()
        binding.versionInfo.text = Util.getVersionName(this)

        binding.accessibilityServiceStatus.text = String.format(
            "%s (%s)",
            getString(R.string.accessibility_service_name),
            getString(R.string.app_name_helper)
        )
        audioRecordPermissionStatusDefaultTextColor = binding.audioRecordPermissionStatus.textColors

        binding.connectionBetweenAppsStatus.text = String.format(
            "%s ⬌ %s",
            getString(R.string.app_name_helper),
            getString(R.string.app_name)
        )

        viewModel.observeAccessibilityServicesChanges().observe(this) { isEnabled ->
            onAccessibilityChanged(isEnabled)
        }

        viewModel.observeClientConnected().observe(this) { isConnected ->
            CLog.log(logTag, "observeClientConnected() -> isConnected: $isConnected")
            onClientConnected(isConnected)
        }

        binding.enableOngoingNotification.isChecked = AppSettings.actAsForegroundService
        binding.enableOngoingNotification.setOnCheckedChangeListener { buttonView, isChecked ->

            AppSettings.actAsForegroundService = isChecked
            AccessibilityCallRecordingService.start(this)

        }

        binding.accessibilityServiceCardActionButton.setOnClickListener {
            CLog.log(logTag, "accessibilityServiceCardActionButton()")
            val openWithoutCheckingIfEnabled = openHelperServiceSettingsIfNeededClickCount > 0
            val opened = AccessibilityCallRecordingService.openHelperServiceSettingsIfNeeded(
                this,
                openWithoutCheckingIfEnabled
            )
            if (opened) {
                openHelperServiceSettingsIfNeededClickCount = 0
            } else {
                openHelperServiceSettingsIfNeededClickCount++
            }
        }

        binding.installMainAppCardActionButton.setOnClickListener {
            CLog.log(logTag, "installMainAppCardActionButton() -> setOnClickListener")
            StoreConfigImpl.openACRPhoneDownloadLink(
                this,
                ClientContentProviderHelper.clientPackageName
            )
        }

        //Not that there could be only one job per addRepeatingJob
        //MUST BE STARTED as RESUMED creates loop when user manually denied permission from the settings
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CLog.log(logTag, "lifecycleScope() -> STARTED")
                doOnEachStarted()
            }
        }

        lifecycleScope.launch {
            //Update check
            onVersionUpdateResult(UpdateChecker.checkUpdate(this@MainActivity))
        }


    }

    private fun doOnEachStarted() {

        CLog.log(logTag, "doOnEachStarted()")

        if (StoreConfigImpl.requiresProminentPrivacyPolicyDisplay() && !AppSettings.privacyPolicyDisplayed) {
            showPrivacyPolicy()
        } else {

            checkMainApp()
            checkForAudioRecordPermission()
            //checkAccessibilityServiceState()
        }
    }

    private fun showPrivacyPolicy() {
        CLog.log(logTag, "showPrivacyPolicy()")
        with(MaterialAlertDialogBuilder(this))
        {

            val message = String.format(getString(R.string.privacy_policy_warning), StoreConfigImpl.getPrivacyPolicyUrl())
            val textView = MaterialTextView(this@MainActivity).apply {
                val pad = Util.dpToPx(this@MainActivity, 24f).toInt()
                setPadding(pad, pad, pad, pad)
                extSetHTML(message) { urlToOpen ->
                    CLog.log(logTag, "setHML -> Clicked on: $urlToOpen")
                    try {
                        Intent(Intent.ACTION_VIEW, Uri.parse(urlToOpen)).let(context::startActivity)
                    } catch (e: Exception) {
                        CLog.logPrintStackTrace(e)
                        Toast.makeText(this@MainActivity, e.message, Toast.LENGTH_SHORT).show()
                    }
                }

            }
            setCancelable(false)
            setView(textView)
            setPositiveButton(android.R.string.ok) { _, _ ->
                if (CLog.isDebug()) {
                    CLog.log(logTag, "showPrivacyPolicy() -> Accepted. Call doOnEachStarted()")
                }
                AppSettings.privacyPolicyDisplayed = true
                doOnEachStarted()
            }
            setNegativeButton(android.R.string.cancel) { _, _ ->
                if (CLog.isDebug()) {
                    CLog.log(logTag, "showPrivacyPolicy() -> Declined. Close the app")
                }
                finish()
            }
            show()
        }
    }

    //Test
    /*showUpdateMessage(UpdateResult.Required(RemoteAppVersion("{\n" +
            "                                                                          \"versionCode\": 1,\n" +
            "                                                                          \"downloadUrl\": \"https://acr.app/aph.apk\",\n" +
            "                                                                          \"whatsNewMessage\": \"\",\n" +
            "                                                                          \"forceUpdate\": true\n" +
            "                                                                        }"), true))*/
    private fun onVersionUpdateResult(updateResult: UpdateResult) {
        if (CLog.isDebug()) {
            CLog.log(logTag, "onVersionUpdateResult -> updateResult: $updateResult")
        }
        when (updateResult) {
            is UpdateResult.Required -> {
                showUpdateMessage(updateResult)
            }
            is UpdateResult.NotRequired -> {
                if (CLog.isDebug()) {
                    CLog.log(logTag, "onVersionUpdateResult -> NotRequired")
                }
            }
        }
    }

    private fun showUpdateMessage(updateResult: UpdateResult.Required) {
        updateResult.openDownloadUrl(this)
    }

    private fun onAccessibilityChanged(isEnabled: Boolean) {
        CLog.log(logTag, "onAccessibilityChanged -> isEnabled: $isEnabled")

        binding.accessibilityServiceDisabledCard.isVisible = isEnabled.not()
        binding.accessibilityServiceStatus.extSetCompoundDrawablesWithIntrinsicBoundsToRightOrLeft(
            if (isEnabled) {
                R.drawable.ic_green_checked_24dp
            } else {
                R.drawable.ic_red_error_24dp
            }, 8f
        )


        /**
         * Re: lifecycleScope.launch
         * Seems to help with avoiding Skipped 38 frames!  The application may be doing too much work on its main thread.
         */
        lifecycleScope.launch {
            //Make sure we are not in the background to avoid -> Not allowed to start service Intent { cmp=com.nll.cb/.record.support.AccessibilityCallRecordingService }: app is in background
            if (isEnabled && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                //Start Service. We do this here rather then AndroidViewModelObserving because we would have called start service as many times as ViewModel count that extending AndroidViewModelObserving
                AccessibilityCallRecordingService.startHelperServiceIfIsNotRunning(application)
            }
        }
    }

    private fun onClientConnected(isConnected: Boolean) {
        if (!isConnected) {
            CLog.log(logTag, "onClientConnected() -> isConnected is false. Calling checkMainApp()")
            checkMainApp()
        }
        binding.connectionBetweenAppsStatus.extSetCompoundDrawablesWithIntrinsicBoundsToRightOrLeft(
            if (isConnected) {
                R.drawable.ic_green_checked_24dp
            } else {
                R.drawable.ic_red_error_24dp
            }, 8f
        )
    }

    private fun checkMainApp() {
        val clientVersionData = ClientContentProviderHelper.getClientVersionData(this)
        val isAcrPhoneInstalled = clientVersionData != null
        val clientNeedsUpdating = if (isAcrPhoneInstalled) {
            (clientVersionData?.clientVersion ?: -1) < BuildConfig.MINIMUM_CLIENT_VERSION_CODE
        } else {
            false
        }
        CLog.log(
            logTag,
            "checkMainApp() -> clientVersionData: $clientVersionData, clientNeedsUpdating: $clientNeedsUpdating"
        )

        binding.installAcrPhone.isVisible = !isAcrPhoneInstalled
        if (isAcrPhoneInstalled && !askedClientToConnect) {
            //Ask client to connect as it may be in closed state
            ClientContentProviderHelper.askToClientToConnect(this)
            askedClientToConnect = true
        }

        binding.acrPhoneInstallationStatus.extSetCompoundDrawablesWithIntrinsicBoundsToRightOrLeft(
            if (isAcrPhoneInstalled) {
                R.drawable.ic_green_checked_24dp
            } else {
                R.drawable.ic_red_error_24dp
            }, 8f
        )

    }

    private fun checkForAudioRecordPermission() {
        val hasAudioRecordPermission = ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED


        if (!hasAudioRecordPermission) {
            recordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        updateAudioPermissionDisplay(hasAudioRecordPermission)
    }

    private fun updateAudioPermissionDisplay(hasAudioRecordPermission: Boolean) {
        binding.audioRecordPermissionStatus.setTextColor(Color.BLUE)
        if (hasAudioRecordPermission) {
            binding.audioRecordPermissionStatus.setTextColor(
                audioRecordPermissionStatusDefaultTextColor
            )
            binding.audioRecordPermissionStatus.setOnClickListener(null)
        } else {
            binding.audioRecordPermissionStatus.setTextColor(Color.BLUE)
            binding.audioRecordPermissionStatus.setOnClickListener {
                extOpenAppDetailsSettings()
            }
        }
        binding.audioRecordPermissionStatus.extSetCompoundDrawablesWithIntrinsicBoundsToRightOrLeft(
            if (hasAudioRecordPermission) {
                R.drawable.ic_green_checked_24dp
            } else {
                R.drawable.ic_red_error_24dp
            }, 8f
        )


    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        CLog.log(logTag, "onNewIntent() -> intent: $intent")
        onAccessibilityChanged(AccessibilityCallRecordingService.isHelperServiceEnabled(this))
    }

    //Prevent close on back
    override fun onBackPressed() {
        CLog.log(logTag, "onBackPressed()")
        moveTaskToBack(true)
    }
}
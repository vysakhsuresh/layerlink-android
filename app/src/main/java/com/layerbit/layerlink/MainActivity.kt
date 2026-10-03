package com.layerbit.layerlink

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.text.format.DateUtils
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.layerbit.core.R as CoreR
import com.layerbit.core.brand.BrandLinks
import com.layerbit.core.webrtc.CloseReason
import com.layerbit.core.webrtc.QualityProfile
import com.layerbit.core.webrtc.SessionState
import com.layerbit.layerlink.databinding.ActivityMainBinding
import com.layerbit.layerlink.databinding.ItemHowStepBinding
import com.layerbit.layerlink.service.ScreenShareService
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var boundService: ScreenShareService? = null
    private var isBound = false
    private var stateJob: Job? = null

    /** Which of the four moments is on screen; a change animates, a same-moment update doesn't. */
    private enum class Screen { READY, OUTCOME, WAITING, LIVE }

    private var currentScreen: Screen? = null
    private var currentLink: String = ""
    private var pulseAnimator: ObjectAnimator? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            boundService = (service as ScreenShareService.LocalBinder).service
            isBound = true
            observeState()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            isBound = false
            boundService = null
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            startSharing(data)
        } else {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Whether or not it was granted, proceed - the floating Stop control is a nice-to-have,
        // not a requirement for broadcasting.
        launchScreenCapture()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        binding.btnStart.setOnClickListener { requestScreenCapture() }
        binding.btnStop.setOnClickListener { boundService?.stopSharing() }
        binding.btnCopy.setOnClickListener { copyLink() }
        binding.btnCopyMini.setOnClickListener { copyLink() }
        binding.btnShare.setOnClickListener { shareLink() }
        binding.brandFooterInclude.brandFooterRow.setOnClickListener { BrandLinks.openWebsite(this) }
        binding.brandFooterInclude.btnGetHelp.setOnClickListener { BrandLinks.showGetHelpDialog(this) }
        binding.brandFooterInclude.btnBuyCoffee.setOnClickListener { BrandLinks.openCoffee(this) }
        bindStep(binding.step1, 1, R.string.step1_title, R.string.step1_body)
        bindStep(binding.step2, 2, R.string.step2_title, R.string.step2_body)
        bindStep(binding.step3, 3, R.string.step3_title, R.string.step3_body)

        maybeRequestNotificationPermission()
        renderState(SessionState.Idle)
    }

    /**
     * Android 15 forces edge-to-edge on apps targeting SDK 35+, so this window now extends behind
     * the status and navigation bars - which left the header colliding with the clock and clipped
     * the Get Help / Buy me a coffee buttons under the gesture bar. Pad the scroll container by
     * whatever the system bars (plus any display cutout) actually occupy, rather than guessing a
     * fixed margin: clipToPadding="false" on the NestedScrollView keeps content scrolling under
     * the bars instead of stopping short of them.
     */
    private fun applyWindowInsets() {
        val scroll = binding.root
        val baseLeft = scroll.paddingLeft
        val baseTop = scroll.paddingTop
        val baseRight = scroll.paddingRight
        val baseBottom = scroll.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                baseLeft + bars.left,
                baseTop + bars.top,
                baseRight + bars.right,
                baseBottom + bars.bottom,
            )
            WindowInsetsCompat.CONSUMED
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, ScreenShareService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        stateJob?.cancel()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        binding.liveClock.stop()
        pulseAnimator?.cancel()
    }

    private fun bindStep(step: ItemHowStepBinding, number: Int, @StringRes title: Int, @StringRes body: Int) {
        step.stepNumber.text = number.toString()
        step.stepTitle.setText(title)
        step.stepBody.setText(body)
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestScreenCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.overlay_permission_rationale, Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            overlayPermissionLauncher.launch(intent)
            return
        }
        launchScreenCapture()
    }

    private fun launchScreenCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun startSharing(resultData: Intent) {
        val qualityProfile = if (binding.switchDataSaver.isChecked) {
            QualityProfile.DATA_SAVER
        } else {
            QualityProfile.HIGH
        }
        val intent = Intent(this, ScreenShareService::class.java).apply {
            action = ScreenShareService.ACTION_START
            putExtra(ScreenShareService.EXTRA_RESULT_CODE, Activity.RESULT_OK)
            putExtra(ScreenShareService.EXTRA_RESULT_DATA, resultData)
            putExtra(ScreenShareService.EXTRA_QUALITY_PROFILE, qualityProfile.name)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun observeState() {
        stateJob?.cancel()
        val service = boundService ?: return
        stateJob = lifecycleScope.launch {
            service.state.collect { state -> renderState(state) }
        }
    }

    private fun renderState(state: SessionState) {
        val screen = when (state) {
            is SessionState.Idle -> Screen.READY
            is SessionState.Closed ->
                if (state.reason == CloseReason.CAPTURE_STOPPED) Screen.READY else Screen.OUTCOME
            is SessionState.Error -> Screen.OUTCOME
            is SessionState.Requesting, is SessionState.Waiting -> Screen.WAITING
            is SessionState.Reconnecting -> if (state.connectedAtMillis != null) Screen.LIVE else Screen.WAITING
            is SessionState.Live -> Screen.LIVE
        }
        // Animate only when the moment changes. The reconnect countdown re-renders every
        // second, and that must not replay a transition each tick.
        if (screen != currentScreen && currentScreen != null) {
            TransitionManager.beginDelayedTransition(binding.contentRoot, AutoTransition().setDuration(180))
        }
        currentScreen = screen

        renderStatusPill(state)

        binding.outcomeCard.isVisible = screen == Screen.OUTCOME
        binding.dataSaverTile.isVisible = screen == Screen.READY || screen == Screen.OUTCOME
        binding.btnStart.isVisible = screen == Screen.READY || screen == Screen.OUTCOME
        binding.howItWorks.isVisible = screen == Screen.READY
        binding.brandFooterInclude.root.isVisible = screen == Screen.READY || screen == Screen.OUTCOME

        val link = when (state) {
            is SessionState.Waiting -> state.viewerUrl
            is SessionState.Reconnecting -> state.viewerUrl
            is SessionState.Live -> state.viewerUrl
            else -> null
        }
        if (link != null && link != currentLink) {
            currentLink = link
            binding.shareLinkText.text = link
            binding.miniLinkText.text = link
        }
        val sessionId = when (state) {
            is SessionState.Waiting -> state.sessionId
            is SessionState.Reconnecting -> state.sessionId
            is SessionState.Live -> state.sessionId
            else -> null
        }

        binding.shareCard.isVisible = screen == Screen.WAITING && link != null
        binding.factsCard.isVisible = (screen == Screen.WAITING || screen == Screen.LIVE) && sessionId != null
        binding.liveClockBlock.isVisible = screen == Screen.LIVE
        binding.miniLinkRow.isVisible = screen == Screen.LIVE
        binding.btnStop.isVisible = screen == Screen.WAITING || screen == Screen.LIVE

        if (sessionId != null) renderFacts(state, sessionId)
        renderLiveClock(state)
        if (screen == Screen.OUTCOME) renderOutcome(state)

        val failed = (state as? SessionState.Closed)?.reason == CloseReason.NEVER_CONNECTED ||
            state is SessionState.Error
        binding.btnStart.setText(if (failed) R.string.btn_try_again else R.string.btn_start)
        binding.btnStart.setIconResource(if (failed) R.drawable.ic_retry else R.drawable.ic_broadcast)
        binding.startHint.isVisible = (state as? SessionState.Closed)?.reason == CloseReason.NEVER_CONNECTED
    }

    private fun renderStatusPill(state: SessionState) {
        val (text, colorRes, pulsing) = when (state) {
            is SessionState.Idle -> Triple(getString(R.string.status_idle), CoreR.color.status_idle, false)
            is SessionState.Requesting -> Triple(getString(R.string.status_requesting), CoreR.color.status_waiting, true)
            is SessionState.Waiting -> Triple(getString(R.string.status_waiting), CoreR.color.status_waiting, true)
            is SessionState.Reconnecting -> Triple(
                getString(
                    if (state.connectedAtMillis != null) R.string.status_reconnecting else R.string.status_connecting,
                    state.secondsRemaining
                ),
                CoreR.color.status_waiting,
                true
            )
            is SessionState.Live -> Triple(getString(R.string.status_live), CoreR.color.status_live, true)
            is SessionState.Closed -> when (state.reason) {
                CloseReason.NEVER_CONNECTED -> Triple(getString(R.string.status_closed_never_connected), CoreR.color.status_error, false)
                CloseReason.CONNECTION_ENDED -> Triple(getString(R.string.status_closed), CoreR.color.status_idle, false)
                CloseReason.CAPTURE_STOPPED -> Triple(getString(R.string.status_idle), CoreR.color.status_idle, false)
            }
            is SessionState.Error -> Triple(getString(R.string.status_error), CoreR.color.status_error, false)
        }
        val color = ContextCompat.getColor(this, colorRes)
        binding.statusText.text = text
        binding.statusText.setTextColor(color)
        binding.statusDot.backgroundTintList = ColorStateList.valueOf(color)
        // Ready sits on the plain card surface; every other state gets a faint wash of its colour.
        binding.statusPill.backgroundTintList = ColorStateList.valueOf(
            if (colorRes == CoreR.color.status_idle) {
                ContextCompat.getColor(this, CoreR.color.surface_dark)
            } else {
                ColorUtils.setAlphaComponent(color, 0x24)
            }
        )
        setDotPulsing(pulsing)
    }

    // A slow fade on the status dot while something is in progress. ObjectAnimator follows the
    // system animator scale, so with "Remove animations" on, the dot just stays solid.
    private fun setDotPulsing(pulsing: Boolean) {
        if (pulsing) {
            if (pulseAnimator?.isRunning == true) return
            pulseAnimator = ObjectAnimator.ofFloat(binding.statusDot, View.ALPHA, 1f, 0.25f).apply {
                duration = 850
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        } else {
            pulseAnimator?.cancel()
            pulseAnimator = null
            binding.statusDot.alpha = 1f
        }
    }

    private fun renderFacts(state: SessionState, sessionId: String) {
        binding.factSession.text = sessionId
        val quality = boundService?.currentQuality
            ?: if (binding.switchDataSaver.isChecked) QualityProfile.DATA_SAVER else QualityProfile.HIGH
        binding.factQuality.setText(
            if (quality == QualityProfile.DATA_SAVER) R.string.quality_data_saver else R.string.quality_high
        )
        val (viewerText, viewerColor) = when (state) {
            is SessionState.Live -> R.string.viewer_connected to CoreR.color.status_live
            is SessionState.Reconnecting ->
                (if (state.connectedAtMillis != null) R.string.viewer_reconnecting else R.string.viewer_connecting) to
                    CoreR.color.status_waiting
            else -> R.string.viewer_not_connected to CoreR.color.text_muted
        }
        binding.factViewer.setText(viewerText)
        binding.factViewer.setTextColor(ContextCompat.getColor(this, viewerColor))
    }

    private fun renderLiveClock(state: SessionState) {
        val connectedAt = when (state) {
            is SessionState.Live -> state.connectedAtMillis
            is SessionState.Reconnecting -> state.connectedAtMillis
            else -> null
        }
        if (connectedAt != null) {
            // Chronometer counts from an elapsedRealtime() base, the same clock the session
            // stamped, so the time is right even after the activity was in the background.
            if (binding.liveClock.base != connectedAt) binding.liveClock.base = connectedAt
            binding.liveClock.start()
        } else {
            binding.liveClock.stop()
        }
    }

    private fun renderOutcome(state: SessionState) {
        val error = ContextCompat.getColor(this, CoreR.color.status_error_soft)
        val muted = ContextCompat.getColor(this, CoreR.color.text_muted)
        when {
            state is SessionState.Closed && state.reason == CloseReason.NEVER_CONNECTED -> showOutcome(
                R.drawable.ic_wifi_off, error, getString(R.string.outcome_never_connected_title),
                getString(R.string.outcome_never_connected_body), R.drawable.outcome_error_background
            )
            state is SessionState.Closed -> showOutcome(
                R.drawable.ic_broadcast, muted, getString(R.string.outcome_ended_title),
                getString(R.string.outcome_ended_body, DateUtils.formatElapsedTime(state.liveDurationMillis / 1000)),
                CoreR.drawable.card_background
            )
            state is SessionState.Error -> showOutcome(
                R.drawable.ic_error_outline, error, getString(R.string.outcome_error_title),
                state.message, R.drawable.outcome_error_background
            )
        }
    }

    private fun showOutcome(
        @DrawableRes icon: Int,
        iconColor: Int,
        title: String,
        body: String,
        @DrawableRes background: Int
    ) {
        binding.outcomeIcon.setImageResource(icon)
        binding.outcomeIcon.imageTintList = ColorStateList.valueOf(iconColor)
        binding.outcomeTitle.text = title
        binding.outcomeBody.text = body
        binding.outcomeCard.setBackgroundResource(background)
    }

    private fun copyLink() {
        val link = currentLink
        if (link.isEmpty()) return
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), link))
        Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareLink() {
        val link = currentLink
        if (link.isEmpty()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, link)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_link_title)))
    }
}

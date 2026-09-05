package com.layerbit.layerlink

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.layerbit.core.brand.BrandLinks
import com.layerbit.core.webrtc.SessionState
import com.layerbit.layerlink.databinding.ActivityMainBinding
import com.layerbit.layerlink.service.ScreenShareService
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var boundService: ScreenShareService? = null
    private var isBound = false
    private var stateJob: Job? = null
    private var previewInitialized = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            boundService = (service as ScreenShareService.LocalBinder).service
            isBound = true
            attachPreview()
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

        binding.btnStart.setOnClickListener { requestScreenCapture() }
        binding.btnStop.setOnClickListener { boundService?.stopSharing() }
        binding.btnCopy.setOnClickListener { copyLink() }
        binding.btnShare.setOnClickListener { shareLink() }
        binding.brandFooterInclude.brandFooterRow.setOnClickListener { BrandLinks.openWebsite(this) }
        binding.brandFooterInclude.btnGetHelp.setOnClickListener { BrandLinks.showGetHelpDialog(this) }
        binding.brandFooterInclude.btnBuyCoffee.setOnClickListener { BrandLinks.openCoffee(this) }

        maybeRequestNotificationPermission()
        renderState(SessionState.Idle)
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, ScreenShareService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        stateJob?.cancel()
        if (isBound) {
            boundService?.detachRenderer(binding.previewRenderer)
            unbindService(connection)
            isBound = false
        }
        if (previewInitialized) {
            binding.previewRenderer.release()
            previewInitialized = false
        }
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
        val intent = Intent(this, ScreenShareService::class.java).apply {
            action = ScreenShareService.ACTION_START
            putExtra(ScreenShareService.EXTRA_RESULT_CODE, Activity.RESULT_OK)
            putExtra(ScreenShareService.EXTRA_RESULT_DATA, resultData)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun attachPreview() {
        val eglContext = boundService?.eglBaseContext ?: return
        if (!previewInitialized) {
            binding.previewRenderer.init(eglContext, null)
            binding.previewRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            binding.previewRenderer.setMirror(false)
            previewInitialized = true
        }
        boundService?.attachRenderer(binding.previewRenderer)
    }

    private fun observeState() {
        stateJob?.cancel()
        val service = boundService ?: return
        stateJob = lifecycleScope.launch {
            service.state.collect { state -> renderState(state) }
        }
    }

    private fun renderState(state: SessionState) {
        binding.statusBadge.text = when (state) {
            is SessionState.Idle -> getString(R.string.status_idle)
            is SessionState.Requesting -> getString(R.string.status_requesting)
            is SessionState.Waiting -> getString(R.string.status_waiting)
            is SessionState.Live -> getString(R.string.status_live)
            is SessionState.Closed -> getString(R.string.status_closed)
            is SessionState.Error -> getString(R.string.status_error, state.message)
        }

        val link = (state as? SessionState.Waiting)?.viewerUrl
            ?: (state as? SessionState.Live)?.viewerUrl
        if (link != null) {
            binding.shareLinkInput.setText(link)
        }

        binding.btnStart.isVisible = state is SessionState.Idle || state is SessionState.Closed || state is SessionState.Error
        binding.btnStop.isVisible = state is SessionState.Requesting || state is SessionState.Waiting || state is SessionState.Live
        binding.linkContainer.isVisible = state is SessionState.Waiting || state is SessionState.Live
        binding.previewCard.isVisible = state is SessionState.Waiting || state is SessionState.Live
        binding.howToUseCard.isVisible = state is SessionState.Idle || state is SessionState.Closed || state is SessionState.Error
    }

    private fun copyLink() {
        val link = binding.shareLinkInput.text?.toString().orEmpty()
        if (link.isEmpty()) return
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), link))
        Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareLink() {
        val link = binding.shareLinkInput.text?.toString().orEmpty()
        if (link.isEmpty()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, link)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_link_title)))
    }
}

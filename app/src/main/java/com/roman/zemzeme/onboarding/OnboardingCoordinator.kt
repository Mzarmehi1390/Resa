package com.roman.zemzeme.onboarding

import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Coordinates the complete onboarding flow including permission explanation,
 * permission requests, and initialization of the mesh service.
 *
 * The permission explanation screen now handles all permissions inline
 * (including background location and battery optimization) via sequential
 * per-category requests. This coordinator provides the launchers and
 * completion logic.
 */
class OnboardingCoordinator(
    private val activity: ComponentActivity,
    private val permissionManager: PermissionManager,
    private val onOnboardingComplete: () -> Unit,
    private val onOnboardingFailed: (String) -> Unit
) {

    companion object {
        private const val TAG = "OnboardingCoordinator"
    }

    private var permissionLauncher: ActivityResultLauncher<Array<String>>? = null
    private var backgroundLocationLauncher: ActivityResultLauncher<String>? = null

    init {
        setupPermissionLauncher()
        setupBackgroundLocationLauncher()
    }

    private fun setupPermissionLauncher() {
        permissionLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            handlePermissionResults(permissions)
        }
    }

    private fun setupBackgroundLocationLauncher() {
        backgroundLocationLauncher = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            handleBackgroundLocationResult(granted)
        }
    }

    /**
     * Start the onboarding process — called when user clicks "Continue"
     * after all required permissions are granted.
     */
    fun startOnboarding() {
        Log.d(TAG, "Starting onboarding process")
        permissionManager.logPermissionStatus()

        if (permissionManager.areRequiredPermissionsGranted()) {
            Log.d(TAG, "Required permissions granted, completing onboarding")
            completeOnboarding()
        } else {
            Log.d(TAG, "Missing permissions, need to start explanation flow")
        }
    }

    /**
     * Request specific permissions for a single category.
     * Called by the permission explanation screen during sequential granting.
     */
    fun requestSpecificPermissions(permissions: List<String>) {
        val missing = permissions.filter { !permissionManager.isPermissionGranted(it) }
        if (missing.isEmpty()) return
        Log.d(TAG, "Requesting specific permissions: $missing")
        permissionLauncher?.launch(missing.toTypedArray())
    }

    /**
     * Request background location permission.
     */
    fun requestBackgroundLocation() {
        val permission = permissionManager.getBackgroundLocationPermission()
        if (permission == null) {
            Log.d(TAG, "Background location not needed on this API level")
            return
        }
        Log.d(TAG, "Requesting background location permission")
        backgroundLocationLauncher?.launch(permission)
    }

    /**
     * Permission results are handled by the explanation screen via lifecycle
     * observer. We just log here.
     */
    private fun handlePermissionResults(permissions: Map<String, Boolean>) {
        Log.d(TAG, "Received permission results:")
        permissions.forEach { (permission, granted) ->
            Log.d(TAG, "  $permission: ${if (granted) "GRANTED" else "DENIED"}")
        }
    }

    private fun handleBackgroundLocationResult(granted: Boolean) {
        BackgroundLocationPreferenceManager.setSkipped(activity, true)
        Log.d(TAG, "Background location permission ${if (granted) "granted" else "denied"}")
    }

    /**
     * Complete the onboarding process and initialize the app
     */
    private fun completeOnboarding() {
        Log.d(TAG, "Completing onboarding process")
        permissionManager.markOnboardingComplete()
        permissionManager.logPermissionStatus()

        activity.lifecycleScope.launch {
            kotlinx.coroutines.delay(100)
            onOnboardingComplete()
        }
    }

    /**
     * Open app settings for manual permission management
     */
    fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", activity.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
            Log.d(TAG, "Opened app settings for manual permission management")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app settings", e)
        }
    }

    fun getDiagnostics(): String {
        return buildString {
            appendLine("Onboarding Coordinator Diagnostics:")
            appendLine("Activity: ${activity::class.simpleName}")
            appendLine("Permission launcher: ${permissionLauncher != null}")
            appendLine()
            append(permissionManager.getPermissionDiagnostics())
        }
    }
}

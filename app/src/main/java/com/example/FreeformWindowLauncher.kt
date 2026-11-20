package com.example

import android.app.ActivityOptions
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import java.lang.ref.WeakReference

/**
 * Launches external phone apps in Android Freeform Multi-Window mode (WINDOWING_MODE_FREEFORM = 5)
 * with centered bounded Rect coordinates so the system window frame supports moving by top bar
 * and resizing from corners when freeform support is enabled on the device.
 */
object FreeformWindowLauncher {

    const val WINDOWING_MODE_FREEFORM = 5
    private const val PREF_FREEFORM_PROMPT_MUTED = "freeform_prompt_muted"
    private const val TAG = "FreeformLauncher"

    internal var activeGuideDialogRef: WeakReference<AlertDialog>? = null
    internal var promptShownCountForTest: Int = 0

    fun computeCenteredFreeformBounds(context: Context): Rect {
        val metrics = context.resources.displayMetrics
        val screenW = metrics.widthPixels.coerceAtLeast(360)
        val screenH = metrics.heightPixels.coerceAtLeast(480)
        val density = metrics.density.coerceAtLeast(1f)

        val minW = (240 * density).toInt().coerceAtMost((screenW - 32).coerceAtLeast(200))
        val minH = (280 * density).toInt().coerceAtMost((screenH - 64).coerceAtLeast(240))
        val winW = (screenW * 0.78f).toInt().coerceIn(minW, (screenW - 32).coerceAtLeast(minW))
        val winH = (screenH * 0.62f).toInt().coerceIn(minH, (screenH - 64).coerceAtLeast(minH))

        val left = ((screenW - winW) / 2).coerceAtLeast(16)
        val top = ((screenH - winH) / 2).coerceAtLeast(48)
        val right = (left + winW).coerceAtMost(screenW - 16)
        val bottom = (top + winH).coerceAtMost(screenH - 32)
        return Rect(left, top, right, bottom)
    }

    fun isFreeformMultiWindowEnabled(context: Context): Boolean {
        val hasFeature = runCatching {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)
        }.getOrDefault(false)
        val devFreeformEnabled = runCatching {
            Settings.Global.getInt(context.contentResolver, "enable_freeform_support", 0) == 1
        }.getOrDefault(false)
        return hasFeature || devFreeformEnabled
    }

    fun prepareFreeformIntent(rawIntent: Intent): Intent {
        return Intent(rawIntent).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }
    }

    fun buildFreeformActivityOptions(context: Context): ActivityOptions {
        val options = ActivityOptions.makeBasic()
        options.setLaunchBounds(computeCenteredFreeformBounds(context))
        applyLaunchWindowingMode(options, WINDOWING_MODE_FREEFORM)
        return options
    }

    fun applyLaunchWindowingMode(options: ActivityOptions, mode: Int): Boolean {
        return runCatching {
            val method = ActivityOptions::class.java.getMethod(
                "setLaunchWindowingMode",
                Int::class.javaPrimitiveType
            )
            method.invoke(options, mode)
            true
        }.getOrElse { e ->
            Log.w(TAG, "setLaunchWindowingMode($mode) unavailable on this runtime", e)
            false
        }
    }

    fun launchInFreeformWindow(
        context: Context,
        rawIntent: Intent,
        appLabel: String? = null
    ): Boolean {
        val preparedIntent = prepareFreeformIntent(rawIntent)
        val options = buildFreeformActivityOptions(context)

        val launched = try {
            context.startActivity(preparedIntent, options.toBundle())
            true
        } catch (e: Exception) {
            Log.w(TAG, "Freeform startActivity with options failed, falling back", e)
            try {
                context.startActivity(preparedIntent)
                true
            } catch (fallbackEx: Exception) {
                Log.e(TAG, "Failed launching ${appLabel ?: "app"}", fallbackEx)
                false
            }
        }

        if (launched && !isFreeformMultiWindowEnabled(context)) {
            showFreeformEnablePrompt(context)
        }
        return launched
    }

    fun showFreeformEnablePrompt(context: Context) {
        promptShownCountForTest++
        val prefs = context.getSharedPreferences("smart_dot_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_FREEFORM_PROMPT_MUTED, false)) {
            Toast.makeText(
                context,
                context.getString(R.string.freeform_enable_toast),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (!Settings.canDrawOverlays(context)) {
            Toast.makeText(
                context,
                context.getString(R.string.freeform_enable_toast),
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val previous = activeGuideDialogRef?.get()
        if (previous?.isShowing == true) {
            return
        }

        try {
            val builder = AlertDialog.Builder(
                context,
                android.R.style.Theme_DeviceDefault_Dialog_Alert
            )
                .setTitle(R.string.freeform_prompt_title)
                .setMessage(R.string.freeform_prompt_message)
                .setPositiveButton(R.string.freeform_prompt_open_dev_options) { dialog, _ ->
                    dialog.dismiss()
                    openDeveloperOptions(context)
                }
                .setNeutralButton(R.string.freeform_prompt_dont_ask) { dialog, _ ->
                    prefs.edit().putBoolean(PREF_FREEFORM_PROMPT_MUTED, true).apply()
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.ok) { dialog, _ ->
                    dialog.dismiss()
                }

            val alert = builder.create()
            val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            alert.window?.setType(windowType)
            alert.show()
            activeGuideDialogRef = WeakReference(alert)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to show freeform overlay prompt dialog; falling back to Toast", e)
            Toast.makeText(
                context,
                context.getString(R.string.freeform_enable_toast),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun openDeveloperOptions(context: Context) {
        val devIntent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(devIntent)
        } catch (_: Exception) {
            val settingsIntent = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(settingsIntent) }
            Toast.makeText(
                context,
                context.getString(R.string.freeform_unlock_dev_options_toast),
                Toast.LENGTH_LONG
            ).show()
        }
    }
}

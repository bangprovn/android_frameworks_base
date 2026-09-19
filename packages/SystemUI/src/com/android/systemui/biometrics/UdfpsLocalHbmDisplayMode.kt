/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.biometrics

import android.content.Context
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import android.view.Display
import com.android.systemui.res.R
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executor

private const val TAG = "UdfpsLocalHbm"

/**
 * How long to wait for the panel to leave a low-power state before writing the control node. The
 * kernel rejects the write with EINVAL while the panel is not initialized, and on a panel that has
 * just been woken from AOD the command set only lands once the display is driving frames again.
 */
private const val PANEL_READY_TIMEOUT_MS = 300L

/** Poll interval while waiting for [PANEL_READY_TIMEOUT_MS]. */
private const val PANEL_READY_POLL_MS = 5L

/** Attempts of the "on" write, in case the panel is a few milliseconds behind. */
private const val ENABLE_WRITE_ATTEMPTS = 3

/** Delay between two "on" write attempts. */
private const val ENABLE_WRITE_RETRY_MS = 20L

/**
 * A [UdfpsDisplayModeProvider] for panels whose *local* high-brightness mode (LHBM) is driven by the
 * kernel through a sysfs node, rather than by the framework drawing a bright circle over the sensor.
 *
 * It wraps the platform [UdfpsDisplayMode] - the refresh rate vote is unchanged and still happens
 * first - and adds the panel write that actually illuminates the finger:
 * * [enable] writes the "on" value to the control node, then invokes `onEnabled`, which is what
 *   releases `UDFPS_UI_READY` to the fingerprint HAL.
 * * [disable] writes the authentication result to the result node (if configured) and then the
 *   "off" value to the control node.
 *
 * The write is done on a background executor: the sysfs store handler runs the DSI command set
 * synchronously and the panel may first have to be waited for, neither of which may happen on the
 * UI thread. `onEnabled` is posted back to the main executor, as [UdfpsDisplayModeProvider]
 * requires.
 *
 * Everything is inert unless `config_udfpsLocalHbmControlPath` is non-empty; see
 * [wrapIfConfigured].
 */
class UdfpsLocalHbmDisplayMode(
    private val context: Context,
    private val delegate: UdfpsDisplayModeProvider,
    private val config: Config,
    private val bgExecutor: Executor,
    private val mainExecutor: Executor,
) : UdfpsDisplayModeProvider {

    /** Paths and values of the panel's LHBM interface. All paths may be empty except [controlPath]. */
    data class Config(
        /** Node that turns LHBM on and off. */
        val controlPath: String,
        /** Value that turns LHBM on, written as ASCII with no trailing newline. */
        val onValue: String,
        /** Value that turns LHBM off. */
        val offValue: String,
        /** Read-back node, logged after the write. Empty to skip. */
        val statusPath: String,
        /** Value [statusPath] is expected to read once LHBM is on. Empty to skip the check. */
        val statusReadyValue: String,
        /** Node that takes the authentication result before LHBM is turned off. Empty to skip. */
        val authResultPath: String,
        /** Node reporting the panel power mode, polled while the display is dozing. Empty to skip. */
        val powerModePath: String,
        /** Values of [powerModePath] that mean the panel is ready for the control write. */
        val powerModeReadyValues: List<String>,
        /**
         * Touch driver node that switches the panel's finger-on-display reporting on and off
         * for the lifetime of a fingerprint client (overlay shown to overlay hidden). Empty to
         * skip. Written "1" / "0", as ASCII with no newline.
         */
        val touchFodPath: String = "",
    )

    /** Incremented for every [enable]; main thread only. */
    private var nextRequestId = 0

    /** Id of the request the panel currently belongs to, or 0 if none. */
    @Volatile private var activeRequestId = 0

    /** True between a successful "on" write and the matching "off" write. */
    @Volatile private var illuminated = false

    /** Serializes the node writes; never taken on the UI thread. */
    private val nodeLock = Any()

    /**
     * The overlay is up: a fingerprint client (authentication or enrolment) is listening.
     * Mirrors the stock framework, which writes the touch driver's fod_en node "1" on client
     * start and "0" on client stop: with it off, the touch controller reports a finger held on
     * the sensor as a train of short presses, and the HAL - which reads the touch panel itself -
     * takes each release as finger-up and abandons the capture.
     */
    fun onOverlayShown() {
        if (config.touchFodPath.isEmpty()) {
            return
        }
        bgExecutor.execute { synchronized(nodeLock) { writeNode(config.touchFodPath, "1") } }
    }

    /** The overlay is gone: no client is listening any more. */
    fun onOverlayHidden() {
        if (config.touchFodPath.isEmpty()) {
            return
        }
        bgExecutor.execute { synchronized(nodeLock) { writeNode(config.touchFodPath, "0") } }
    }

    override fun enable(onEnabled: Runnable?) {
        // The refresh rate vote is the platform behaviour and must keep happening first. If the
        // platform implementation declines the request (already enabled, no callback registered) it
        // does not run onEnabled either, so neither do we.
        var accepted = false
        delegate.enable { accepted = true }
        if (!accepted) {
            Log.w(TAG, "enable | platform display mode declined the request")
            return
        }

        Trace.beginSection("UdfpsLocalHbmDisplayMode.enable")
        val requestId = ++nextRequestId
        activeRequestId = requestId
        bgExecutor.execute { enableOnBackgroundThread(requestId, onEnabled) }
        Trace.endSection()
    }

    override fun disable(onDisabled: Runnable?) {
        val hadRequest = activeRequestId != 0
        // Cancel any enable that has not written yet. Everything below is idempotent, so this also
        // covers the exit paths that do not follow a finger-up: a good acquisition, an
        // authentication result, a cancelled request, the screen turning off.
        activeRequestId = 0

        var accepted = false
        delegate.disable { accepted = true }
        if (!accepted) {
            Log.v(TAG, "disable | platform display mode was already disabled")
        }

        if (hadRequest || illuminated) {
            bgExecutor.execute { disableOnBackgroundThread() }
        }

        onDisabled?.run()
    }

    private fun enableOnBackgroundThread(requestId: Int, onEnabled: Runnable?) {
        if (activeRequestId != requestId) {
            Log.v(TAG, "enable | request $requestId was cancelled before the panel write")
            return
        }

        awaitPanelReady(requestId)
        setLocalHbm(true, requestId)

        if (activeRequestId == requestId) {
            // Release UDFPS_UI_READY even if the write failed: the HAL polls the panel state on its
            // own and a stalled authentication is worse than one that cannot capture.
            mainExecutor.execute { onEnabled?.run() }
        } else {
            // Cancelled while we were writing. Nothing will call disable() for this request any
            // more, so undo the write here or the panel keeps a bright spot.
            Log.v(TAG, "enable | request $requestId cancelled during the write, reverting")
            setLocalHbm(false, 0)
        }
    }

    private fun disableOnBackgroundThread() {
        if (config.authResultPath.isNotEmpty() && illuminated) {
            // The stock implementation reports whether the fingerprint matched. Nothing at this
            // seam knows the authentication result, so always report "not authenticated" - the
            // node only feeds the panel's own release path.
            writeNode(config.authResultPath, config.offValue)
        }
        setLocalHbm(false, 0)
    }

    /**
     * Blocks until the panel can take the control write, for at most [PANEL_READY_TIMEOUT_MS].
     *
     * Only relevant while the display is dozing: the write fails with EINVAL until the panel is
     * initialized, and the command set only reaches the panel once the display is driving frames.
     * When the display is fully on this returns immediately.
     */
    private fun awaitPanelReady(requestId: Int) {
        if (config.powerModePath.isEmpty() || config.powerModeReadyValues.isEmpty()) {
            return
        }
        val displayState = context.display?.state ?: Display.STATE_UNKNOWN
        if (displayState == Display.STATE_ON) {
            return
        }

        val deadline = SystemClock.uptimeMillis() + PANEL_READY_TIMEOUT_MS
        var powerMode: String? = null
        while (activeRequestId == requestId && SystemClock.uptimeMillis() < deadline) {
            powerMode = readNode(config.powerModePath)
            if (powerMode != null && config.powerModeReadyValues.contains(powerMode)) {
                return
            }
            try {
                Thread.sleep(PANEL_READY_POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
        // Write anyway: the timeout is a safety net, not a veto.
        Log.w(TAG, "enable | panel not ready after ${PANEL_READY_TIMEOUT_MS}ms"
                + " (displayState=$displayState powerMode=$powerMode)")
    }

    private fun setLocalHbm(on: Boolean, requestId: Int) = synchronized(nodeLock) {
        if (on) {
            var written = false
            for (attempt in 1..ENABLE_WRITE_ATTEMPTS) {
                if (activeRequestId != requestId) {
                    return@synchronized
                }
                written = writeNode(config.controlPath, config.onValue)
                if (written) {
                    break
                }
                try {
                    Thread.sleep(ENABLE_WRITE_RETRY_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            if (!written) {
                return@synchronized
            }
            illuminated = true
            logStatus()
        } else {
            if (!illuminated) {
                // Never turn LHBM off speculatively: the off command set also restores the
                // backlight, which is a visible flash on a panel that was never illuminated.
                return@synchronized
            }
            illuminated = false
            writeNode(config.controlPath, config.offValue)
        }
    }

    /**
     * Reads the status node once and logs it. The store handler sends the DSI command set
     * synchronously, so the state is final by the time the write returns and there is nothing to
     * poll for.
     */
    private fun logStatus() {
        if (config.statusPath.isEmpty()) {
            return
        }
        val status = readNode(config.statusPath)
        if (config.statusReadyValue.isNotEmpty() && status != config.statusReadyValue) {
            Log.w(TAG, "enable | ${config.statusPath} reads $status,"
                    + " expected ${config.statusReadyValue}")
        } else {
            Log.d(TAG, "enable | local HBM on, ${config.statusPath} reads $status")
        }
    }

    private fun writeNode(path: String, value: String): Boolean {
        try {
            FileOutputStream(path).use { it.write(value.toByteArray(StandardCharsets.US_ASCII)) }
            Log.d(TAG, "wrote $value to $path")
            return true
        } catch (e: IOException) {
            Log.e(TAG, "failed to write $value to $path", e)
            return false
        }
    }

    private fun readNode(path: String): String? {
        try {
            FileInputStream(path).use { stream ->
                val buffer = ByteArray(16)
                val read = stream.read(buffer)
                if (read <= 0) {
                    return null
                }
                return String(buffer, 0, read, StandardCharsets.US_ASCII).trim()
            }
        } catch (e: IOException) {
            Log.e(TAG, "failed to read $path", e)
            return null
        }
    }

    companion object {
        /**
         * Returns [delegate] wrapped in local HBM control, or [delegate] itself on a device that
         * does not configure `config_udfpsLocalHbmControlPath`.
         */
        @JvmStatic
        fun wrapIfConfigured(
            context: Context,
            delegate: UdfpsDisplayModeProvider,
            bgExecutor: Executor,
            mainExecutor: Executor,
        ): UdfpsDisplayModeProvider {
            val resources = context.resources
            val controlPath = resources.getString(R.string.config_udfpsLocalHbmControlPath)
            if (controlPath.isEmpty()) {
                return delegate
            }
            val config =
                Config(
                    controlPath = controlPath,
                    onValue = resources.getString(R.string.config_udfpsLocalHbmOnValue),
                    offValue = resources.getString(R.string.config_udfpsLocalHbmOffValue),
                    statusPath = resources.getString(R.string.config_udfpsLocalHbmStatusPath),
                    statusReadyValue =
                        resources.getString(R.string.config_udfpsLocalHbmStatusReadyValue),
                    authResultPath =
                        resources.getString(R.string.config_udfpsLocalHbmAuthResultPath),
                    powerModePath =
                        resources.getString(R.string.config_udfpsLocalHbmPanelPowerModePath),
                    powerModeReadyValues =
                        resources
                            .getStringArray(R.array.config_udfpsLocalHbmPanelPowerModeReadyValues)
                            .toList(),
                    touchFodPath = resources.getString(R.string.config_udfpsLocalHbmTouchFodPath),
                )
            Log.i(TAG, "local HBM control enabled: $config")
            return UdfpsLocalHbmDisplayMode(context, delegate, config, bgExecutor, mainExecutor)
        }
    }
}

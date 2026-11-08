package com.trackeye.app

import kotlin.math.abs
import kotlin.math.hypot

/** Normalized screen point, 0f..1f on both axes. */
data class GazePoint(val x: Float, val y: Float)

/** Baseline captured during quick calibration. All angles in degrees. */
data class Calibration(
    val centerYaw: Float = 0f,
    val centerPitch: Float = 0f,
    val yawRange: Float = 18f,
    val pitchRange: Float = 14f,
    val browBaseline: Float = 0f,
    val blinkThreshold: Float = 0.25f
) {
    fun isCalibrated(): Boolean = yawRange > 1f && pitchRange > 1f
}

/** User-tunable thresholds (see SPEC section 6). */
data class TrackSettings(
    val dwellMs: Long = 800L,
    val blinkThreshold: Float = 0.25f,
    val confidenceThreshold: Float = 0.6f,
    val tiltSensitivity: Float = 1.0f,
    val overlayEnabled: Boolean = true,
    val dynamicFps: Boolean = true,
    val holdToActivate: Boolean = false
)

enum class ControlMode { CURSOR, SCROLL, KEYBOARD, SCAN }

enum class Gesture {
    BLINK, DOUBLE_BLINK, LONG_BLINK, BROW_RAISE, MOUTH_LEFT, MOUTH_RIGHT,
    HEAD_UP, HEAD_DOWN, HEAD_LEFT, HEAD_RIGHT, DWELL
}

/**
 * Pure, unit-testable mapping from head pose to a gaze point.
 * Head yaw/pitch from ML Kit Euler angles is the on-device approximation
 * of gaze direction (no dedicated eye tracker hardware required).
 */
class GazeMapper(private var calibration: Calibration = Calibration()) {

    private var smoothX = 0.5f
    private var smoothY = 0.5f

    fun setCalibration(value: Calibration) {
        calibration = value
    }

    fun map(headYaw: Float, headPitch: Float): GazePoint {
        val nx = 0.5f + (headYaw - calibration.centerYaw) / (2f * calibration.yawRange)
        val ny = 0.5f + (headPitch - calibration.centerPitch) / (2f * calibration.pitchRange)
        // Exponential smoothing suppresses tremor / micro-movements (ghost-click prevention).
        smoothX += (nx.coerceIn(0f, 1f) - smoothX) * 0.35f
        smoothY += (ny.coerceIn(0f, 1f) - smoothY) * 0.35f
        return GazePoint(smoothX, smoothY)
    }

    fun reset() {
        smoothX = 0.5f
        smoothY = 0.5f
    }
}

/** Detects blink / double-blink / long-blink from eye-open probabilities. */
class BlinkDetector(
    private val thresholdProvider: () -> Float = { 0.25f },
    private val doubleWindowMs: Long = 600L,
    private val longBlinkMs: Long = 600L,
    private val minBlinkMs: Long = 60L
) {
    private var closedSinceMs: Long = -1L
    private var lastBlinkEndMs: Long = -1L

    /** Feed every frame; returns a gesture when one completes, else null. */
    fun onFrame(leftOpen: Float?, rightOpen: Float?, nowMs: Long): Gesture? {
        val open = listOfNotNull(leftOpen, rightOpen).minOrNull() ?: return null
        val closed = open < thresholdProvider()
        if (closed) {
            if (closedSinceMs < 0) closedSinceMs = nowMs
            return null
        }
        if (closedSinceMs < 0) return null
        val duration = nowMs - closedSinceMs
        closedSinceMs = -1L
        if (duration < minBlinkMs) return null
        if (duration >= longBlinkMs) {
            lastBlinkEndMs = nowMs
            return Gesture.LONG_BLINK
        }
        val isDouble = lastBlinkEndMs > 0 && nowMs - lastBlinkEndMs <= doubleWindowMs
        lastBlinkEndMs = nowMs
        return if (isDouble) Gesture.DOUBLE_BLINK else Gesture.BLINK
    }
}

/** Fires DWELL once when the gaze stays inside [radius] for [dwellMs]. */
class DwellDetector(
    private val dwellMsProvider: () -> Long = { 800L },
    private val radius: Float = 0.06f
) {
    private var anchor: GazePoint? = null
    private var sinceMs: Long = -1L
    private var fired = false

    fun onPoint(point: GazePoint, nowMs: Long): Gesture? {
        val a = anchor
        if (a == null || hypot((point.x - a.x).toDouble(), (point.y - a.y).toDouble()) > radius) {
            anchor = point
            sinceMs = nowMs
            fired = false
            return null
        }
        if (!fired && nowMs - sinceMs >= dwellMsProvider()) {
            fired = true
            return Gesture.DWELL
        }
        return null
    }

    /** 0f..1f dwell progress for the HUD timeout bar. */
    fun progress(point: GazePoint, nowMs: Long): Float {
        val a = anchor ?: return 0f
        if (hypot((point.x - a.x).toDouble(), (point.y - a.y).toDouble()) > radius) return 0f
        val dwell = dwellMsProvider().coerceAtLeast(1L)
        return ((nowMs - sinceMs).toFloat() / dwell).coerceIn(0f, 1f)
    }
}

/** Edge-triggered head tilt / turn gestures with a refractory period. */
class HeadGestureDetector(
    private val tiltThresholdDeg: Float = 14f,
    private val refractoryMs: Long = 700L
) {
    private var lastFiredMs: Long = -1L
    private var armed = true

    fun onPose(yaw: Float, pitch: Float, calibration: Calibration, nowMs: Long, sensitivity: Float): Gesture? {
        val dy = yaw - calibration.centerYaw
        val dp = pitch - calibration.centerPitch
        val t = tiltThresholdDeg / sensitivity.coerceAtLeast(0.2f)
        val gesture = when {
            dp > t -> Gesture.HEAD_DOWN
            dp < -t -> Gesture.HEAD_UP
            dy > t -> Gesture.HEAD_RIGHT
            dy < -t -> Gesture.HEAD_LEFT
            else -> null
        }
        if (gesture == null) {
            armed = true
            return null
        }
        if (!armed || nowMs - lastFiredMs < refractoryMs) return null
        armed = false
        lastFiredMs = nowMs
        return gesture
    }
}

/** Brow-raise and mouth-corner gestures from normalized landmark distances. */
class FaceGestureDetector(
    private val browDelta: Float = 0.035f,
    private val mouthDelta: Float = 0.02f,
    private val refractoryMs: Long = 800L
) {
    private var lastFiredMs: Long = -1L

    fun onMetrics(browDistance: Float?, mouthAsymmetry: Float?, browBaseline: Float, nowMs: Long): Gesture? {
        if (nowMs - lastFiredMs < refractoryMs) return null
        if (browDistance != null && browBaseline > 0f && browDistance - browBaseline > browDelta) {
            lastFiredMs = nowMs
            return Gesture.BROW_RAISE
        }
        if (mouthAsymmetry != null && abs(mouthAsymmetry) > mouthDelta) {
            lastFiredMs = nowMs
            return if (mouthAsymmetry > 0) Gesture.MOUTH_RIGHT else Gesture.MOUTH_LEFT
        }
        return null
    }
}

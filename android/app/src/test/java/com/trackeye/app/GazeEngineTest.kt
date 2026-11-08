package com.trackeye.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GazeEngineTest {

    @Test
    fun mapperCentersAndClamps() {
        val mapper = GazeMapper(Calibration(centerYaw = 0f, centerPitch = 0f, yawRange = 10f, pitchRange = 10f))
        repeat(20) { mapper.map(0f, 0f) }
        val center = mapper.map(0f, 0f)
        assertEquals(0.5f, center.x, 0.05f)
        assertEquals(0.5f, center.y, 0.05f)
        val edge = mapper.map(90f, 90f)
        assertTrue(edge.x in 0f..1f && edge.y in 0f..1f)
    }

    @Test
    fun blinkAndDoubleBlink() {
        val detector = BlinkDetector(thresholdProvider = { 0.25f })
        assertNull(detector.onFrame(0.9f, 0.9f, 0))
        assertNull(detector.onFrame(0.1f, 0.1f, 50))
        assertEquals(Gesture.BLINK, detector.onFrame(0.9f, 0.9f, 200))
        assertNull(detector.onFrame(0.1f, 0.1f, 300))
        assertEquals(Gesture.DOUBLE_BLINK, detector.onFrame(0.9f, 0.9f, 450))
    }

    @Test
    fun longBlink() {
        val detector = BlinkDetector(thresholdProvider = { 0.25f })
        detector.onFrame(0.1f, 0.1f, 0)
        assertEquals(Gesture.LONG_BLINK, detector.onFrame(0.9f, 0.9f, 700))
    }

    @Test
    fun dwellFiresOnce() {
        val detector = DwellDetector(dwellMsProvider = { 500L })
        val p = GazePoint(0.5f, 0.5f)
        assertNull(detector.onPoint(p, 0))
        assertNull(detector.onPoint(p, 300))
        assertEquals(Gesture.DWELL, detector.onPoint(p, 600))
        assertNull(detector.onPoint(p, 900))
    }

    @Test
    fun headTiltEdgeTriggered() {
        val detector = HeadGestureDetector()
        val cal = Calibration()
        assertEquals(Gesture.HEAD_DOWN, detector.onPose(0f, 20f, cal, 1000, 1f))
        assertNull(detector.onPose(0f, 20f, cal, 1100, 1f))
        assertNull(detector.onPose(0f, 0f, cal, 1200, 1f))
        assertEquals(Gesture.HEAD_DOWN, detector.onPose(0f, 20f, cal, 2000, 1f))
    }
}

package com.trackeye.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var store: ProfileStore
    private var calibration = Calibration()
    private var settings = TrackSettings()
    private var actionMap: Map<String, String> = defaultActionMap()
    private var mode: ControlMode = ControlMode.CURSOR
    private var controlActive = true

    private val mapper = GazeMapper()
    private val blinkDetector = BlinkDetector(thresholdProvider = { settings.blinkThreshold })
    private val dwellDetector = DwellDetector(dwellMsProvider = { settings.dwellMs })
    private val headDetector = HeadGestureDetector()
    private val faceGestureDetector = FaceGestureDetector()

    private var lastYaw = 0f
    private var lastPitch = 0f
    private var lastBrowDistance = 0f
    private var hasFace = false
    private var currentGaze = GazePoint(0.5f, 0.5f)
    private var lastFrameMs = 0L
    private var lastMoveMs = 0L
    private var sessionStartMs = System.currentTimeMillis()
    private var trainHits = 0
    private var scanIndex = 0
    private var calibrationStep = 0
    private val calibrationSamples = mutableListOf<Pair<Float, Float>>()

    private val handler = Handler(Looper.getMainLooper())
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    private lateinit var statusText: TextView
    private lateinit var modeText: TextView
    private lateinit var logText: TextView
    private lateinit var overlay: GazeOverlayView
    private lateinit var previewView: PreviewView

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.15f)
            .enableTracking()
            .build()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = ProfileStore(this)
        statusText = findViewById(R.id.statusText)
        modeText = findViewById(R.id.modeText)
        logText = findViewById(R.id.logText)
        overlay = findViewById(R.id.gazeOverlay)
        previewView = findViewById(R.id.previewView)

        loadActiveProfile()
        bindControls()
        refreshProfileList()
        updateStatsPanel()
        startScanLoop()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 42)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 42 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
        else statusText.text = "Bez oprávnění ke kameře nelze sledovat pohled."
    }

    private fun loadActiveProfile() {
        val active = store.activeProfileName()
        val profile = active?.let { store.loadProfile(it) } ?: presetProfiles().first()
        calibration = profile.calibration
        settings = profile.settings
        actionMap = profile.actionMap
        mapper.setCalibration(calibration)
        log("Profil: ${profile.name}")
    }

    private fun currentProfile(name: String) = UserProfile(name, calibration, settings, actionMap)

    private fun applySettingsToUi() {
        findViewById<SeekBar>(R.id.dwellSeek).progress = (settings.dwellMs - 300).toInt().coerceIn(0, 900)
        findViewById<SeekBar>(R.id.confSeek).progress = (settings.confidenceThreshold * 100 - 10).toInt().coerceIn(0, 90)
        findViewById<SeekBar>(R.id.tiltSeek).progress = (settings.tiltSensitivity * 100 - 50).toInt().coerceIn(0, 150)
        findViewById<CheckBox>(R.id.overlayCheck).isChecked = settings.overlayEnabled
        findViewById<CheckBox>(R.id.dynamicFpsCheck).isChecked = settings.dynamicFps
        findViewById<CheckBox>(R.id.holdCheck).isChecked = settings.holdToActivate
        updateSettingLabels()
    }

    private fun updateSettingLabels() {
        findViewById<TextView>(R.id.dwellLabel).text = "Dwell čas: ${settings.dwellMs} ms"
        findViewById<TextView>(R.id.confLabel).text = "Práh jistoty: ${(settings.confidenceThreshold * 100).toInt()} %"
        findViewById<TextView>(R.id.tiltLabel).text = "Citlivost náklonu: ${(settings.tiltSensitivity * 100).toInt()} %"
    }

    private fun bindControls() {
        findViewById<Button>(R.id.modeCursor).setOnClickListener { setMode(ControlMode.CURSOR) }
        findViewById<Button>(R.id.modeScroll).setOnClickListener { setMode(ControlMode.SCROLL) }
        findViewById<Button>(R.id.modeKeyboard).setOnClickListener { setMode(ControlMode.KEYBOARD) }
        findViewById<Button>(R.id.modeScan).setOnClickListener { setMode(ControlMode.SCAN) }
        findViewById<Button>(R.id.activateButton).setOnClickListener {
            controlActive = !controlActive
            updateActivateButton()
        }
        findViewById<Button>(R.id.accessibilityButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Povolte „TrackEye ovládání pohledem“.", Toast.LENGTH_LONG).show()
        }
        listOf(R.id.demoA, R.id.demoB, R.id.demoC).forEach { id ->
            findViewById<Button>(id).setOnClickListener { log("Kliknuto: ${(it as Button).text}"); store.addStat("clicks", 1) }
        }
        listOf(R.id.scan0, R.id.scan1, R.id.scan2, R.id.scan3).forEachIndexed { i, id ->
            findViewById<Button>(id).setOnClickListener { onScanSelected(i) }
        }
        val output = findViewById<EditText>(R.id.keyboardOutput)
        findViewById<Button>(R.id.keyA).setOnClickListener { output.append("A") }
        findViewById<Button>(R.id.keyB).setOnClickListener { output.append("B") }
        findViewById<Button>(R.id.keyC).setOnClickListener { output.append("C") }
        findViewById<Button>(R.id.keySpace).setOnClickListener { output.append(" ") }
        findViewById<Button>(R.id.keyBack).setOnClickListener {
            val t = output.text; if (t.isNotEmpty()) output.setText(t.dropLast(1)); output.setSelection(output.text.length)
        }

        fun panelTab(buttonId: Int, panelId: Int) {
            findViewById<Button>(buttonId).setOnClickListener {
                listOf(R.id.calibPanel, R.id.profilesPanel, R.id.trainPanel, R.id.statsPanel).forEach {
                    findViewById<View>(it).visibility = if (it == panelId) View.VISIBLE else View.GONE
                }
                updateStatsPanel()
            }
        }
        panelTab(R.id.tabCalib, R.id.calibPanel)
        panelTab(R.id.tabProfiles, R.id.profilesPanel)
        panelTab(R.id.tabTrain, R.id.trainPanel)
        panelTab(R.id.tabStats, R.id.statsPanel)

        findViewById<Button>(R.id.calibCapture).setOnClickListener { captureCalibrationStep() }
        findViewById<Button>(R.id.calibReset).setOnClickListener {
            calibration = Calibration(); calibrationStep = 0; calibrationSamples.clear()
            mapper.setCalibration(calibration); updateCalibLabel(); log("Kalibrace resetována.")
        }
        findViewById<Button>(R.id.profileChat).setOnClickListener { applyPreset(0) }
        findViewById<Button>(R.id.profileBrowser).setOnClickListener { applyPreset(1) }
        findViewById<Button>(R.id.profileEdu).setOnClickListener { applyPreset(2) }
        findViewById<Button>(R.id.profileSave).setOnClickListener {
            val name = findViewById<EditText>(R.id.profileName).text.toString().trim()
            if (name.isEmpty()) { Toast.makeText(this, "Zadejte název profilu.", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            store.saveProfile(currentProfile(name)); refreshProfileList(); log("Uložen profil: $name")
        }
        findViewById<Button>(R.id.trainTarget).setOnClickListener {
            trainHits++
            findViewById<TextView>(R.id.trainText).text = "Zásahy: $trainHits/5" + if (trainHits >= 5) " — trénink hotový!" else ""
            (it as Button).text = "🎯 Bublina $trainHits/5 — klikni na mě"
            store.addStat("training_hits", 1)
        }
        findViewById<Button>(R.id.statsClear).setOnClickListener { store.clearStats(); sessionStartMs = System.currentTimeMillis(); updateStatsPanel() }

        findViewById<SeekBar>(R.id.dwellSeek).setOnSeekBarChangeListener(simpleSeek { settings = settings.copy(dwellMs = 300L + it); updateSettingLabels() })
        findViewById<SeekBar>(R.id.confSeek).setOnSeekBarChangeListener(simpleSeek { settings = settings.copy(confidenceThreshold = (10 + it) / 100f); updateSettingLabels() })
        findViewById<SeekBar>(R.id.tiltSeek).setOnSeekBarChangeListener(simpleSeek { settings = settings.copy(tiltSensitivity = (50 + it) / 100f); updateSettingLabels() })
        findViewById<CheckBox>(R.id.overlayCheck).setOnCheckedChangeListener { _, v -> settings = settings.copy(overlayEnabled = v); overlay.visibility = if (v) View.VISIBLE else View.INVISIBLE }
        findViewById<CheckBox>(R.id.dynamicFpsCheck).setOnCheckedChangeListener { _, v -> settings = settings.copy(dynamicFps = v) }
        findViewById<CheckBox>(R.id.holdCheck).setOnCheckedChangeListener { _, v -> settings = settings.copy(holdToActivate = v) }
        applySettingsToUi()
        updateCalibLabel()
        updateActivateButton()
    }

    private fun simpleSeek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { if (fromUser) onChange(progress) }
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    private fun applyPreset(index: Int) {
        val p = presetProfiles()[index]
        calibration = p.calibration; settings = p.settings; actionMap = p.actionMap
        mapper.setCalibration(calibration); store.saveProfile(p); applySettingsToUi()
        log("Aktivní profil: ${p.name}")
    }

    private fun refreshProfileList() {
        val names = (presetProfiles().map { it.name } + store.profileNames()).distinct()
        findViewById<TextView>(R.id.profileList).text = "Dostupné profily: ${names.joinToString(", ")}"
    }

    private fun setMode(newMode: ControlMode) {
        mode = newMode
        modeText.text = "Režim: " + when (newMode) {
            ControlMode.CURSOR -> "KURZOR"; ControlMode.SCROLL -> "SCROLL"
            ControlMode.KEYBOARD -> "KLÁVESNICE"; ControlMode.SCAN -> "SCAN & SELECT"
        }
        findViewById<View>(R.id.scanPanel).visibility = if (newMode == ControlMode.SCAN) View.VISIBLE else View.GONE
        findViewById<View>(R.id.keyboardPanel).visibility = if (newMode == ControlMode.KEYBOARD) View.VISIBLE else View.GONE
        log("Přepnut režim: $modeText.text")
    }

    private fun updateActivateButton() {
        findViewById<Button>(R.id.activateButton).text =
            if (controlActive) "Aktivní ovládání: ZAP (dlouhé mrknutí přepne)" else "Aktivní ovládání: VYP (dlouhé mrknutí zapne)"
    }

    // ---------------- Calibration ----------------
    private val calibStepNames = listOf("STŘED", "VLEVO", "VPRAVO", "NAHORU", "DOLŮ")
    private fun updateCalibLabel() {
        findViewById<TextView>(R.id.calibStepText).text =
            if (calibrationStep >= calibStepNames.size) "Kalibrace hotová ✓" else "Krok: ${calibStepNames[calibrationStep]} — dívejte se tím směrem a zachyťte"
    }

    private fun captureCalibrationStep() {
        if (!hasFace) { Toast.makeText(this, "Obličej není vidět.", Toast.LENGTH_SHORT).show(); return }
        calibrationSamples.add(lastYaw to lastPitch)
        calibrationStep++
        if (calibrationStep >= calibStepNames.size) {
            val center = calibrationSamples[0]
            val yawRange = calibrationSamples.drop(1).maxOfOrNull { abs(it.first - center.first) } ?: 18f
            val pitchRange = calibrationSamples.drop(1).maxOfOrNull { abs(it.second - center.second) } ?: 14f
            calibration = calibration.copy(
                centerYaw = center.first, centerPitch = center.second,
                yawRange = yawRange.coerceAtLeast(6f), pitchRange = pitchRange.coerceAtLeast(5f),
                browBaseline = lastBrowDistance
            )
            mapper.setCalibration(calibration)
            store.saveProfile(currentProfile(store.activeProfileName() ?: "Můj profil"))
            log("Kalibrace hotová: rozsah yaw=${calibration.yawRange}°, pitch=${calibration.pitchRange}°")
        }
        updateCalibLabel()
    }

    // ---------------- Scan & Select ----------------
    private fun startScanLoop() {
        handler.post(object : Runnable {
            override fun run() {
                if (mode == ControlMode.SCAN && controlActive) {
                    scanIndex = (scanIndex + 1) % 4
                    highlightScan()
                }
                handler.postDelayed(this, 1200)
            }
        })
    }

    private fun highlightScan() {
        listOf(R.id.scan0, R.id.scan1, R.id.scan2, R.id.scan3).forEachIndexed { i, id ->
            findViewById<Button>(id).alpha = if (i == scanIndex) 1.0f else 0.45f
        }
    }

    private fun onScanSelected(index: Int) {
        when (index) {
            0 -> performAction("click")
            1 -> performAction("back")
            2 -> performAction("scroll_down")
            3 -> TrackEyeAccessibilityService.instance?.goHome() ?: log("Domů: povolte Accessibility službu.")
        }
    }

    // ---------------- Gesture dispatch ----------------
    private fun onGesture(gesture: Gesture) {
        runOnUiThread {
            overlay.pulse = true
            handler.postDelayed({ overlay.pulse = false }, 180)
            if (gesture == Gesture.LONG_BLINK && settings.holdToActivate) {
                controlActive = !controlActive; updateActivateButton()
                log(if (controlActive) "Ovládání aktivováno." else "Ovládání pozastaveno.")
                return@runOnUiThread
            }
            if (!controlActive) return@runOnUiThread
            store.addStat("gestures", 1)
            if (gesture == Gesture.BLINK && mode == ControlMode.SCAN) { onScanSelected(scanIndex); return@runOnUiThread }
            val action = actionMap[gesture.name] ?: return@runOnUiThread
            log("Gesto ${gesture.name} → $action")
            performAction(action)
        }
    }

    private fun performAction(action: String) {
        val svc = TrackEyeAccessibilityService.instance
        val display = resources.displayMetrics
        val x = currentGaze.x * display.widthPixels
        val y = currentGaze.y * display.heightPixels
        when (action) {
            "click" -> {
                store.addStat("clicks", 1)
                if (svc != null) svc.clickAt(x, y) else clickDemoAtGaze()
            }
            "back" -> svc?.goBack() ?: log("Zpět (demo): povolte Accessibility pro systémový návrat.")
            "forward" -> log("Vpřed (v prohlížeči řeší Accessibility jen omezeně).")
            "scroll_up" -> svc?.scrollAt(x, y, downward = false) ?: log("Scroll nahoru (demo).")
            "scroll_down" -> svc?.scrollAt(x, y, downward = true) ?: log("Scroll dolů (demo).")
            "switch_mode" -> {
                val next = ControlMode.values()[(mode.ordinal + 1) % ControlMode.values().size]
                setMode(next)
            }
            "activate" -> { controlActive = !controlActive; updateActivateButton() }
        }
        updateStatsPanel()
    }

    private fun clickDemoAtGaze() {
        val location = IntArray(2)
        listOf(R.id.demoA, R.id.demoB, R.id.demoC, R.id.trainTarget).forEach { id ->
            val b = findViewById<Button>(id)
            if (b.visibility != View.VISIBLE) return@forEach
            b.getLocationOnScreen(location)
            val display = resources.displayMetrics
            val gx = currentGaze.x * display.widthPixels
            val gy = currentGaze.y * display.heightPixels
            if (gx >= location[0] && gx <= location[0] + b.width && gy >= location[1] && gy <= location[1] + b.height) {
                b.performClick()
            }
        }
    }

    private fun updateStatsPanel() {
        val minutes = (System.currentTimeMillis() - sessionStartMs) / 60000.0
        val clicks = store.stat("clicks")
        val gestures = store.stat("gestures")
        val recommendation = when {
            clicks == 0L && minutes > 2 -> "Doporučení: zkuste trénink bublin nebo snižte dwell čas."
            gestures > 0 && clicks * 2 < gestures -> "Doporučení: zvyšte práh jistoty nebo dwell čas (možné falešné detekce)."
            else -> "Doporučení: nastavení vypadá vyváženě."
        }
        findViewById<TextView>(R.id.statsText).text =
            "Doba relace: ${"%.1f".format(minutes)} min\nKliky: $clicks\nGesta celkem: $gestures\nTrénink zásahy: ${store.stat("training_hits")}\n$recommendation"
    }

    private fun log(message: String) {
        runOnUiThread { logText.text = message }
    }

    // ---------------- Camera + ML Kit ----------------
    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analysisExecutor) { proxy -> processFrame(proxy) } }
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
            statusText.text = "Kamera běží. Zpracování je pouze lokální."
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processFrame(proxy: ImageProxy) {
        val now = System.currentTimeMillis()
        // Dynamic FPS: when the face is still, analyse less often to save battery.
        val idle = now - lastMoveMs > 2500
        val minInterval = if (settings.dynamicFps && idle) 200L else 33L
        if (now - lastFrameMs < minInterval) { proxy.close(); return }
        lastFrameMs = now
        val mediaImage = proxy.image
        if (mediaImage == null) { proxy.close(); return }
        val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        detector.process(image)
            .addOnSuccessListener { faces ->
                val face = faces.maxByOrNull { it.boundingBox.width() }
                if (face == null) {
                    hasFace = false
                    runOnUiThread { overlay.trackingActive = false; statusText.text = "Obličej nenalezen — namiřte obličej na kameru a zlepšete osvětlení." }
                    return@addOnSuccessListener
                }
                hasFace = true
                lastYaw = face.headEulerAngleY
                lastPitch = face.headEulerAngleX
                if (abs(lastYaw) + abs(lastPitch) > 0.5f) lastMoveMs = now

                val brow = browDistance(face)
                if (brow != null) lastBrowDistance = brow
                val mouthAsym = mouthAsymmetry(face)

                currentGaze = mapper.map(lastYaw, lastPitch)
                TrackEyeAccessibilityService.gazeXPx = currentGaze.x * resources.displayMetrics.widthPixels
                TrackEyeAccessibilityService.gazeYPx = currentGaze.y * resources.displayMetrics.heightPixels

                blinkDetector.onFrame(face.leftEyeOpenProbability, face.rightEyeOpenProbability, now)?.let { onGesture(it) }
                headDetector.onPose(lastYaw, lastPitch, calibration, now, settings.tiltSensitivity)?.let {
                    if (mode == ControlMode.SCROLL || it == Gesture.HEAD_LEFT || it == Gesture.HEAD_RIGHT) onGesture(it)
                }
                faceGestureDetector.onMetrics(brow, mouthAsym, calibration.browBaseline, now)?.let { onGesture(it) }
                if (mode == ControlMode.CURSOR) {
                    dwellDetector.onPoint(currentGaze, now)?.let { onGesture(it) }
                }
                runOnUiThread {
                    overlay.trackingActive = true
                    overlay.gaze = if (settings.overlayEnabled) currentGaze else null
                    overlay.dwellProgress = dwellDetector.progress(currentGaze, now)
                    statusText.text = "Tracking aktivní • pohled ${(currentGaze.x * 100).toInt()} %, ${(currentGaze.y * 100).toInt()} %"
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun browDistance(face: com.google.mlkit.vision.face.Face): Float? {
        val browPoints = face.getContour(com.google.mlkit.vision.face.FaceContour.LEFT_EYEBROW_TOP)?.points
        val brow = browPoints?.minByOrNull { it.y } ?: return null
        val eye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position ?: return null
        val h = face.boundingBox.height().toFloat().coerceAtLeast(1f)
        return abs(brow.y - eye.y) / h
    }

    private fun mouthAsymmetry(face: com.google.mlkit.vision.face.Face): Float? {
        val left = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position ?: return null
        val right = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position ?: return null
        val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position ?: return null
        val w = face.boundingBox.width().toFloat().coerceAtLeast(1f)
        return ((right.y - nose.y) - (left.y - nose.y)) / w
    }

    override fun onDestroy() {
        analysisExecutor.shutdown()
        detector.close()
        super.onDestroy()
    }
}

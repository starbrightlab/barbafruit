package com.family.barbafruit

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.util.Size
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var drawer: DrawerLayout
    private lateinit var gameView: GameSurfaceView
    private lateinit var pipPreview: PreviewView
    private lateinit var permissionOverlay: LinearLayout

    private lateinit var btnModeFrenzy: Button
    private lateinit var btnModeClassic: Button
    private lateinit var btnTime30: Button
    private lateinit var btnTime60: Button
    private lateinit var btnTime120: Button
    private lateinit var btnTimeEndless: Button
    private lateinit var btnEasy: Button
    private lateinit var btnMedium: Button
    private lateinit var btnHard: Button
    private lateinit var timerSection: LinearLayout

    private lateinit var cameraExecutor: ExecutorService
    private var analyzer: FaceTrackerAnalyzer? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraStarted = false

    private val prefs by lazy { getSharedPreferences("barbafruit", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Face-controlled game: the player never touches the screen, so nothing
        // resets the system screen-off timer. Hold the screen on while we're in
        // the foreground so the Portal's screensaver can't cut in mid-game.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        drawer = findViewById(R.id.drawerLayout)
        gameView = findViewById(R.id.gameSurface)
        pipPreview = findViewById(R.id.pipPreview)
        permissionOverlay = findViewById(R.id.permissionOverlay)

        btnModeFrenzy = findViewById(R.id.btnModeFrenzy)
        btnModeClassic = findViewById(R.id.btnModeClassic)
        btnTime30 = findViewById(R.id.btnTime30)
        btnTime60 = findViewById(R.id.btnTime60)
        btnTime120 = findViewById(R.id.btnTime120)
        btnTimeEndless = findViewById(R.id.btnTimeEndless)
        btnEasy = findViewById(R.id.btnEasy)
        btnMedium = findViewById(R.id.btnMedium)
        btnHard = findViewById(R.id.btnHard)
        timerSection = findViewById(R.id.timerSection)

        cameraExecutor = Executors.newSingleThreadExecutor()

        // ---- Restore saved settings ----
        val initialMode = readEnumPref("mode", GameSurfaceView.GameMode.FRUIT_FRENZY)
        val initialDiff = readEnumPref("difficulty", GameSurfaceView.Difficulty.MEDIUM)
        val initialSeconds = prefs.getInt("round_seconds", 60)

        btnModeFrenzy.setOnClickListener { updateMode(GameSurfaceView.GameMode.FRUIT_FRENZY) }
        btnModeClassic.setOnClickListener { updateMode(GameSurfaceView.GameMode.CLASSIC) }
        btnTime30.setOnClickListener { updateRoundSeconds(30) }
        btnTime60.setOnClickListener { updateRoundSeconds(60) }
        btnTime120.setOnClickListener { updateRoundSeconds(120) }
        btnTimeEndless.setOnClickListener { updateRoundSeconds(0) }   // 0 = endless
        btnEasy.setOnClickListener { updateDifficulty(GameSurfaceView.Difficulty.EASY) }
        btnMedium.setOnClickListener { updateDifficulty(GameSurfaceView.Difficulty.MEDIUM) }
        btnHard.setOnClickListener { updateDifficulty(GameSurfaceView.Difficulty.HARD) }

        gameView.roundSeconds = initialSeconds
        gameView.difficulty = initialDiff
        gameView.gameMode = initialMode
        refreshSettingButtons()
        loadHighScore()

        gameView.onNewHighScore = { hs ->
            prefs.edit().putInt(getHighScoreKey(), hs).apply()
        }

        wireDrawer()

        findViewById<Button>(R.id.btnGrantCamera).setOnClickListener {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    private inline fun <reified T : Enum<T>> readEnumPref(key: String, default: T): T {
        val saved = prefs.getString(key, default.name) ?: default.name
        return try { enumValueOf<T>(saved) } catch (e: Exception) { default }
    }

    // ======================== Drawer & menu ========================
    private fun wireDrawer() {
        findViewById<ImageButton>(R.id.btnMenu).setOnClickListener {
            drawer.openDrawer(GravityCompat.START)
        }

        // Opening the drawer must instantly pause the game loop's action;
        // closing it resumes.
        drawer.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) = gameView.pauseGame()
            override fun onDrawerClosed(drawerView: View) = gameView.resumeGame()
        })

        findViewById<Button>(R.id.btnPlay).setOnClickListener {
            gameView.startCalibration()
            drawer.closeDrawer(GravityCompat.START)
        }
        findViewById<Button>(R.id.btnRecalibrate).setOnClickListener {
            gameView.startCalibration()
            drawer.closeDrawer(GravityCompat.START)
        }
        findViewById<Button>(R.id.btnResetScore).setOnClickListener {
            gameView.resetHighScore()
            prefs.edit().putInt(getHighScoreKey(), 0).apply()
            drawer.closeDrawer(GravityCompat.START)
        }
        findViewById<Button>(R.id.btnQuit).setOnClickListener {
            finish()
        }
    }

    // ======================== Settings ========================

    /**
     * High scores are tracked per game configuration: a 30-second frenzy
     * score and a 2-minute frenzy score aren't comparable, and classic
     * scores live in their own world entirely.
     */
    private fun getHighScoreKey(): String = when (gameView.gameMode) {
        GameSurfaceView.GameMode.FRUIT_FRENZY -> {
            val len = if (gameView.roundSeconds > 0) "${gameView.roundSeconds}" else "endless"
            "hs_frenzy_${len}_${gameView.difficulty.name.lowercase()}"
        }
        GameSurfaceView.GameMode.CLASSIC ->
            "hs_classic_${gameView.difficulty.name.lowercase()}"
    }

    private fun loadHighScore() {
        gameView.highScore = prefs.getInt(getHighScoreKey(), 0)
    }

    private fun updateMode(mode: GameSurfaceView.GameMode) {
        gameView.gameMode = mode
        prefs.edit().putString("mode", mode.name).apply()
        refreshSettingButtons()
        loadHighScore()
    }

    private fun updateRoundSeconds(seconds: Int) {
        gameView.roundSeconds = seconds
        prefs.edit().putInt("round_seconds", seconds).apply()
        refreshSettingButtons()
        loadHighScore()
    }

    private fun updateDifficulty(diff: GameSurfaceView.Difficulty) {
        gameView.difficulty = diff
        prefs.edit().putString("difficulty", diff.name).apply()
        refreshSettingButtons()
        loadHighScore()
    }

    private fun refreshSettingButtons() {
        val pink = ContextCompat.getColor(this, R.color.truffula_pink)
        val orange = ContextCompat.getColor(this, R.color.truffula_orange)
        val yellow = ContextCompat.getColor(this, R.color.truffula_yellow)
        val lime = ContextCompat.getColor(this, R.color.leaf_lime)
        val cyan = ContextCompat.getColor(this, R.color.sky_cyan)
        val red = ContextCompat.getColor(this, R.color.berry_red)

        val mode = gameView.gameMode
        tintToggle(btnModeFrenzy, pink, mode == GameSurfaceView.GameMode.FRUIT_FRENZY)
        tintToggle(btnModeClassic, cyan, mode == GameSurfaceView.GameMode.CLASSIC)

        // The timer only matters in Fruit Frenzy — hide it in Classic so the
        // menu doesn't suggest a setting that has no effect.
        timerSection.visibility =
            if (mode == GameSurfaceView.GameMode.FRUIT_FRENZY) View.VISIBLE else View.GONE
        val secs = gameView.roundSeconds
        tintToggle(btnTime30, yellow, secs == 30)
        tintToggle(btnTime60, yellow, secs == 60)
        tintToggle(btnTime120, yellow, secs == 120)
        tintToggle(btnTimeEndless, yellow, secs == 0)

        val diff = gameView.difficulty
        tintToggle(btnEasy, lime, diff == GameSurfaceView.Difficulty.EASY)
        tintToggle(btnMedium, orange, diff == GameSurfaceView.Difficulty.MEDIUM)
        tintToggle(btnHard, red, diff == GameSurfaceView.Difficulty.HARD)
    }

    private fun tintToggle(btn: Button, activeColor: Int, selected: Boolean) {
        val dim = Color.parseColor("#44FFFFFF")
        btn.backgroundTintList = ColorStateList.valueOf(if (selected) activeColor else dim)
        btn.setTextColor(if (selected) Color.parseColor("#2C1E5E") else Color.WHITE)
    }

    // ======================== Permissions ========================
    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA && hasCameraPermission()) {
            permissionOverlay.visibility = View.GONE
            startCamera()
        } else if (requestCode == REQ_CAMERA) {
            permissionOverlay.visibility = View.VISIBLE
        }
    }

    // ======================== CameraX pipeline ========================
    private fun startCamera() {
        if (cameraStarted) return
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider

            // Low-res analysis stream: plenty for face boxes, light enough
            // for the Portal's SoC to sustain ≥30 fps into ML Kit.
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val faceAnalyzer = FaceTrackerAnalyzer { frame ->
                gameView.onFaceFrame(frame)
            }
            analyzer = faceAnalyzer
            analysis.setAnalyzer(cameraExecutor, faceAnalyzer)

            // Tiny PiP preview for positioning feedback.
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(pipPreview.surfaceProvider)
            }

            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                analysis
            )
            cameraStarted = true
        }, ContextCompat.getMainExecutor(this))
    }

    // ======================== Lifecycle ========================
    override fun onResume() {
        super.onResume()
        if (hasCameraPermission()) {
            permissionOverlay.visibility = View.GONE
            // CameraX is lifecycle-aware and resumes the stream itself;
            // we just (re)build the pipeline on first grant / first resume.
            startCamera()
            gameView.resumeGame()
        } else {
            permissionOverlay.visibility = View.VISIBLE
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }
    }

    override fun onPause() {
        // CameraX automatically stops the stream when the lifecycle pauses.
        // We additionally freeze gameplay so nothing moves while invisible.
        gameView.pauseGame()
        super.onPause()
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        analyzer?.shutdown()           // release the ML Kit detector
        cameraExecutor.shutdown()
        gameView.sound.release()
        super.onDestroy()
    }

    private companion object {
        const val REQ_CAMERA = 1001
    }
}

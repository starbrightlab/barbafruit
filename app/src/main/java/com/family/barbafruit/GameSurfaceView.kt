package com.family.barbafruit

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.TextureView
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * The entire game lives here: a custom background [GameThread] renders
 * onto this SurfaceView's Canvas at ~60 fps, fully decoupled from the
 * UI thread and the camera/ML pipeline.
 *
 * Thread-safety model (deliberately simple — no frameworks):
 *  - The camera thread writes the latest [FaceFrame] into [@Volatile]
 *    fields via [onFaceFrame].
 *  - The game thread reads them each tick and lerps toward them.
 *  - State transitions requested from the UI thread (drawer buttons)
 *    set a volatile "requested state" the loop honors on its next tick.
 *
 * Game modes:
 *  - FRUIT_FRENZY (default): nothing bad ever falls and nothing can end the
 *    game early. Catch as many Truffula fruits as possible before the timer
 *    runs out, then celebrate. Designed for very young players.
 *  - CLASSIC: the original rules — rocks and boots fall too; eating 3 of
 *    them, or dropping 3 fruits, ends the game.
 */
class GameSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {

    // ======================== Game states, modes & difficulty ========================
    enum class State { WAITING_FOR_CAMERA, CALIBRATING, COUNTDOWN, PLAYING, PAUSED, TIME_UP, GAME_OVER }
    enum class GameMode { FRUIT_FRENZY, CLASSIC }
    enum class Difficulty { EASY, MEDIUM, HARD }

    @Volatile private var state = State.WAITING_FOR_CAMERA
    @Volatile var gameMode = GameMode.FRUIT_FRENZY
    @Volatile var difficulty = Difficulty.MEDIUM
    /**
     * Round length for FRUIT_FRENZY, in seconds. Adjustable from the drawer.
     * 0 means endless — no timer, no ending, play until a grown-up quits.
     */
    @Volatile var roundSeconds = 60

    // ==================== Face input (volatile bridge) ====================
    @Volatile private var faceX = 0.5f
    @Volatile private var faceY = 0.5f
    @Volatile private var faceSize = 0f
    @Volatile private var faceVisible = false

    /** Called from the CameraX analyzer thread. */
    fun onFaceFrame(f: FaceFrame) {
        faceX = f.normX
        faceY = f.normY
        faceSize = f.sizeRatio
        faceVisible = f.hasFace
    }

    // ======================== Public controls ========================
    val sound = SoundFx(context)
    @Volatile var highScore = 0
    var onNewHighScore: ((Int) -> Unit)? = null   // invoked on game thread

    fun startCalibration() {
        score = 0
        alignedSinceMs = 0L
        calibrationAccumX = 0f
        calibrationCount = 0
        calibratedCenterX = 0.5f
        state = State.CALIBRATING
    }

    fun pauseGame() {
        if (state == State.PLAYING || state == State.COUNTDOWN) {
            state = State.PAUSED
            sound.pauseMusic()
        }
    }

    fun resumeGame() {
        if (state == State.PAUSED) {
            state = State.PLAYING
            sound.resumeMusic()
        }
    }

    fun resetHighScore() { highScore = 0 }

    fun quitToMainScreen() {
        sound.stopMusic()
        items.clear()
        particles.clear()
        score = 0
        badItemsEaten = 0
        fruitsDropped = 0
        alignedSinceMs = 0L
        state = State.WAITING_FOR_CAMERA
    }

    // ======================== Game entities ========================
    private var score = 0
    private var badItemsEaten = 0
    private var fruitsDropped = 0
    private var bearX = 0.5f             // smoothed render position (0..1)
    private var bearDizzyUntil = 0L      // wobble animation end time (classic only)
    private var alignedSinceMs = 0L      // calibration hold timer
    private var calibrationAccumX = 0f
    private var calibrationCount = 0
    private var calibratedCenterX = 0.5f
    private var countdownStartMs = 0L
    private var lastSpawnMs = 0L
    private var lastTickPlayed = -1

    // Timed-round state (FRUIT_FRENZY). Captured at countdown start so
    // drawer changes never affect a round already in progress. Elapsed time
    // is accumulated from dt, so pausing freezes the clock too.
    private var activeMode = GameMode.FRUIT_FRENZY
    private var activeRoundSeconds = 60
    private var playElapsedSec = 0f
    private var lastTimerTick = -1
    private var lastConfettiMs = 0L

    // Milestone cheer (FRUIT_FRENZY): every 10th fruit the Lorax pops up.
    private var milestoneUntilMs = 0L
    private var milestoneText = ""
    private var milestoneCheer = ""

    private data class Item(
        var x: Float, var y: Float,       // normalized
        val good: Boolean,
        val kind: Int,                    // fruit: 0 pink, 1 orange, 2 yellow | bad: 0 rock, 1 boot
        val speed: Float,                 // normalized units / second
        val sway: Float                   // phase offset for a gentle drift
    )

    private data class Particle(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        var life: Float, val color: Int
    )

    private val items = CopyOnWriteArrayList<Item>()
    private val particles = CopyOnWriteArrayList<Particle>()

    // ======================== Paints (allocated once) ========================
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT_BOLD, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    // Truffula palette — candy tufts over a Seuss twilight sky.
    private val cPink = Color.parseColor("#FF8AC9")
    private val cOrange = Color.parseColor("#FFA94D")
    private val cYellow = Color.parseColor("#FFE066")
    private val cLime = Color.parseColor("#8BFF2B")
    private val cCyan = Color.parseColor("#7FE3FF")
    private val cRed = Color.parseColor("#FF4D6A")
    private val cSkyTop = Color.parseColor("#2C1E5E")
    private val cSkyBottom = Color.parseColor("#7A4A8F")
    private val cGrass = Color.parseColor("#2E7D5B")
    private val cGrassLight = Color.parseColor("#3E9C6F")
    private val cHillBack = Color.parseColor("#27584E")
    private val cHillMid = Color.parseColor("#2B6156")
    private val cBearFur = Color.parseColor("#8A5A33")
    private val cBearFurDark = Color.parseColor("#6E4626")
    private val cBearMuzzle = Color.parseColor("#D9A972")
    private val cBearDark = Color.parseColor("#4A2E17")
    private val cTongue = Color.parseColor("#E8837E")
    private val cCream = Color.parseColor("#F5E9C8")
    private val cTrunkBand = Color.parseColor("#C99B66")
    private val cLoraxOrange = Color.parseColor("#F28C28")
    private val cLoraxFace = Color.parseColor("#FFC98B")
    private val cMustache = Color.parseColor("#FFD34D")
    private val cLoraxNose = Color.parseColor("#D9822B")
    private val fruitColors = intArrayOf(0, 0, 0).also {
        it[0] = cPink; it[1] = cOrange; it[2] = cYellow
    }
    private var skyShader: LinearGradient? = null
    private var skyShaderHeight = 0f

    // The static scenery (hills, grass, truffula grove) is expensive to
    // re-draw every frame, so it's baked into a bitmap per surface size.
    private var sceneryBitmap: Bitmap? = null
    private val trunkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    /** Multiply a color's RGB channels by [f] (f < 1 darkens). */
    private fun shade(color: Int, f: Float): Int = Color.argb(
        Color.alpha(color),
        (Color.red(color) * f).toInt().coerceIn(0, 255),
        (Color.green(color) * f).toInt().coerceIn(0, 255),
        (Color.blue(color) * f).toInt().coerceIn(0, 255)
    )

    /** Mix a color toward white by [f] (0..1). */
    private fun lighten(color: Int, f: Float): Int = Color.argb(
        Color.alpha(color),
        (Color.red(color) + (255 - Color.red(color)) * f).toInt(),
        (Color.green(color) + (255 - Color.green(color)) * f).toInt(),
        (Color.blue(color) + (255 - Color.blue(color)) * f).toInt()
    )

    /** Filled ellipse centered at (x, y), optionally rotated. */
    private fun ell(c: Canvas, x: Float, y: Float, rx: Float, ry: Float, rotDeg: Float = 0f) {
        if (rotDeg == 0f) {
            c.drawOval(x - rx, y - ry, x + rx, y + ry, paint)
        } else {
            c.save()
            c.rotate(rotDeg, x, y)
            c.drawOval(x - rx, y - ry, x + rx, y + ry, paint)
            c.restore()
        }
    }

    // ======================== Thread plumbing ========================
    private var thread: GameThread? = null

    init {
        isOpaque = true
        surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        thread = GameThread().also { it.running = true; it.start() }
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        thread?.let {
            it.running = false
            try { it.join(500) } catch (_: InterruptedException) {}
        }
        thread = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (state == State.GAME_OVER || state == State.TIME_UP) {
            if (event.action == MotionEvent.ACTION_DOWN) {
                startCalibration()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    // ======================== THE GAME LOOP ========================
    private inner class GameThread : Thread("GameLoop") {
        @Volatile var running = false

        override fun run() {
            var lastNs = System.nanoTime()
            while (running) {
                val nowNs = System.nanoTime()
                val dt = ((nowNs - lastNs) / 1_000_000_000.0f).coerceAtMost(0.05f)
                lastNs = nowNs

                update(dt)

                val canvas: Canvas? = try { this@GameSurfaceView.lockCanvas() } catch (e: Exception) { null }
                if (canvas != null) {
                    try { render(canvas) }
                    finally {
                        try { this@GameSurfaceView.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
                    }
                }

                // ~60 fps pacing
                val frameMs = (System.nanoTime() - nowNs) / 1_000_000
                val sleepMs = 16 - frameMs
                if (sleepMs > 0) try { sleep(sleepMs) } catch (_: InterruptedException) {}
            }
        }
    }

    // ======================== UPDATE ========================
    private fun update(dt: Float) {
        val now = System.currentTimeMillis()

        when (state) {
            State.WAITING_FOR_CAMERA -> if (faceVisible) state = State.CALIBRATING

            State.CALIBRATING -> updateCalibration(now)

            State.COUNTDOWN -> {
                val elapsed = now - countdownStartMs
                val secondsLeft = 3 - (elapsed / 1000).toInt()
                if (secondsLeft != lastTickPlayed && secondsLeft in 1..3) {
                    sound.tick(); lastTickPlayed = secondsLeft
                }
                if (elapsed >= 3000) {
                    sound.go()     // plays fanfare + starts background music
                    items.clear(); particles.clear()
                    score = 0; badItemsEaten = 0; fruitsDropped = 0; lastSpawnMs = now
                    playElapsedSec = 0f; lastTimerTick = -1; milestoneUntilMs = 0L
                    state = State.PLAYING
                }
            }

            State.PLAYING -> updatePlaying(dt, now)

            State.PAUSED -> { /* frozen — music already paused by pauseGame() */ }
            State.TIME_UP -> updateCelebration(now)
            State.GAME_OVER -> { /* frozen */ }
        }

        // Particles update in every state so bursts finish nicely.
        particles.forEach { p ->
            p.x += p.vx * dt; p.y += p.vy * dt
            p.vy += 1.2f * dt           // gravity
            p.life -= dt * 1.8f
        }
        particles.removeAll { it.life <= 0f }
    }

    private fun updateCalibration(now: Long) {
        val aligned = isFaceAligned()
        if (aligned) {
            if (alignedSinceMs == 0L) {
                alignedSinceMs = now
                calibrationAccumX = 0f
                calibrationCount = 0
            }
            calibrationAccumX += faceX
            calibrationCount++

            // Hold steady for 800 ms before launching the countdown —
            // forgiving for a wiggly little kid, but avoids false starts.
            if (now - alignedSinceMs >= 800) {
                calibratedCenterX = if (calibrationCount > 0) calibrationAccumX / calibrationCount else 0.5f
                // Lock in the round settings for this game.
                activeMode = gameMode
                activeRoundSeconds = roundSeconds
                countdownStartMs = now
                lastTickPlayed = -1
                state = State.COUNTDOWN
            }
        } else {
            alignedSinceMs = 0L
        }
    }

    /** Face center inside the target circle AND at a sane distance. */
    private fun isFaceAligned(): Boolean {
        if (!faceVisible) return false
        val inCircle = abs(faceX - 0.5f) < CAL_RADIUS && abs(faceY - 0.5f) < CAL_RADIUS
        val goodDistance = faceSize in MIN_FACE_SIZE..MAX_FACE_SIZE
        return inCircle && goodDistance
    }

    private fun updatePlaying(dt: Float, now: Long) {
        // ---- Bear follows the face with a render-side lerp on top of the
        // analyzer's low-pass filter: silky even if camera frames drop. ----
        val targetX = (0.5f + (faceX - calibratedCenterX) * 2.2f).coerceIn(0.05f, 0.95f)
        bearX += (targetX - bearX) * (LERP_SPEED * dt).coerceAtMost(1f)

        // ---- Round timer (FRUIT_FRENZY only, skipped in endless). dt-based
        // so pauses freeze it. ----
        if (activeMode == GameMode.FRUIT_FRENZY && activeRoundSeconds > 0) {
            playElapsedSec += dt
            val secondsLeft = (activeRoundSeconds - playElapsedSec.toInt()).coerceAtLeast(0)
            if (secondsLeft != lastTimerTick && secondsLeft in 1..3) {
                sound.tick(); lastTimerTick = secondsLeft
            }
            if (playElapsedSec >= activeRoundSeconds) {
                sound.celebrate()          // happy fanfare, stops the music
                state = State.TIME_UP
                items.clear()
                lastConfettiMs = 0L
                return
            }
        }

        // ---- Difficulty settings ----
        val spawnInterval = when (difficulty) {
            Difficulty.EASY -> 1400L
            Difficulty.MEDIUM -> 1000L
            Difficulty.HARD -> 700L
        }
        val speedMultiplier = when (difficulty) {
            Difficulty.EASY -> 0.75f
            Difficulty.MEDIUM -> 1.00f
            Difficulty.HARD -> 1.40f
        }

        // ---- Spawn items ----
        if (now - lastSpawnMs > spawnInterval) {
            lastSpawnMs = now
            // Frenzy mode: every single thing that falls is catchable fruit.
            val good = activeMode == GameMode.FRUIT_FRENZY || Random.nextFloat() > 0.25f
            items.add(
                Item(
                    x = Random.nextFloat() * 0.86f + 0.07f,
                    y = -0.08f,
                    good = good,
                    kind = if (good) Random.nextInt(3) else Random.nextInt(2),
                    speed = FALL_SPEED * speedMultiplier * (0.85f + Random.nextFloat() * 0.4f),
                    sway = Random.nextFloat() * (2 * Math.PI).toFloat()
                )
            )
        }

        // ---- Move items & detect collisions (pure AABB, normalized) ----
        // On EASY the catch box is wider than the bear looks, so a
        // near-miss still counts — the littlest players barely move.
        val catchHalfW =
            if (difficulty == Difficulty.EASY) BEAR_HALF_W * EASY_CATCH_BONUS else BEAR_HALF_W
        val bearLeft = bearX - catchHalfW
        val bearRight = bearX + catchHalfW
        val bearTop = BEAR_Y - BEAR_HALF_H

        items.forEach { it.y += it.speed * dt }

        val eaten = items.filter { item ->
            item.y + ITEM_HALF > bearTop &&
            item.y - ITEM_HALF < BEAR_Y + BEAR_HALF_H &&
            item.x + ITEM_HALF > bearLeft &&
            item.x - ITEM_HALF < bearRight
        }
        eaten.forEach { item ->
            if (item.good) {
                score++
                if (score > highScore) { highScore = score; onNewHighScore?.invoke(highScore) }
                sound.eatFruit()
                burst(item.x, item.y, fruitColors[item.kind])
                burst(item.x, item.y, cYellow)
                if (activeMode == GameMode.FRUIT_FRENZY && score % MILESTONE_EVERY == 0) {
                    startMilestone(now)
                }
            } else {
                badItemsEaten++
                bearDizzyUntil = now + 1200
                sound.eatBomb()
                burst(item.x, item.y, Color.GRAY)
            }
        }
        items.removeAll(eaten.toSet())

        // ---- Missed fruits fall off screen ----
        val fellOff = items.filter { it.y > 1.15f }
        fellOff.forEach { item ->
            // In frenzy mode a missed fruit is a silent non-event: no penalty,
            // no sad sound, nothing for a small player to get discouraged by.
            if (item.good && activeMode == GameMode.CLASSIC) {
                fruitsDropped++
                sound.dropFruit()
            }
        }
        items.removeAll(fellOff.toSet())

        // ---- Classic-only fail condition ----
        if (activeMode == GameMode.CLASSIC && (badItemsEaten >= 3 || fruitsDropped >= 3)) {
            sound.gameOver()   // stops music, plays game-over sting
            state = State.GAME_OVER
            items.clear()
        }
    }

    /** Every 10th fruit: the Lorax pops up, confetti flies, a cheer chirps. */
    private fun startMilestone(now: Long) {
        milestoneUntilMs = now + MILESTONE_MS
        milestoneText = "$score!"
        val cheers = arrayOf("WOW!", "YAY!", "SUPER!", "HOORAY!")
        milestoneCheer = cheers[(score / MILESTONE_EVERY - 1).mod(cheers.size)]
        sound.cheer()
        repeat(3) {
            burst(
                Random.nextFloat() * 0.6f + 0.2f,
                Random.nextFloat() * 0.3f + 0.15f,
                fruitColors[Random.nextInt(3)]
            )
        }
    }

    /** Keep the party going on the celebration screen. */
    private fun updateCelebration(now: Long) {
        if (now - lastConfettiMs > 450) {
            lastConfettiMs = now
            burst(
                Random.nextFloat() * 0.8f + 0.1f,
                Random.nextFloat() * 0.4f + 0.05f,
                fruitColors[Random.nextInt(3)]
            )
        }
    }

    private fun burst(x: Float, y: Float, color: Int) {
        repeat(14) {
            val angle = Random.nextFloat() * (2 * Math.PI).toFloat()
            val speed = 0.25f + Random.nextFloat() * 0.45f
            particles.add(
                Particle(
                    x, y,
                    vx = speed * kotlin.math.cos(angle),
                    vy = speed * sin(angle) - 0.2f,
                    life = 1f,
                    color = color
                )
            )
        }
    }

    // ======================== RENDER ========================
    private fun render(c: Canvas) {
        val w = c.width.toFloat()
        val h = c.height.toFloat()
        drawSky(c, w, h)
        drawScenery(c, w, h)

        when (state) {
            State.WAITING_FOR_CAMERA -> drawCenteredMessage(c, w, h, "Looking for you…", "Stand in front of the screen! 👀")
            State.CALIBRATING -> drawCalibration(c, w, h)
            State.COUNTDOWN -> { drawWorld(c, w, h); drawCountdown(c, w, h) }
            State.PLAYING -> { drawWorld(c, w, h); drawHud(c, w, h); drawMilestone(c, w, h) }
            State.PAUSED -> { drawWorld(c, w, h); drawCenteredMessage(c, w, h, "Paused", "Close the menu to keep playing!") }
            State.TIME_UP -> { drawWorld(c, w, h); drawCelebration(c, w, h) }
            State.GAME_OVER -> { drawWorld(c, w, h); drawGameOver(c, w, h) }
        }
    }

    // ---------------- Scenery ----------------
    private fun drawSky(c: Canvas, w: Float, h: Float) {
        if (skyShader == null || skyShaderHeight != h) {
            skyShader = LinearGradient(0f, 0f, 0f, h, cSkyTop, cSkyBottom, Shader.TileMode.CLAMP)
            skyShaderHeight = h
        }
        paint.style = Paint.Style.FILL
        paint.shader = skyShader
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = null

        // Deterministic twinkly dots — cheap and cheerful.
        val t = System.currentTimeMillis() / 600.0
        for (i in 0 until 30) {
            val sx = ((i * 97) % 100) / 100f * w
            val sy = ((i * 53) % 100) / 100f * h * 0.7f
            val twinkle = (sin(t + i) * 0.5 + 0.5).toFloat()
            paint.color = Color.argb((30 + 70 * twinkle).toInt(), 255, 255, 255)
            c.drawCircle(sx, sy, 2.5f + 2f * twinkle, paint)
        }
    }

    /** Blit the baked scenery, rebuilding it if the surface size changed. */
    private fun drawScenery(c: Canvas, w: Float, h: Float) {
        val cached = sceneryBitmap
        val bmp = if (cached == null || cached.width != w.toInt() || cached.height != h.toInt()) {
            Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888).also {
                buildScenery(Canvas(it), w, h)
                sceneryBitmap = it
            }
        } else cached
        c.drawBitmap(bmp, 0f, 0f, null)
    }

    private fun buildScenery(c: Canvas, w: Float, h: Float) {
        paint.style = Paint.Style.FILL
        // Distant rolling hills behind the grass line.
        paint.color = cHillBack
        c.drawOval(w * -0.20f, h * 0.885f, w * 0.64f, h * 1.085f, paint)
        paint.color = cHillMid
        c.drawOval(w * 0.30f, h * 0.875f, w * 1.26f, h * 1.115f, paint)
        paint.color = cGrass
        c.drawRect(0f, h * 0.94f, w, h, paint)
        paint.color = cGrassLight
        for (i in 0 until 9) {
            c.drawCircle((i / 8f) * w, h * 0.955f, h * 0.025f, paint)
        }
        // Two short, dimmed trees on the far hill for depth…
        drawTree(c, w, h, w * 0.46f, h * 0.74f, h * 0.048f, w * 0.006f, cOrange, dim = true)
        drawTree(c, w, h, w * 0.585f, h * 0.77f, h * 0.042f, -w * 0.005f, cPink, dim = true)
        // …and four foreground trees framing the play space, positioned to
        // leave the top-center (score/timer) and HUD rows clear.
        drawTree(c, w, h, w * 0.06f, h * 0.15f, h * 0.082f, w * 0.012f, cPink, dim = false)
        drawTree(c, w, h, w * 0.21f, h * 0.30f, h * 0.062f, -w * 0.012f, cYellow, dim = false)
        drawTree(c, w, h, w * 0.79f, h * 0.28f, h * 0.062f, w * 0.012f, cOrange, dim = false)
        drawTree(c, w, h, w * 0.94f, h * 0.14f, h * 0.082f, -w * 0.012f, cPink, dim = false)
    }

    /** One truffula tree: S-curved trunk with ring stripes, tuft on top. */
    private fun drawTree(
        c: Canvas, w: Float, h: Float,
        baseX: Float, tuftY: Float, r: Float, lean: Float, color: Int, dim: Boolean
    ) {
        val baseY = h * 0.965f
        val topX = baseX + lean
        val topY = tuftY + r * 0.55f
        val thick = r * 0.24f

        val trunk = Path().apply {
            moveTo(baseX, baseY)
            cubicTo(
                baseX - lean * 0.8f, baseY - (baseY - topY) * 0.38f,
                topX + lean * 1.1f, topY + (baseY - topY) * 0.34f,
                topX, topY
            )
        }
        trunkPaint.pathEffect = null
        trunkPaint.color = if (dim) shade(cCream, 0.72f) else cCream
        trunkPaint.strokeWidth = thick
        c.drawPath(trunk, trunkPaint)
        // Ring stripes: a dashed re-stroke follows the same curve.
        trunkPaint.pathEffect = DashPathEffect(floatArrayOf(thick * 0.55f, thick * 1.35f), thick)
        trunkPaint.color = if (dim) shade(cTrunkBand, 0.72f) else cTrunkBand
        trunkPaint.strokeWidth = thick * 0.78f
        c.drawPath(trunk, trunkPaint)
        trunkPaint.pathEffect = null

        drawTuft(c, topX, tuftY, r, if (dim) shade(color, 0.72f) else color)
    }

    /** A fluffy tuft: shadow ball, ring of puffs, bright core, highlight. */
    private fun drawTuft(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = shade(color, 0.72f)
        c.drawCircle(cx + r * 0.05f, cy + r * 0.15f, r * 0.90f, paint)
        paint.color = color
        for (i in 0 until 7) {
            val a = i / 7f * (2 * Math.PI).toFloat() + 0.35f
            val pr = r * (0.42f + 0.09f * ((i * 5) % 3))
            c.drawCircle(
                cx + kotlin.math.cos(a) * r * 0.55f,
                cy + sin(a) * r * 0.50f, pr, paint
            )
        }
        c.drawCircle(cx, cy, r * 0.80f, paint)
        paint.color = lighten(color, 0.28f)
        c.drawCircle(cx - r * 0.26f, cy - r * 0.30f, r * 0.42f, paint)
        paint.color = withAlpha(Color.WHITE, 77)
        c.drawCircle(cx - r * 0.38f, cy - r * 0.44f, r * 0.18f, paint)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    // ---------------- Calibration screen ----------------
    private fun drawCalibration(c: Canvas, w: Float, h: Float) {
        val aligned = isFaceAligned()
        val cx = w / 2f
        val cy = h / 2f
        val radius = h * 0.30f
        val t = System.currentTimeMillis()

        // Big target circle: red/yellow → flashing green when aligned.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 14f
        paint.color = when {
            aligned && (t / 150) % 2 == 0L -> cLime
            aligned -> Color.parseColor("#33FF99")
            faceVisible -> cYellow
            else -> cRed
        }
        c.drawCircle(cx, cy, radius, paint)

        // Soft glow ring
        paint.strokeWidth = 4f
        paint.alpha = 90
        c.drawCircle(cx, cy, radius + 18f, paint)
        paint.alpha = 255

        // Live face dot so the child can "steer" into the circle.
        if (faceVisible) {
            paint.style = Paint.Style.FILL
            paint.color = cCyan
            c.drawCircle(faceX * w, faceY * h, 26f, paint)
        }

        textPaint.color = Color.WHITE
        textPaint.textSize = h * 0.07f
        c.drawText("Put your face in the circle!", cx, cy - radius - h * 0.06f, textPaint)

        textPaint.textSize = h * 0.045f
        textPaint.color = when {
            !faceVisible -> cRed
            faceSize > MAX_FACE_SIZE -> cYellow
            faceSize < MIN_FACE_SIZE -> cYellow
            else -> cLime
        }
        val hint = when {
            !faceVisible -> "I can't see you yet! 🙈"
            faceSize > MAX_FACE_SIZE -> "Take a step back! ⬅️"
            faceSize < MIN_FACE_SIZE -> "Come a little closer! ➡️"
            !aligned -> "Almost there…"
            else -> "Perfect! Hold still… ⭐"
        }
        c.drawText(hint, cx, cy + radius + h * 0.09f, textPaint)
    }

    private fun drawCountdown(c: Canvas, w: Float, h: Float) {
        val elapsed = System.currentTimeMillis() - countdownStartMs
        val n = (3 - elapsed / 1000).coerceAtLeast(1)
        val phase = (elapsed % 1000) / 1000f
        textPaint.color = cPink
        textPaint.textSize = h * (0.35f - 0.1f * phase)   // shrinking pop
        c.drawText("$n", w / 2f, h / 2f + textPaint.textSize / 3f, textPaint)
    }

    // ---------------- Game world ----------------
    private fun drawWorld(c: Canvas, w: Float, h: Float) {
        items.forEach { drawItem(c, w, h, it) }
        drawBear(c, w, h)
        paint.style = Paint.Style.FILL
        particles.forEach { p ->
            paint.color = p.color
            paint.alpha = (255 * p.life).toInt().coerceIn(0, 255)
            c.drawCircle(p.x * w, p.y * h, 8f * p.life + 3f, paint)
        }
        paint.alpha = 255
    }

    private fun drawItem(c: Canvas, w: Float, h: Float, item: Item) {
        val x = item.x * w
        val y = item.y * h
        val r = ITEM_HALF * h
        paint.style = Paint.Style.FILL
        if (item.good) {
            // Truffula fruit: a fluffy pom-pom with a tiny stem.
            val color = fruitColors[item.kind]
            val bob = sin(item.sway + item.y * 9f) * r * 0.08f
            paint.color = cCream
            c.drawRect(x - r * 0.06f, y - r * 1.1f, x + r * 0.06f, y - r * 0.4f, paint)
            paint.color = shade(color, 0.72f)            // under-shadow for depth
            c.drawCircle(x + bob + r * 0.06f, y + r * 0.14f, r * 0.82f, paint)
            paint.color = color
            c.drawCircle(x + bob, y, r * 0.85f, paint)
            c.drawCircle(x - r * 0.5f + bob, y - r * 0.25f, r * 0.5f, paint)
            c.drawCircle(x + r * 0.5f + bob, y - r * 0.25f, r * 0.5f, paint)
            c.drawCircle(x + bob, y - r * 0.45f, r * 0.55f, paint)
            paint.color = lighten(color, 0.3f)
            c.drawCircle(x - r * 0.3f + bob, y - r * 0.35f, r * 0.34f, paint)
            paint.color = withAlpha(Color.WHITE, 90)
            c.drawCircle(x - r * 0.38f + bob, y - r * 0.48f, r * 0.15f, paint)
        } else when (item.kind) {
            0 -> { // rock
                paint.color = Color.GRAY
                c.drawCircle(x, y, r * 0.9f, paint)
                paint.color = Color.DKGRAY
                c.drawCircle(x - r * 0.3f, y - r * 0.2f, r * 0.35f, paint)
            }
            else -> { // muddy boot
                paint.color = Color.parseColor("#6B4A2B")
                c.drawRect(x - r * .5f, y - r, x + r * .2f, y + r * .5f, paint)
                c.drawRect(x - r * .5f, y + r * .2f, x + r, y + r, paint)
            }
        }
    }

    private fun drawBear(c: Canvas, w: Float, h: Float) {
        val now = System.currentTimeMillis()
        val dizzy = now < bearDizzyUntil
        val wobble = if (dizzy) sin(now / 40.0).toFloat() * 14f * (h / 800f) else 0f

        val cx = bearX * w + wobble
        val gy = 0.95f * h                  // feet on the grass
        val u = 0.24f * h                   // total bear height
        val breathe = sin(now / 420.0).toFloat() * u * 0.012f   // gentle idle bounce

        paint.style = Paint.Style.FILL
        // Feet
        paint.color = cBearFurDark
        ell(c, cx - u * 0.16f, gy - u * 0.03f, u * 0.115f, u * 0.06f)
        ell(c, cx + u * 0.16f, gy - u * 0.03f, u * 0.115f, u * 0.06f)
        // Arms, raised to catch (behind the body)
        paint.color = cBearFur
        ell(c, cx - u * 0.33f, gy - u * 0.60f, u * 0.15f, u * 0.062f, -41f)
        ell(c, cx + u * 0.33f, gy - u * 0.60f, u * 0.15f, u * 0.062f, 41f)
        ell(c, cx - u * 0.42f, gy - u * 0.70f + breathe, u * 0.068f, u * 0.068f) // paws
        ell(c, cx + u * 0.42f, gy - u * 0.70f + breathe, u * 0.068f, u * 0.068f)
        // Body + tummy
        ell(c, cx, gy - u * 0.32f, u * 0.30f, u * 0.34f)
        paint.color = cBearMuzzle
        ell(c, cx, gy - u * 0.27f, u * 0.185f, u * 0.22f)
        // Head with cheek fluff
        paint.color = cBearFur
        ell(c, cx - u * 0.235f, gy - u * 0.66f + breathe, u * 0.085f, u * 0.085f)
        ell(c, cx + u * 0.235f, gy - u * 0.66f + breathe, u * 0.085f, u * 0.085f)
        c.drawCircle(cx, gy - u * 0.72f + breathe, u * 0.26f, paint)
        // Ears
        ell(c, cx - u * 0.17f, gy - u * 0.93f + breathe, u * 0.09f, u * 0.09f)
        ell(c, cx + u * 0.17f, gy - u * 0.93f + breathe, u * 0.09f, u * 0.09f)
        paint.color = cBearMuzzle
        ell(c, cx - u * 0.17f, gy - u * 0.93f + breathe, u * 0.045f, u * 0.045f)
        ell(c, cx + u * 0.17f, gy - u * 0.93f + breathe, u * 0.045f, u * 0.045f)
        // Muzzle, open mouth (the catcher), tongue, nose
        ell(c, cx, gy - u * 0.63f + breathe, u * 0.15f, u * 0.115f)
        paint.color = cBearDark
        ell(c, cx, gy - u * 0.60f + breathe, u * 0.092f, u * 0.072f)
        paint.color = cTongue
        ell(c, cx, gy - u * 0.567f + breathe, u * 0.052f, u * 0.032f)
        paint.color = cBearDark
        ell(c, cx, gy - u * 0.688f + breathe, u * 0.048f, u * 0.032f)
        // Eyes — swirly when dizzy (classic mode only)
        val ey = gy - u * 0.79f + breathe
        if (dizzy) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = u * 0.028f
            paint.color = Color.WHITE
            c.drawCircle(cx - u * 0.105f, ey, u * 0.055f, paint)
            c.drawCircle(cx + u * 0.105f, ey, u * 0.055f, paint)
            c.drawArc(cx - u * 0.127f, ey - u * 0.022f, cx - u * 0.083f, ey + u * 0.022f, 0f, 260f, false, paint)
            c.drawArc(cx + u * 0.083f, ey - u * 0.022f, cx + u * 0.127f, ey + u * 0.022f, 90f, 260f, false, paint)
            paint.style = Paint.Style.FILL
        } else {
            paint.color = Color.WHITE
            ell(c, cx - u * 0.105f, ey, u * 0.052f, u * 0.062f)
            ell(c, cx + u * 0.105f, ey, u * 0.052f, u * 0.062f)
            paint.color = Color.parseColor("#2B1B0E")
            ell(c, cx - u * 0.096f, ey + u * 0.012f, u * 0.028f, u * 0.032f)
            ell(c, cx + u * 0.096f, ey + u * 0.012f, u * 0.028f, u * 0.032f)
            paint.color = Color.WHITE
            c.drawCircle(cx - u * 0.088f, ey, u * 0.010f, paint)
            c.drawCircle(cx + u * 0.104f, ey, u * 0.010f, paint)
        }
    }

    private fun drawHud(c: Canvas, w: Float, h: Float) {
        val originalAlign = textPaint.textAlign

        // Big bubbly score, top-center with outline for pop.
        textPaint.textSize = h * 0.12f
        textPaint.style = Paint.Style.STROKE
        textPaint.strokeWidth = 10f
        textPaint.color = cSkyTop
        textPaint.textAlign = Paint.Align.CENTER
        c.drawText("🍒 $score", w / 2f, h * 0.16f, textPaint)
        textPaint.style = Paint.Style.FILL
        textPaint.color = cYellow
        c.drawText("🍒 $score", w / 2f, h * 0.16f, textPaint)

        if (activeMode == GameMode.FRUIT_FRENZY) {
            if (activeRoundSeconds > 0) {
                // Countdown clock under the score — turns red for the final 10 s.
                val secondsLeft = (activeRoundSeconds - playElapsedSec.toInt()).coerceAtLeast(0)
                textPaint.textSize = h * 0.07f
                textPaint.color = if (secondsLeft <= 10) cRed else Color.WHITE
                c.drawText(formatTime(secondsLeft), w / 2f, h * 0.25f, textPaint)

                textPaint.textSize = h * 0.035f
                textPaint.color = Color.parseColor("#88FFFFFF")
                c.drawText("Best: $highScore", w / 2f, h * 0.30f, textPaint)
            } else {
                // Endless: no clock, no pressure — just the score and the best.
                textPaint.textSize = h * 0.035f
                textPaint.color = Color.parseColor("#88FFFFFF")
                c.drawText("Best: $highScore", w / 2f, h * 0.22f, textPaint)
            }
        } else {
            textPaint.textSize = h * 0.045f
            textPaint.color = cCyan
            c.drawText("Best: $highScore", w / 2f, h * 0.22f, textPaint)

            textPaint.textSize = h * 0.035f
            textPaint.color = Color.parseColor("#88FFFFFF")
            c.drawText("Difficulty: $difficulty", w / 2f, h * 0.27f, textPaint)

            // Strike indicators sit just below the canopy tufts so they never
            // fight the tuft colors (or the menu button / PiP preview) for
            // legibility.
            val indicatorY = h * 0.18f
            textPaint.textSize = h * 0.04f
            textPaint.textAlign = Paint.Align.LEFT
            textPaint.color = Color.WHITE
            c.drawText("Rocks: ", w * 0.14f, indicatorY, textPaint)
            val badText = (1..3).joinToString(" ") { i ->
                if (i <= badItemsEaten) "💥" else "⚪"
            }
            textPaint.color = cRed
            c.drawText(badText, w * 0.14f + textPaint.measureText("Rocks: "), indicatorY, textPaint)

            textPaint.textAlign = Paint.Align.RIGHT
            textPaint.color = Color.WHITE
            val fruitsText = (1..3).joinToString(" ") { i ->
                if (i <= fruitsDropped) "❌" else "🍒"
            }
            val label = "Dropped: "
            c.drawText(fruitsText, w * 0.86f, indicatorY, textPaint)
            textPaint.color = cLime
            c.drawText(label, w * 0.86f - textPaint.measureText(fruitsText) - 10f, indicatorY, textPaint)
        }

        textPaint.textAlign = originalAlign
    }

    /**
     * Milestone overlay during play: the Lorax slides up from the bottom-left
     * with a cheer bubble while a big score number pops in the middle.
     */
    private fun drawMilestone(c: Canvas, w: Float, h: Float) {
        val now = System.currentTimeMillis()
        if (now >= milestoneUntilMs) return
        val remaining = (milestoneUntilMs - now).toFloat()
        val elapsed = MILESTONE_MS - remaining

        // Slide up over the first 300 ms, hold, drop back the last 300 ms.
        val rise = when {
            elapsed < 300f -> elapsed / 300f
            remaining < 300f -> remaining / 300f
            else -> 1f
        }
        drawLorax(c, w, h, w * 0.13f, rise, milestoneCheer)

        // Big popping "20!" in the middle: quick scale-in, fade at the end.
        val popScale = (elapsed / 150f).coerceAtMost(1f)
        val alpha = ((remaining / 300f).coerceAtMost(1f) * 255).toInt()
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = h * 0.22f * popScale
        textPaint.style = Paint.Style.STROKE
        textPaint.strokeWidth = 12f
        textPaint.color = cSkyTop
        textPaint.alpha = alpha
        c.drawText(milestoneText, w / 2f, h * 0.5f, textPaint)
        textPaint.style = Paint.Style.FILL
        textPaint.color = cYellow
        textPaint.alpha = alpha
        c.drawText(milestoneText, w / 2f, h * 0.5f, textPaint)
        textPaint.alpha = 255
    }

    /**
     * A friendly Lorax: orange, round, and mostly mustache.
     * [rise] is 0..1 — how far he has popped up above the grass line.
     * [scale] shrinks him where screen text needs the room.
     */
    private fun drawLorax(
        c: Canvas, w: Float, h: Float, cx: Float, rise: Float, bubble: String, scale: Float = 1f
    ) {
        val bodyB = h * 0.24f * scale
        val bodyW = bodyB * 0.62f
        // Feet start below the screen edge and rise to stand on the grass.
        val baseY = h * 1.02f - (h * 0.06f + bodyB) * rise

        paint.style = Paint.Style.FILL
        // Feet
        paint.color = shade(cLoraxOrange, 0.82f)
        ell(c, cx - bodyW * 0.24f, baseY - bodyB * 0.02f, bodyW * 0.17f, bodyB * 0.045f)
        ell(c, cx + bodyW * 0.24f, baseY - bodyB * 0.02f, bodyW * 0.17f, bodyB * 0.045f)
        // Arms raised in a cheer, with mitts
        paint.color = cLoraxOrange
        ell(c, cx - bodyW * 0.55f, baseY - bodyB * 0.66f, bodyB * 0.115f, bodyB * 0.05f, -46f)
        ell(c, cx + bodyW * 0.55f, baseY - bodyB * 0.66f, bodyB * 0.115f, bodyB * 0.05f, 46f)
        ell(c, cx - bodyW * 0.68f, baseY - bodyB * 0.76f, bodyB * 0.052f, bodyB * 0.052f)
        ell(c, cx + bodyW * 0.68f, baseY - bodyB * 0.76f, bodyB * 0.052f, bodyB * 0.052f)
        // Egg body with side fur puffs
        ell(c, cx - bodyW * 0.46f, baseY - bodyB * 0.36f, bodyW * 0.10f, bodyW * 0.10f)
        ell(c, cx + bodyW * 0.46f, baseY - bodyB * 0.36f, bodyW * 0.10f, bodyW * 0.10f)
        ell(c, cx - bodyW * 0.40f, baseY - bodyB * 0.18f, bodyW * 0.10f, bodyW * 0.10f)
        ell(c, cx + bodyW * 0.40f, baseY - bodyB * 0.18f, bodyW * 0.10f, bodyW * 0.10f)
        ell(c, cx, baseY - bodyB * 0.48f, bodyW * 0.50f, bodyB * 0.50f)
        // Face patch
        paint.color = cLoraxFace
        ell(c, cx, baseY - bodyB * 0.72f, bodyW * 0.36f, bodyB * 0.195f)
        // Eyes with highlights
        val ey = baseY - bodyB * 0.765f
        paint.color = Color.WHITE
        ell(c, cx - bodyW * 0.16f, ey, bodyW * 0.10f, bodyW * 0.115f)
        ell(c, cx + bodyW * 0.16f, ey, bodyW * 0.10f, bodyW * 0.115f)
        paint.color = Color.parseColor("#2B1B0E")
        ell(c, cx - bodyW * 0.15f, ey + bodyW * 0.02f, bodyW * 0.048f, bodyW * 0.055f)
        ell(c, cx + bodyW * 0.15f, ey + bodyW * 0.02f, bodyW * 0.048f, bodyW * 0.055f)
        paint.color = Color.WHITE
        c.drawCircle(cx - bodyW * 0.135f, ey, bodyW * 0.018f, paint)
        c.drawCircle(cx + bodyW * 0.165f, ey, bodyW * 0.018f, paint)
        // Bushy brows — thick, angled up and out
        paint.color = cMustache
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = bodyW * 0.145f
        c.drawLine(cx - bodyW * 0.335f, baseY - bodyB * 0.845f, cx - bodyW * 0.06f, baseY - bodyB * 0.875f, paint)
        c.drawLine(cx + bodyW * 0.06f, baseY - bodyB * 0.875f, cx + bodyW * 0.335f, baseY - bodyB * 0.845f, paint)
        paint.strokeCap = Paint.Cap.BUTT
        paint.style = Paint.Style.FILL
        // Nose
        paint.color = cLoraxNose
        ell(c, cx, baseY - bodyB * 0.665f, bodyW * 0.075f, bodyW * 0.055f)
        // THE mustache — one sculpted filled shape, drooping past the body
        val ny = baseY - bodyB * 0.66f
        paint.color = cMustache
        val stache = Path().apply {
            moveTo(cx, ny + bodyB * 0.035f)
            cubicTo(cx - bodyW * 0.30f, ny - bodyB * 0.055f, cx - bodyW * 0.56f, ny - bodyB * 0.005f, cx - bodyW * 0.62f, ny + bodyB * 0.13f)
            cubicTo(cx - bodyW * 0.655f, ny + bodyB * 0.225f, cx - bodyW * 0.615f, ny + bodyB * 0.29f, cx - bodyW * 0.52f, ny + bodyB * 0.285f)
            cubicTo(cx - bodyW * 0.38f, ny + bodyB * 0.275f, cx - bodyW * 0.20f, ny + bodyB * 0.195f, cx - bodyW * 0.075f, ny + bodyB * 0.155f)
            quadTo(cx - bodyW * 0.02f, ny + bodyB * 0.14f, cx, ny + bodyB * 0.145f)
            quadTo(cx + bodyW * 0.02f, ny + bodyB * 0.14f, cx + bodyW * 0.075f, ny + bodyB * 0.155f)
            cubicTo(cx + bodyW * 0.20f, ny + bodyB * 0.195f, cx + bodyW * 0.38f, ny + bodyB * 0.275f, cx + bodyW * 0.52f, ny + bodyB * 0.285f)
            cubicTo(cx + bodyW * 0.615f, ny + bodyB * 0.29f, cx + bodyW * 0.655f, ny + bodyB * 0.225f, cx + bodyW * 0.62f, ny + bodyB * 0.13f)
            cubicTo(cx + bodyW * 0.56f, ny - bodyB * 0.005f, cx + bodyW * 0.30f, ny - bodyB * 0.055f, cx, ny + bodyB * 0.035f)
            close()
        }
        c.drawPath(stache, paint)

        // Speech bubble, fully visible only once he's up
        if (rise > 0.85f && bubble.isNotEmpty()) {
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = h * 0.045f
            val tw = textPaint.measureText(bubble)
            val bx = cx + bodyW * 0.9f
            val by = baseY - bodyB * 1.18f
            val pad = h * 0.02f
            paint.color = Color.WHITE
            val rect = RectF(bx - tw / 2f - pad, by - h * 0.045f - pad, bx + tw / 2f + pad, by + pad)
            c.drawRoundRect(rect, pad, pad, paint)
            val tail = Path()
            tail.moveTo(bx - tw * 0.3f, by + pad * 0.8f)
            tail.lineTo(bx - tw * 0.1f, by + pad * 0.8f)
            tail.lineTo(cx + bodyW * 0.4f, baseY - bodyB * 0.95f)
            tail.close()
            c.drawPath(tail, paint)
            textPaint.color = cSkyTop
            c.drawText(bubble, bx, by - h * 0.008f, textPaint)
        }
    }

    private fun formatTime(totalSeconds: Int): String {
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        return "%d:%02d".format(m, s)
    }

    private fun drawCenteredMessage(c: Canvas, w: Float, h: Float, big: String, small: String) {
        textPaint.color = cCyan
        textPaint.textSize = h * 0.09f
        c.drawText(big, w / 2f, h / 2f - h * 0.02f, textPaint)
        textPaint.color = Color.WHITE
        textPaint.textSize = h * 0.05f
        c.drawText(small, w / 2f, h / 2f + h * 0.08f, textPaint)
    }

    /** Happy end-of-round screen for FRUIT_FRENZY — all cheers, no failure. */
    private fun drawCelebration(c: Canvas, w: Float, h: Float) {
        // Gentle dim so the text pops but the confetti still shows.
        paint.color = Color.parseColor("#882C1E5E")
        paint.style = Paint.Style.FILL
        c.drawRect(0f, 0f, w, h, paint)

        val cx = w / 2f
        val cy = h / 2f
        val bounce = sin(System.currentTimeMillis() / 250.0).toFloat() * h * 0.01f

        // The Lorax joins the party, bouncing in the corner — drawn smaller
        // here so his speech bubble stays clear of the centered text block.
        drawLorax(c, w, h, w * 0.10f, 1f + bounce / h, "HOORAY!", scale = 0.8f)

        textPaint.style = Paint.Style.FILL
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = cYellow
        textPaint.textSize = h * 0.11f
        c.drawText("🎉 TIME'S UP! 🎉", cx, cy - h * 0.16f + bounce, textPaint)

        textPaint.color = Color.WHITE
        textPaint.textSize = h * 0.065f
        val fruitWord = if (score == 1) "fruit" else "fruits"
        c.drawText("You caught $score truffula $fruitWord!", cx, cy - h * 0.04f, textPaint)

        textPaint.textSize = h * 0.05f
        textPaint.color = cPink
        val cheer = when {
            score >= highScore && score > 0 -> "⭐ NEW BEST! Amazing! ⭐"
            score >= activeRoundSeconds / 2 -> "Wow, what a hungry Barbaloot!"
            else -> "Great catching!"
        }
        c.drawText(cheer, cx, cy + h * 0.05f, textPaint)

        textPaint.textSize = h * 0.045f
        textPaint.color = cCyan
        c.drawText("Best: $highScore", cx, cy + h * 0.12f, textPaint)

        // Tap to restart, pulsing
        textPaint.textSize = h * 0.05f
        textPaint.color = cLime
        val alpha = (180 + 75 * sin(System.currentTimeMillis() / 200.0)).toInt().coerceIn(0, 255)
        textPaint.alpha = alpha
        c.drawText("Tap the screen to play again! 🍒", cx, cy + h * 0.21f, textPaint)
        textPaint.alpha = 255
    }

    private fun drawGameOver(c: Canvas, w: Float, h: Float) {
        // Semi-transparent overlay to dim the world
        paint.color = Color.parseColor("#D92C1E5E")
        paint.style = Paint.Style.FILL
        c.drawRect(0f, 0f, w, h, paint)

        val cx = w / 2f
        val cy = h / 2f

        // "GAME OVER"
        textPaint.color = cRed
        textPaint.textSize = h * 0.10f
        textPaint.style = Paint.Style.FILL
        textPaint.textAlign = Paint.Align.CENTER
        c.drawText("GAME OVER", cx, cy - h * 0.15f, textPaint)

        // Reason
        textPaint.color = Color.WHITE
        textPaint.textSize = h * 0.045f
        val reason = when {
            badItemsEaten >= 3 && fruitsDropped >= 3 -> "Ate 3 rocks & dropped 3 fruits!"
            badItemsEaten >= 3 -> "Ouch! You ate 3 rocks! 🪨"
            else -> "Oops! You dropped 3 fruits! 🍒"
        }
        c.drawText(reason, cx, cy - h * 0.07f, textPaint)

        // Score info
        textPaint.textSize = h * 0.06f
        textPaint.color = cYellow
        c.drawText("Score: $score", cx, cy + h * 0.02f, textPaint)

        textPaint.textSize = h * 0.045f
        textPaint.color = cCyan
        c.drawText("Best score: $highScore", cx, cy + h * 0.08f, textPaint)

        // Tap to restart instructions with pulsing alpha
        textPaint.textSize = h * 0.05f
        textPaint.color = cLime
        val alpha = (180 + 75 * sin(System.currentTimeMillis() / 200.0)).toInt().coerceIn(0, 255)
        textPaint.alpha = alpha
        c.drawText("Tap screen to play again! 🎯", cx, cy + h * 0.18f, textPaint)
        textPaint.alpha = 255
    }

    // ======================== Tuning constants ========================
    private companion object {
        // Calibration
        const val CAL_RADIUS = 0.16f       // normalized half-extent of target zone
        const val MIN_FACE_SIZE = 0.10f    // too far if smaller
        const val MAX_FACE_SIZE = 0.45f    // too close if bigger

        // Barbaloot bear
        const val BEAR_Y = 0.86f           // bottom 20% of the screen
        const val BEAR_HALF_W = 0.07f
        const val BEAR_HALF_H = 0.09f
        const val LERP_SPEED = 9f          // render-side smoothing factor
        const val EASY_CATCH_BONUS = 1.4f  // EASY catch box is 40% wider than the bear

        // Milestone cheers (FRUIT_FRENZY)
        const val MILESTONE_EVERY = 10
        const val MILESTONE_MS = 2200L

        // Items — gentle, child-friendly pace
        const val ITEM_HALF = 0.045f
        const val FALL_SPEED = 0.18f       // screen heights / second
    }
}

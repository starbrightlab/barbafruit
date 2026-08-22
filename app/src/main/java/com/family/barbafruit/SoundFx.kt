package com.family.barbafruit

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.SoundPool
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/**
 * Sound system for Barbafruit.
 *
 * - Short one-shot effects use [SoundPool] for near-zero latency.
 * - Looping background music uses [MediaPlayer], which MUST be controlled
 *   from the main thread — all music calls are dispatched via [mainHandler].
 * - Countdown ticks use the lightweight [ToneGenerator].
 */
class SoundFx(context: Context) {

    // Always post MediaPlayer work to the main thread
    private val mainHandler = Handler(Looper.getMainLooper())

    // ======================== SoundPool (short one-shot effects) ========================
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(5)
        .setAudioAttributes(attrs)
        .build()

    private val idEatFruit  = pool.load(context, R.raw.snd_eat_fruit,  1)
    private val idEatBomb   = pool.load(context, R.raw.snd_eat_bomb,   1)
    private val idDropFruit = pool.load(context, R.raw.snd_drop_fruit, 1)
    private val idGo        = pool.load(context, R.raw.snd_go,         1)
    private val idGameOver  = pool.load(context, R.raw.snd_gameover,   1)

    // ======================== MediaPlayer (background loop) ========================
    // Created on the main thread so it's safe to control from mainHandler posts.
    private var musicPlayer: MediaPlayer? = null

    init {
        mainHandler.post {
            musicPlayer = MediaPlayer.create(context, R.raw.snd_gameplay)?.apply {
                isLooping = true
                setVolume(0.5f, 0.5f)
            }
        }
    }

    // ======================== ToneGenerator (countdown ticks) ========================
    private val tone: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, 70)
    } catch (e: RuntimeException) { null }

    // ======================== Public API — safe to call from any thread ========================

    /** Cheerful sound when a fruit is eaten. */
    fun eatFruit() = pool.play(idEatFruit, 1f, 1f, 1, 0, 1f)

    /** Thud/buzz when a bomb is eaten. */
    fun eatBomb() = pool.play(idEatBomb, 1f, 1f, 1, 0, 1f)

    /** Whoosh/thud when a fruit is missed and falls off screen. */
    fun dropFruit() = pool.play(idDropFruit, 1f, 1f, 1, 0, 1f)

    /** Fanfare on GO — also starts the background music. */
    fun go() {
        pool.play(idGo, 1f, 1f, 2, 0, 1f)
        startMusic()
    }

    /** Game-over sting — also stops the background music. */
    fun gameOver() {
        stopMusic()
        pool.play(idGameOver, 1f, 1f, 2, 0, 1f)
    }

    /**
     * Happy end-of-round fanfare for timed mode — reuses the upbeat GO
     * sound (never the game-over sting) and stops the music without
     * starting it again.
     */
    fun celebrate() {
        stopMusic()
        pool.play(idGo, 1f, 1f, 2, 0, 1f)
    }

    /** Short chirpy cheer for mid-round milestones — the GO fanfare pitched up. */
    fun cheer() = pool.play(idGo, 1f, 1f, 1, 0, 1.6f)

    /** Calibration countdown tick: 3… 2… 1… */
    fun tick() = tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 100)

    // ======================== Music control (all dispatched to main thread) ========================

    fun startMusic() = mainHandler.post {
        musicPlayer?.let { if (!it.isPlaying) it.start() }
    }

    fun stopMusic() = mainHandler.post {
        musicPlayer?.let { if (it.isPlaying) { it.pause(); it.seekTo(0) } }
    }

    fun pauseMusic() = mainHandler.post {
        musicPlayer?.let { if (it.isPlaying) it.pause() }
    }

    fun resumeMusic() = mainHandler.post {
        musicPlayer?.let { if (!it.isPlaying) it.start() }
    }

    // ======================== Lifecycle ========================

    fun release() {
        mainHandler.post {
            musicPlayer?.release()
            musicPlayer = null
        }
        pool.release()
        tone?.release()
    }
}

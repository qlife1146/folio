package com.mccal.folio

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.media.session.MediaController
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlin.math.sqrt

private val IdleAudioBands = List(4) { .15f }

/** Real output-mix waveform; no microphone source and no audio samples are retained or saved. */
@Composable
internal fun rememberAudioBands(media: IslandActivity.Media?, enabled: Boolean): State<List<Float>>? {
    val context = LocalContext.current.applicationContext
    val audio = remember(context) { context.getSystemService(AudioManager::class.java) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    val privacyRevision by AppSecurity.revision.collectAsState()
    val mixBlocked by IslandListenerService.outputMixBlocked.collectAsState()
    val bands = remember(media?.token) { mutableStateOf(IdleAudioBands) }
    val generation = remember { AtomicInteger() }
    val receivedAt = remember { AtomicLong() }
    val handler = remember { Handler(Looper.getMainLooper()) }
    var normalMode by remember(audio) { mutableStateOf(audio?.mode == AudioManager.MODE_NORMAL) }
    DisposableEffect(audio, enabled) {
        val listener = AudioManager.OnModeChangedListener { normalMode = it == AudioManager.MODE_NORMAL }
        if (enabled) runCatching {
            normalMode = audio?.mode == AudioManager.MODE_NORMAL
            audio?.addOnModeChangedListener(context.mainExecutor, listener)
        }.onFailure { normalMode = false }
        onDispose { runCatching { audio?.removeOnModeChangedListener(listener) } }
    }
    val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val protected = media?.let { AppSecurity.isProtected(it.packageName) } == true
    val remote = runCatching { media?.controller?.playbackInfo?.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE }
        .getOrDefault(true)
    val allowed = permission && !protected && !mixBlocked && normalMode && !remote && media != null
    val foreground = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val failed = remember(media?.token, enabled, allowed, foreground, media?.playing, privacyRevision) { mutableStateOf(false) }
    DisposableEffect(bands, enabled, allowed, foreground, media?.playing, privacyRevision, failed.value) {
        val request = generation.incrementAndGet()
        var visualizer: Visualizer? = null
        fun release() {
            runCatching { visualizer?.setEnabled(false) }
            runCatching { visualizer?.release() }
            visualizer = null
        }
        if (!allowed) bands.value = IdleAudioBands
        if (enabled && allowed && foreground && media?.playing == true && !failed.value) runCatching {
            val player = media
            fun stopCapture() {
                handler.post {
                    if (generation.compareAndSet(request, request + 1)) {
                        release()
                        bands.value = IdleAudioBands
                        failed.value = true
                    }
                }
            }
            fun outputSafe() = runCatching {
                lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                    audio?.mode == AudioManager.MODE_NORMAL &&
                    player.controller.playbackInfo?.playbackType != MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE &&
                    !IslandListenerService.outputMixBlocked.value && !AppSecurity.isProtected(player.packageName)
            }.getOrDefault(false)
            if (!outputSafe()) { bands.value = IdleAudioBands; return@runCatching }
            val effect = Visualizer(0).also { visualizer = it }
            check(effect.setEnabled(false) == Visualizer.SUCCESS)
            check(effect.setCaptureSize(Visualizer.getCaptureSizeRange()[1]) == Visualizer.SUCCESS)
            check(effect.setScalingMode(Visualizer.SCALING_MODE_AS_PLAYED) == Visualizer.SUCCESS)
            val envelope = OutputWaveformEnvelope()
            val listener = object : Visualizer.OnDataCaptureListener {
                override fun onFftDataCapture(effect: Visualizer, fft: ByteArray, samplingRate: Int) = Unit
                override fun onWaveFormDataCapture(effect: Visualizer, waveform: ByteArray, samplingRate: Int) {
                    if (generation.get() != request) return
                    if (!outputSafe()) {
                        stopCapture()
                        return
                    }
                    val playing = runCatching { IslandListenerService.playbackIsLive(player.controller.playbackState?.state) }
                        .getOrElse { stopCapture(); return }
                    if (!playing) return
                    receivedAt.set(SystemClock.elapsedRealtime())
                    val measured = envelope.measure(waveform)
                    handler.post {
                        if (generation.get() != request) return@post
                        if (!outputSafe()) { stopCapture(); return@post }
                        val stillPlaying = runCatching { IslandListenerService.playbackIsLive(player.controller.playbackState?.state) }
                            .getOrElse { stopCapture(); return@post }
                        if (stillPlaying) {
                            bands.value = measured.mapIndexed { i, value ->
                                val previous = bands.value[i]
                                previous + (value - previous) * (if (value > previous) .8f else .4f)
                            }
                        }
                    }
                }
            }
            receivedAt.set(SystemClock.elapsedRealtime())
            check(effect.setDataCaptureListener(listener, minOf(20_000, Visualizer.getMaxCaptureRate()), true, false) == Visualizer.SUCCESS)
            check(effect.setEnabled(true) == Visualizer.SUCCESS)
        }.onFailure {
            generation.compareAndSet(request, request + 1)
            release()
            bands.value = IdleAudioBands
            failed.value = true
            Diagnostics.event("Media waveform unavailable: ${it.javaClass.simpleName}")
        }
        onDispose { generation.compareAndSet(request, request + 1); release() }
    }
    LaunchedEffect(failed) {
        val watchedGeneration = generation.get()
        if (enabled && allowed && foreground && media?.playing == true) while (!failed.value) {
            delay(2_000)
            if (SystemClock.elapsedRealtime() - receivedAt.get() > 3_000 &&
                generation.compareAndSet(watchedGeneration, watchedGeneration + 1)) {
                failed.value = true
                bands.value = IdleAudioBands
                Diagnostics.event("Media waveform unavailable: capture stopped")
            }
        }
    }
    return if (enabled) bands else null
}

private class OutputWaveformEnvelope {
    private var reference = .08f

    fun measure(waveform: ByteArray): List<Float> {
        if (waveform.size < 4) return IdleAudioBands
        // Equal time windows avoid the fixed bass-to-treble slope of four FFT bands.
        val rms = List(4) { band ->
            val first = band * waveform.size / 4
            val end = (band + 1) * waveform.size / 4
            var energy = 0f
            for (index in first until end) {
                val sample = ((waveform[index].toInt() and 0xff) - 128) / 128f
                energy += sample * sample
            }
            sqrt(energy / (end - first))
        }
        // Share gain across bars and let it decay slowly, preserving beats instead of
        // normalizing each frame to the same height. Keep 8-bit silence/noise at idle.
        reference = maxOf(.08f, reference * .98f, rms.maxOrNull()!! * 1.25f)
        return rms.map { value ->
            .15f + .85f * ((value - .01f) / (reference - .01f)).coerceIn(0f, 1f)
        }
    }
}

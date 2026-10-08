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
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.pow

private val IdleAudioBands = List(4) { .15f }

/** Real output-mix spectrum; no microphone source or audio samples are recorded or saved. */
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
            check(effect.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED) == Visualizer.SUCCESS)
            val listener = object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(effect: Visualizer, waveform: ByteArray, samplingRate: Int) = Unit
                override fun onFftDataCapture(effect: Visualizer, fft: ByteArray, samplingRate: Int) {
                    if (generation.get() != request) return
                    if (!outputSafe()) {
                        stopCapture()
                        return
                    }
                    val playing = runCatching { IslandListenerService.playbackIsLive(player.controller.playbackState?.state) }
                        .getOrElse { stopCapture(); return }
                    if (!playing) return
                    receivedAt.set(SystemClock.elapsedRealtime())
                    val measured = fftBands(fft, samplingRate / 1000f)
                    handler.post {
                        if (generation.get() != request) return@post
                        if (!outputSafe()) { stopCapture(); return@post }
                        val stillPlaying = runCatching { IslandListenerService.playbackIsLive(player.controller.playbackState?.state) }
                            .getOrElse { stopCapture(); return@post }
                        if (stillPlaying) {
                            bands.value = measured.mapIndexed { i, value ->
                                val previous = bands.value[i]
                                previous + (value - previous) * (if (value > previous) .55f else .2f)
                            }
                        }
                    }
                }
            }
            receivedAt.set(SystemClock.elapsedRealtime())
            check(effect.setDataCaptureListener(listener, minOf(20_000, Visualizer.getMaxCaptureRate()), false, true) == Visualizer.SUCCESS)
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

private fun fftBands(fft: ByteArray, sampleRateHz: Float): List<Float> {
    if (fft.size < 4 || sampleRateHz <= 0f) return IdleAudioBands
    val binHz = sampleRateHz / fft.size
    val low = maxOf(20f, binHz)
    val high = minOf(16_000f, sampleRateHz / 2f)
    if (high <= low) return IdleAudioBands
    return List(4) { band ->
        val first = ceil(low * (high / low).pow(band / 4f) / binHz).toInt().coerceAtLeast(1)
        val last = (ceil(low * (high / low).pow((band + 1) / 4f) / binHz).toInt() - 1).coerceAtMost(fft.size / 2 - 1)
        var peak = 0f
        for (bin in first..last) peak = maxOf(peak, hypot(fft[2 * bin].toFloat(), fft[2 * bin + 1].toFloat()))
        (ln(1f + peak) / ln(182f)).coerceIn(.15f, 1f)
    }
}

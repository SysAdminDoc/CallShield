package com.sysadmindoc.callshield.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.sin

/**
 * SIT Tone Player — Anti-Autodialer Countermeasure
 *
 * Plays the SIT (Special Information Tone) sequence, which is the
 * "disconnected number" signal used by the phone network. When played
 * at the start of a call, automated dialers detect this tone and
 * automatically remove the number from their call lists.
 *
 * This is the same technique YouMail uses to "poison" robocaller lists.
 *
 * SIT tone specification (ITU-T E.180/Q.35):
 *   Segment 1: 985.2 Hz for 380ms
 *   Segment 2: 1428.5 Hz for 380ms
 *   Segment 3: 1776.7 Hz for 380ms
 *   Silence:   ~100ms gap between segments
 *
 * USAGE: Tap "Play SIT Tone" button in the Caller ID overlay during an
 * active call. The tone plays via the call audio path (STREAM_VOICE_CALL)
 * on supported devices. Whether the caller hears it depends on the device
 * audio routing, but it works on most Android phones with the earpiece.
 *
 * NOTE: This cannot be played automatically before the call connects.
 * Android requires user interaction for call audio injection.
 */
object SitTonePlayer {

    private const val SAMPLE_RATE = 44100
    private const val CHANNELS = AudioFormat.CHANNEL_OUT_MONO
    private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

    // SIT tone frequencies and durations (ms)
    private val SIT_SEGMENTS = listOf(
        Pair(985.2,  380),  // Low
        Pair(1428.5, 380),  // Mid
        Pair(1776.7, 380),  // High
    )

    private const val SEGMENT_GAP_MS = 80   // Silence between tones
    private const val FADE_MS = 15          // Fade in/out to avoid clicks

    // AtomicBoolean rather than @Volatile + guarded write: two coroutines
    // entering play() concurrently would both see `false` on a plain
    // @Volatile and both advance to the `isPlaying = true` line before
    // either rejected the other. compareAndSet(false, true) is the single
    // atomic step that admits exactly one winner.
    private val playing = AtomicBoolean(false)

    /**
     * Play the full SIT tone sequence.
     * Must be called from a coroutine; runs on Dispatchers.IO.
     * Safe to call multiple times — concurrent plays are rejected.
     */
    suspend fun play(context: Context) = withContext(Dispatchers.IO) {
        if (!playing.compareAndSet(false, true)) return@withContext
        try {
            playSequence(context)
        } finally {
            playing.set(false)
        }
    }

    fun isPlaying(): Boolean = playing.get()

    private suspend fun playSequence(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Request audio focus on the voice call stream so it routes through earpiece
        @Suppress("DEPRECATION")
        am.requestAudioFocus(
            null,
            AudioManager.STREAM_VOICE_CALL,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        )

        try {
            for ((freq, durationMs) in SIT_SEGMENTS) {
                playTone(freq, durationMs)
                delay(SEGMENT_GAP_MS.toLong())
            }
            // Play the sequence twice for robustness against dialers that
            // sample the first 100ms and may miss the initial segment.
            delay(300L)
            for ((freq, durationMs) in SIT_SEGMENTS) {
                playTone(freq, durationMs)
                delay(SEGMENT_GAP_MS.toLong())
            }
        } finally {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
    }

    private suspend fun playTone(frequencyHz: Double, durationMs: Int) {
        val numSamples = SAMPLE_RATE * durationMs / 1000
        val fadeSamples = SAMPLE_RATE * FADE_MS / 1000
        val buffer = ShortArray(numSamples)

        for (i in 0 until numSamples) {
            val sample = sin(2.0 * PI * frequencyHz * i / SAMPLE_RATE)

            // Fade in
            val fadeIn = if (i < fadeSamples) i.toDouble() / fadeSamples else 1.0
            // Fade out
            val fadeOut = if (i > numSamples - fadeSamples)
                (numSamples - i).toDouble() / fadeSamples else 1.0

            buffer[i] = (sample * fadeIn * fadeOut * Short.MAX_VALUE * 0.9).toInt().toShort()
        }

        val minBufSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNELS, ENCODING)
        val bufferSize = maxOf(minBufSize, buffer.size * 2)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(CHANNELS)
                    .setEncoding(ENCODING)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        try {
            track.write(buffer, 0, buffer.size)
            track.play()
            // Wait for playback to complete
            delay(durationMs.toLong() + 10)
        } finally {
            track.stop()
            track.release()
        }
    }
}

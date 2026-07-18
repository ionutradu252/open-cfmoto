// streams the phone's mic to AA over the aap MIC channel so the assistant works. the only
// hands-free way to set a destination on a bike.
package dev.snaipdefix.opencflink.aa

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import dev.snaipdefix.opencflink.aa.proto.Common
import dev.snaipdefix.opencflink.aa.proto.Media
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * the head unit's microphone, as far as AA is concerned.
 *
 * AA sends MICROPHONE_REQUEST(open=true) when the assistant starts. we answer with a
 * MICROPHONE_RESPONSE and push raw pcm up the MIC channel until it asks us to stop. this request
 * used to be logged and ignored, which is why voice never worked.
 *
 * source is the phone's VOICE_RECOGNITION input. the rider's mic is usually a cardo paired to the
 * BIKE, which bridges it to the phone as a bluetooth headset, so prefer the sco device when there is
 * one. (the dash's supportMic:false is about the dash's own mic, not this.)
 *
 * format has to match what ServiceDiscoveryResponse advertises: 16khz, 16 bit, mono.
 */
class AaMicrophone(
    private val context: Context,
    private val transport: AapTransport,
    private val log: (String) -> Unit,
) {
    companion object {
        const val SAMPLE_RATE = 16000
        /** ~20ms of audio per message */
        private const val CHUNK_SAMPLES = SAMPLE_RATE / 50
    }

    @Volatile private var recording = false
    private var recorder: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var sessionId = 0

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** open or close the mic and answer AA */
    fun onRequest(open: Boolean, channel: Int) {
        if (open) start() else stop("AA closed the mic")
        // answer either way so AA isn't left waiting. if the open failed (no permission, mic busy)
        // say so instead of claiming success, or AA sits listening to a stream that never comes.
        val status = if (!open || recording) Common.MessageStatus.STATUS_SUCCESS_VALUE
                     else Common.MessageStatus.STATUS_INTERNAL_ERROR_VALUE
        transport.send(
            AapMessage(
                channel, Media.MsgType.MEDIA_MESSAGE_MICROPHONE_RESPONSE_VALUE,
                Media.MicrophoneResponse.newBuilder()
                    .setStatus(status).setSessionId(sessionId).build()
            )
        )
        log("[MIC] request open=$open → ${if (recording) "recording" else "closed"}")
    }

    fun setSessionId(id: Int) { sessionId = id }

    private fun start() {
        if (recording) return
        if (!hasPermission()) {
            log("[MIC] no RECORD_AUDIO permission — voice won't work. Grant it in the app.")
            return
        }
        try {
            preferBluetoothMic()
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(CHUNK_SAMPLES * 2 * 4)
            val r = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf
            )
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                log("[MIC] AudioRecord init failed"); r.release(); return
            }
            recorder = r
            recording = true
            r.startRecording()
            thread = thread(name = "aa-mic", isDaemon = true) { pump(r) }
            log("[MIC] recording started (${SAMPLE_RATE}Hz mono) → Android Auto")
        } catch (e: Exception) {
            log("[MIC] start failed: $e")
            recording = false
        }
    }

    /** prefer the bluetooth mic (cardo -> bike -> phone). the phone's own is useless in a pocket. */
    private fun preferBluetoothMic() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val bt = am.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }
                if (bt != null) {
                    am.setCommunicationDevice(bt)
                    log("[MIC] using Bluetooth headset mic (${bt.productName})")
                } else {
                    log("[MIC] no Bluetooth mic available — using the phone's mic")
                }
            } else {
                @Suppress("DEPRECATION")
                am.startBluetoothSco()
            }
        } catch (e: Exception) {
            log("[MIC] bluetooth mic routing failed ($e) — using the phone's mic")
        }
    }

    private fun releaseBluetoothMic() {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice()
            else @Suppress("DEPRECATION") am.stopBluetoothSco()
        } catch (_: Exception) {}
    }

    /** read pcm and push it up the MIC channel as aap media data */
    private fun pump(r: AudioRecord) {
        val buf = ShortArray(CHUNK_SAMPLES)
        var sent = 0L
        while (recording) {
            val n = try { r.read(buf, 0, buf.size) } catch (e: Exception) { break }
            if (n <= 0) continue
            try {
                transport.send(micData(buf, n))
                if (++sent <= 2L || sent % 250L == 0L) log("[MIC] chunks sent=$sent")
            } catch (e: Exception) {
                log("[MIC] send failed: $e"); break
            }
        }
    }

    /**
     * one media data message: [channel][flags][len][msgType=DATA][timestamp us BE][pcm...]
     * built by hand because AapMessage's convenience constructor only takes a protobuf body.
     */
    private fun micData(samples: ShortArray, count: Int): AapMessage {
        val pcmBytes = count * 2
        val payload = 8 + pcmBytes
        val total = AapMessage.HEADER_SIZE + MsgType.SIZE + payload
        val data = ByteArray(total)
        data[0] = Channel.ID_MIC.toByte()
        data[1] = 0x0b                                   // media data, not a control message
        Utils.intToBytes(MsgType.SIZE + payload, 2, data)
        data[4] = 0                                       // msgType hi — MEDIA_MESSAGE_DATA (0)
        data[5] = 0                                       // msgType lo
        val bb = ByteBuffer.wrap(data, AapMessage.HEADER_SIZE + MsgType.SIZE, payload)
            .order(ByteOrder.BIG_ENDIAN)
        bb.putLong(SystemClock.elapsedRealtimeNanos() / 1000)
        bb.order(ByteOrder.LITTLE_ENDIAN)                 // PCM16 is little-endian
        for (i in 0 until count) bb.putShort(samples[i])
        return AapMessage(
            Channel.ID_MIC, 0x0b, Media.MsgType.MEDIA_MESSAGE_DATA_VALUE,
            AapMessage.HEADER_SIZE + MsgType.SIZE, total, data,
        )
    }

    fun stop(reason: String) {
        if (!recording && recorder == null) return
        recording = false
        try { thread?.interrupt() } catch (_: Exception) {}
        thread = null
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        releaseBluetoothMic()
        log("[MIC] stopped ($reason)")
    }
}

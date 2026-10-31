package com.zaferyilmaz.zoro

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

// ==========================================
// ZORO 2.1: KONUSMA MOTORU
// ==========================================
// Dogal ses aciksa cevap sunucudan (OpenAI sesi) akarak calinir.
// Kapaliysa, internet yoksa, butce dolduysa ya da ses gelmezse telefonun
// en kaliteli Turkce sesi okur. Her konusma tam bir kez biter:
// onFinished(id, completed); yeni konusma eskisini keser (completed=false).
// Ayni kimlikle (orn. "ZORO_ERROR") ust uste gelen konusmada eskisi
// sessizce birakilir, bitisi bildirilmez: yenisinin bitisi beklenir.

class ZoroSpeaker(
    context: Context,
    private val onFinished: (String, Boolean) -> Unit
) {

    companion object {
        private const val TAG = "ZORO_SPEAKER"
        private const val SAMPLE_RATE = 24000

        // Telefonun Turkce sesleri arasindan: internetsiz calisan, en kaliteli.
        fun bestTurkishVoice(tts: TextToSpeech): Voice? = try {
            tts.voices
                ?.filter {
                    it.locale.language == "tr" &&
                            !it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
                }
                ?.sortedWith(
                    compareBy<Voice> { it.isNetworkConnectionRequired }
                        .thenByDescending { it.quality }
                )
                ?.firstOrNull()
        } catch (error: Exception) {
            Log.w(TAG, "Ses listesi okunamadi", error)
            null
        }
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private val http = ZoroServer.buildHttpClient().newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        // Ilk ses parcasi gelmezse telefonun sesine gecilir.
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // Her konusmanin ic kimligi ("id#sira") -> disariya bildirilecek id.
    // remove() basarili olan tek cagri bitisi bildirir.
    private val open = ConcurrentHashMap<String, String>()
    private val counter = AtomicLong()

    @Volatile
    private var current: String? = null

    init {
        tts = TextToSpeech(appContext) { status ->
            main.post {
                val engine = tts
                if (status == TextToSpeech.SUCCESS && engine != null) {
                    engine.language = Locale.forLanguageTag("tr-TR")
                    bestTurkishVoice(engine)?.let {
                        engine.voice = it
                        Log.i(TAG, "Telefon sesi: ${it.name} kalite=${it.quality} internet=${it.isNetworkConnectionRequired}")
                    }
                    engine.setPitch(0.85f)
                    engine.setSpeechRate(0.95f)
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            utteranceId?.let { finish(it, true) }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            utteranceId?.let { finish(it, true) }
                        }

                        override fun onStop(utteranceId: String?, interrupted: Boolean) {
                            utteranceId?.let { finish(it, false) }
                        }
                    })
                    ttsReady = true
                } else {
                    Log.e(TAG, "Telefonun konusma motoru acilamadi")
                }
            }
        }
    }

    private fun finish(token: String, completed: Boolean) {
        val id = open.remove(token) ?: return
        main.post { onFinished(id, completed) }
    }

    // Yeni konusma: oncekini keser. Farkli id ise onun bitisi (kesildi) bildirilir.
    private fun begin(id: String): String {

        val token = id + "#" + counter.incrementAndGet()
        val previous = current

        current = token
        open[token] = id

        if (previous != null) {
            tts?.stop()
            val previousId = open.remove(previous)
            if (previousId != null && previousId != id) {
                main.post { onFinished(previousId, false) }
            }
        }

        return token
    }

    // Ana is parcaciginda cagrilir.
    fun speak(text: String, id: String) {

        val token = begin(id)

        if (text.isBlank()) {
            finish(token, true)
            return
        }

        if (ZoroPrefs.naturalVoice(appContext)) {
            Thread({ playNatural(JSONObject().put("text", text), text, token, null) }, "zoro-voice").start()
        } else {
            speakDevice(text, token)
        }
    }

    fun stop() {
        val token = current ?: return
        current = null
        tts?.stop()
        finish(token, false)
    }

    fun shutdown() {
        stop()
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
    }

    private fun speakDevice(text: String, token: String) {

        main.post {
            val engine = tts
            if (current != token) {
                finish(token, false)
                return@post
            }
            if (!ttsReady || engine == null) {
                finish(token, true)
                return@post
            }
            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, token)
            if (result != TextToSpeech.SUCCESS) {
                finish(token, true)
            }
        }
    }

    // ==========================================
    // DOGAL SES (sunucu /tts, 24 kHz 16-bit mono PCM akisi)
    // ==========================================

    private class Focus(private val audio: AudioManager, attributes: AudioAttributes) {

        private val request: AudioFocusRequest? =
            if (Build.VERSION.SDK_INT >= 26)
                AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attributes)
                    .build()
            else null

        fun take() {
            if (Build.VERSION.SDK_INT >= 26) {
                audio.requestAudioFocus(request!!)
            } else {
                @Suppress("DEPRECATION")
                audio.requestAudioFocus(null, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            }
        }

        fun release() {
            if (Build.VERSION.SDK_INT >= 26) {
                audio.abandonAudioFocusRequest(request!!)
            } else {
                @Suppress("DEPRECATION")
                audio.abandonAudioFocus(null)
            }
        }
    }

    // onHttpError null: sorun olursa telefonun sesi okur. Dolu: sorun bildirilir, okunmaz.
    private fun playNatural(payload: JSONObject, text: String, token: String, onHttpError: ((Int) -> Unit)?) {

        var track: AudioTrack? = null
        var framesWritten = 0L
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val focus = Focus(appContext.getSystemService(AudioManager::class.java), attributes)
        var focused = false

        try {
            val body = payload.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder().url(ZoroServer.url("/tts")).post(body).build()

            http.newCall(request).execute().use { response ->

                if (!response.isSuccessful) {
                    // 409 kapali, 402 butce doldu, 502 ses modeli yok: telefonun sesi okur.
                    Log.w(TAG, "Dogal ses yok (${response.code})")
                    if (onHttpError != null) {
                        finish(token, false)
                        onHttpError(response.code)
                    } else {
                        speakDevice(text, token)
                    }
                    return
                }

                val minBuffer = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                val output = AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBuffer * 2, SAMPLE_RATE))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                track = output

                val stream = response.body?.byteStream() ?: throw java.io.IOException("bos ses cevabi")
                val buffer = ByteArray(8192)
                var carry = -1  // 16-bit ornek iki okumaya bolunurse ilk bayt

                while (current == token) {
                    val offset = if (carry >= 0) 1 else 0
                    val read = stream.read(buffer, offset, buffer.size - offset)
                    if (read < 0) break
                    var length = read + offset
                    if (carry >= 0) {
                        buffer[0] = carry.toByte()
                        carry = -1
                    }
                    if (length % 2 == 1) {
                        carry = buffer[length - 1].toInt() and 0xFF
                        length -= 1
                    }
                    if (length == 0) continue
                    if (framesWritten == 0L) {
                        focus.take()
                        focused = true
                        output.play()
                    }
                    val written = output.write(buffer, 0, length)
                    if (written < 0) throw java.io.IOException("AudioTrack yazma hatasi $written")
                    framesWritten += written / 2
                }

                if (current != token) {
                    finish(token, false)
                    return
                }

                if (framesWritten == 0L) {
                    if (onHttpError != null) finish(token, false) else speakDevice(text, token)
                    return
                }

                // Sona yarim saniye sessizlik: kisa cevaplar ve ag takilmasindan
                // sonra kalan son parca da calma esigini gecsin.
                val silence = ByteArray(SAMPLE_RATE)
                output.write(silence, 0, silence.size)

                // Kalan konusma kadar bekle (sessizlik dahil degil).
                val remaining = (framesWritten - output.playbackHeadPosition).coerceAtLeast(0)
                val deadline = System.currentTimeMillis() + remaining * 1000 / SAMPLE_RATE + 1500
                while (current == token && output.playbackHeadPosition < framesWritten &&
                    System.currentTimeMillis() < deadline
                ) {
                    Thread.sleep(40)
                }
                finish(token, current == token)
            }

        } catch (error: Exception) {
            Log.w(TAG, "Dogal ses hatasi", error)
            if (framesWritten == 0L && onHttpError != null) {
                finish(token, false)
                onHttpError(-1)
            } else if (framesWritten == 0L && current == token) {
                speakDevice(text, token)
            } else {
                // Cevabin bir kismi duyuldu: isi yarim birakma.
                finish(token, current == token)
            }
        } finally {
            try {
                track?.pause()
                track?.flush()
                track?.release()
            } catch (_: Exception) {
            }
            if (focused) focus.release()
        }
    }

    // Ayarlar ekranindaki "Dinle" dugmesi: secilen sesle kisa bir ornek
    // (dogal ses kapaliyken de; maliyeti bir cevap kadar).
    fun preview(voice: String, id: String, onError: (String) -> Unit) {

        val token = begin(id)
        val text = "Merhaba Zafer Bey, ben Zoro. Sesim böyle olacak."
        val payload = JSONObject().put("text", text).put("preview", true).put("voice", voice)

        Thread({
            playNatural(payload, text, token) { code ->
                main.post {
                    onError(
                        when (code) {
                            402 -> "Aylık bütçe dolduğu için doğal ses şu an kapalı."
                            -1 -> "Sunucuya bağlanamadım."
                            else -> "Doğal ses şu an alınamadı ($code)."
                        }
                    )
                }
            }
        }, "zoro-voice-preview").start()
    }
}

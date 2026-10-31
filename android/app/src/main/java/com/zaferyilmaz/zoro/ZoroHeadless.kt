package com.zaferyilmaz.zoro

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

// ==========================================
// ZORO 2.1: EKRANSIZ KOMUT
// ==========================================
// Uygulama ekrani tamamen kapatilmisken ses servisi komutu kendisi isler:
// sunucuya sorar, takvim/alarm isini yapar, cevabi okur, sonra arama gibi
// telefon isini yapar (ekran acamadigi isler bildirime duser).
// Ekran aciksa komut eskisi gibi ekrana gider.

object ZoroHeadless {

    private const val TAG = "ZORO_HEADLESS"
    private const val BRIEFING_CHANNEL = "zoro_morning_briefing"
    private const val BRIEFING_NOTIFICATION = 100011

    private val main = Handler(Looper.getMainLooper())
    private val http by lazy { ZoroServer.buildHttpClient() }

    private data class ServerReply(val code: Int, val json: JSONObject?)

    fun handleCommand(service: ZoroVoiceService, command: String) {
        request(service, "/ask", command, briefing = false)
    }

    fun briefing(service: ZoroVoiceService) {
        request(service, "/briefing", "", briefing = true)
    }

    private fun request(
        service: ZoroVoiceService,
        path: String,
        message: String,
        briefing: Boolean
    ) {

        val context = service.applicationContext

        Thread({

            val reply = try {

                val calendar = try {
                    ZoroActions.calendarContext(context)
                } catch (error: Exception) {
                    null
                }

                val body = ZoroServer.askBody(
                    message = message,
                    calendar = calendar,
                    clientNote = ZoroServer.takeClientNote(),
                    recentCalls = ZoroCallLog.recentCalls(context)
                ).toString().toRequestBody("application/json; charset=utf-8".toMediaType())

                http.newCall(Request.Builder().url(ZoroServer.url(path)).post(body).build())
                    .execute().use { response ->
                        val text = response.body?.string().orEmpty()
                        ServerReply(response.code, try { JSONObject(text) } catch (_: Exception) { null })
                    }

            } catch (error: Exception) {
                Log.e(TAG, "Sunucuya ulasilamadi", error)
                null
            }

            main.post { deliver(service, reply, briefing) }

        }, "zoro-headless").start()
    }

    private fun deliver(service: ZoroVoiceService, reply: ServerReply?, briefing: Boolean) {

        val context = service.applicationContext

        if (reply == null) {
            if (!briefing) {
                service.speakReply("Sunucuya bağlanamadım. İnternet bağlantınızı kontrol edin.", false, null)
            }
            return
        }

        val json = reply.json

        if (reply.code !in 200..299 || json == null) {
            val text = when (reply.code) {
                401, 403 -> "Sunucu kimlik doğrulaması başarısız. Uygulama ayarlarını kontrol edin."
                else -> json?.optString("reply", "").orEmpty()
                    .ifBlank { "Sunucu cevabında bir sorun oluştu." }
            }
            if (!briefing) service.speakReply(text, false, null)
            return
        }

        val replyText = json.optString("reply", "")
        val expectReply = json.optBoolean("expect_reply", false)
        val actions = ZoroActions.list(json) ?: emptyList()

        val failures = ZoroActions.runDataActions(context, actions, ContextReminderPort(context))
        val hasPhoneActions = actions.any { !ZoroActions.isDataAction(it) }

        val spoken =
            if (failures.isNotEmpty())
                failures.joinToString(" ") + (if (hasPhoneActions) " Diğer isteğinizi yapıyorum." else "")
            else replyText

        if (failures.isNotEmpty()) {
            ZoroServer.clientNote = spoken
        }

        if (briefing && spoken.isNotBlank()) {
            showBriefingNotification(context, spoken)
        }

        service.speakReply(spoken, listenForReply = expectReply && failures.isEmpty()) {

            val actionFailures = ZoroActions.runIntentActions(context, actions, inForeground = false)

            if (actionFailures.isNotEmpty()) {
                val text = actionFailures.joinToString(" ")
                // Sunucu bir sonraki istekte ne oldugunu bilsin.
                ZoroServer.clientNote = text
                service.speakReply(text, listenForReply = text.trimEnd().endsWith("?"), after = null)
            }
        }
    }

    private fun showBriefingNotification(context: Context, text: String) {

        if (
            Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        try {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(BRIEFING_CHANNEL) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(BRIEFING_CHANNEL, "Zoro sabah özeti", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }

            val notification = NotificationCompat.Builder(context, BRIEFING_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Zoro sabah özeti")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build()

            NotificationManagerCompat.from(context).notify(BRIEFING_NOTIFICATION, notification)

        } catch (error: Exception) {
            Log.e(TAG, "Sabah ozeti bildirimi gosterilemedi", error)
        }
    }
}

package com.zaferyilmaz.zoro

import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Locale

// ==========================================
// ZORO 2.1: AYARLAR VE MALIYET
// ==========================================
// Ucretli ozellikler (web aramasi, dogal ses) ve telefona ait ozellikler
// buradan acilip kapatilir. Maliyet sunucunun olctugu gercek kullanimdan
// gelir; tahminler "tahmin" diye yazilir.

private val Red = Color(0xFFB71C1C)
private val DarkBlue = Color(0xFF135B8D)
private val LightBlue = Color(0xFFE5F5FF)
private val Page = Color(0xFFF4F8FC)
private val Ink = Color(0xFF162B3A)
private val Muted = Color(0xFF5B6B78)
private val Warn = Color(0xFF8A4B00)

private val tr: Locale = Locale.forLanguageTag("tr-TR")

private fun usd(value: Double): String =
    if (value > 0 && value < 0.01) String.format(tr, "%.4f $", value)
    else String.format(tr, "%.2f $", value)

private fun tl(value: Double, rate: Double?): String {
    if (rate == null || rate <= 0) return ""
    val lira = value * rate
    return if (lira >= 10) String.format(tr, " (≈ %.0f ₺)", lira)
    else String.format(tr, " (≈ %.2f ₺)", lira)
}

// Turkce: virgul ondalik ayiraci ("2,5"). Nokta binlik ayiraci oldugu icin kabul edilmez.
private fun budgetText(value: Double): String =
    java.text.DecimalFormat("0.##", java.text.DecimalFormatSymbols(tr)).format(value)

private fun hasPermission(context: Context, permission: String) =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

@Composable
fun ZoroSettingsScreen(
    http: OkHttpClient,
    onBack: () -> Unit,
    previewVoice: (String, (String) -> Unit) -> Unit,
    requestPermission: (List<String>) -> Unit
) {

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var settings by remember { mutableStateOf<JSONObject?>(null) }
    var voices by remember { mutableStateOf(listOf<String>()) }
    var usage by remember { mutableStateOf<JSONObject?>(null) }
    var serverReady by remember { mutableStateOf(ZoroPrefs.serverHas(context, "settings")) }
    var loading by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf("") }
    var budgetInput by remember { mutableStateOf("") }

    // Ayar kayitlari sirayla: eski cevap yenisinin uzerine yazmasin.
    val saveLock = remember { Mutex() }

    // Izinler ve pil ayari ekran disinda degisebilir: saniyede bir yeniden okunur.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }

    var callLogOn by remember { mutableStateOf(ZoroPrefs.callLog(context)) }
    var calendarWriteOn by remember { mutableStateOf(ZoroPrefs.calendarWrite(context)) }
    var backgroundOn by remember { mutableStateOf(ZoroPrefs.backgroundCommands(context)) }

    fun get(path: String): JSONObject? =
        http.newCall(Request.Builder().url(ZoroServer.url(path)).get().build()).execute().use {
            if (it.isSuccessful) JSONObject(it.body?.string().orEmpty()) else null
        }

    suspend fun reload() {
        loading = true
        val result = withContext(Dispatchers.IO) {
            try {
                ZoroServer.syncSettings(context.applicationContext, http)
                if (!ZoroPrefs.serverHas(context, "settings")) Triple(null, null, false)
                else Triple(get("/settings"), get("/usage"), true)
            } catch (error: Exception) {
                null
            }
        }
        loading = false
        if (result == null) {
            message = "Sunucuya bağlanamadım. İnternet bağlantınızı kontrol edin."
            return
        }
        serverReady = result.third
        result.first?.let { json ->
            settings = json.optJSONObject("settings")
            val list = json.optJSONArray("voices")
            voices = if (list == null) emptyList() else (0 until list.length()).map { list.optString(it) }
            settings?.let {
                ZoroPrefs.applyServerSettings(context, it)
                budgetInput = budgetText(it.optDouble("monthly_budget_usd", 10.0))
            }
        }
        usage = result.second
    }

    fun save(change: JSONObject) {
        scope.launch { saveLock.withLock {
            message = ""
            val saved = withContext(Dispatchers.IO) {
                try {
                    val body = change.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                    http.newCall(Request.Builder().url(ZoroServer.url("/settings")).put(body).build())
                        .execute().use { response ->
                            val json = JSONObject(response.body?.string().orEmpty())
                            if (response.isSuccessful) json.optJSONObject("settings")
                            else {
                                message = json.optString("reply", "Ayar kaydedilemedi.")
                                null
                            }
                        }
                } catch (error: Exception) {
                    message = "Sunucuya bağlanamadım, ayar kaydedilemedi."
                    null
                }
            }
            if (saved != null) {
                settings = saved
                ZoroPrefs.applyServerSettings(context, saved)
                ZoroMorningBriefingScheduler.scheduleNext(context.applicationContext)
                usage = withContext(Dispatchers.IO) {
                    try { get("/usage") } catch (_: Exception) { usage }
                }
            }
        } }
    }

    LaunchedEffect(Unit) { reload() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Page)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Geri", color = DarkBlue, fontSize = 16.sp) }
            Spacer(Modifier.width(4.dp))
            Text("Ayarlar ve maliyet", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
        }

        if (message.isNotBlank()) {
            Text(message, color = Red, fontSize = 14.sp)
        }

        if (loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = DarkBlue)
        }

        // ---------------------------------------------- maliyet ozeti
        val u = usage
        val s = settings
        if (serverReady && u != null) {
            val rate = if (u.isNull("usd_try")) null else u.optDouble("usd_try")
            val total = u.optDouble("total_usd", 0.0)
            val projected = u.optDouble("projected_usd", 0.0)
            val budget = u.optDouble("budget_usd", 0.0)

            Section("Bu ay") {
                Text(usd(total) + tl(total, rate), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Ink)
                Text(
                    "Ay sonu tahmini: " + usd(projected) + tl(projected, rate),
                    fontSize = 15.sp, color = Ink
                )
                if (budget > 0) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { (total / budget).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = if (u.optBoolean("budget_reached")) Red else DarkBlue,
                        trackColor = LightBlue
                    )
                    Text("Aylık bütçe: " + usd(budget) + tl(budget, rate), fontSize = 13.sp, color = Muted)
                }
                if (u.optBoolean("budget_reached")) {
                    Text(
                        "Bütçe doldu: web araması ve doğal ses bu ay kapalı. Zoro'nun yapay zekâsı çalışmaya devam ediyor.",
                        color = Red, fontSize = 13.sp
                    )
                }
                if (u.optBoolean("little_data")) {
                    Text(
                        "Henüz az kullanım var; tahmin birkaç gün sonra netleşir.",
                        color = Muted, fontSize = 13.sp
                    )
                }
                if (u.optBoolean("unknown_price")) {
                    Text(
                        "Kullanılan modelin fiyatı tabloda yok; bazı tutarlar eksik görünebilir.",
                        color = Warn, fontSize = 13.sp
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text("Aylık bütçe sınırı (dolar, 0 = sınırsız)", fontSize = 13.sp, color = Muted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = budgetInput,
                        onValueChange = { typed ->
                            val digits = typed.filter { ch -> ch.isDigit() || ch == ',' }
                            val comma = digits.indexOf(',')
                            // En fazla bir virgul ve virgulden sonra iki hane.
                            budgetInput = (if (comma < 0) digits
                            else digits.substring(0, comma + 1) +
                                    digits.substring(comma + 1).filter { it.isDigit() }.take(2)).take(7)
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.width(120.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = {
                            val value = budgetInput.replace(',', '.').toDoubleOrNull()
                            if (value == null || value > 1000 || (value > 0 && value < 0.01)) {
                                message = "Bütçe 0,01 ile 1000 dolar arasında olmalı (0 = sınırsız)."
                            } else {
                                save(JSONObject().put("monthly_budget_usd", value))
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = DarkBlue)
                    ) { Text("Kaydet") }
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    "Tutarlar OpenAI liste fiyatlarıyla (kontrol: " +
                            u.optString("prices_checked").split("-").reversed().joinToString(".") + ") " +
                            "Zoro'nun ölçtüğü kullanımdan hesaplanır. Kesin fatura OpenAI hesabındadır.",
                    fontSize = 12.sp, color = Muted
                )
            }
        } else if (!loading && !serverReady) {
            Section("Sunucu") {
                Text(
                    "Sunucu henüz güncellenmedi. Maliyet, web araması, doğal ses ve sabah özeti ayarları " +
                            "sunucu güncellenince burada açılır.",
                    fontSize = 14.sp, color = Ink
                )
            }
        }

        // ---------------------------------------------- ucretli ozellikler
        if (serverReady && s != null && u != null) {

            val features = u.optJSONArray("features")
            fun feature(key: String): JSONObject? {
                if (features == null) return null
                for (i in 0 until features.length()) {
                    val item = features.optJSONObject(i)
                    if (item?.optString("key") == key) return item
                }
                return null
            }
            val rate = if (u.isNull("usd_try")) null else u.optDouble("usd_try")

            fun costLine(key: String, assumption: String, alwaysOn: Boolean = false): String {
                val f = feature(key) ?: return ""
                val spent = f.optDouble("cost_usd", 0.0)
                val parts = mutableListOf("Bu ay " + usd(spent))
                if (!f.isNull("per_use_usd")) {
                    parts.add("kullanım başına ≈ " + usd(f.optDouble("per_use_usd")))
                }
                if (!f.isNull("monthly_if_on_usd")) {
                    val monthly = f.optDouble("monthly_if_on_usd")
                    parts.add((if (alwaysOn) "aylık ≈ " else "açıkken aylık ≈ ") + usd(monthly) + tl(monthly, rate) + assumption)
                }
                return parts.joinToString(" · ")
            }

            val asks = if (u.isNull("asks_per_day")) null else u.optDouble("asks_per_day")
            val askBasis = if (asks == null) " (günde 20 komut varsayımıyla)"
            else String.format(tr, " (sizin günde ~%.0f komutunuzla)", asks)

            Section("Ücretli özellikler") {

                FeatureRow(
                    title = "Yapay zekâ",
                    description = "Her komutu anlayan beyin. Her zaman açık.",
                    cost = costLine("beyin", askBasis, alwaysOn = true),
                    checked = null,
                    onChange = {}
                )

                FeatureRow(
                    title = "Güncel bilgi (web araması)",
                    description = "Haber, maç sonucu, nöbetçi eczane gibi güncel sorular.",
                    cost = costLine("web_arama", " (günde 3 arama varsayımıyla)"),
                    checked = s.optBoolean("web_search"),
                    onChange = { save(JSONObject().put("web_search", it)) }
                )

                FeatureRow(
                    title = "Doğal ses",
                    description = "Cevapları robot sesi yerine doğal bir sesle okur.",
                    cost = costLine("dogal_ses", askBasis),
                    checked = s.optBoolean("natural_voice"),
                    onChange = { save(JSONObject().put("natural_voice", it)) }
                )

                if (voices.isNotEmpty()) {
                    Text("Ses seçimi", fontSize = 13.sp, color = Muted)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val selected = s.optString("voice")
                        voices.forEach { voice ->
                            val label = voice.replaceFirstChar { it.titlecase(tr) }
                            if (voice == selected) {
                                Button(
                                    onClick = {},
                                    colors = ButtonDefaults.buttonColors(containerColor = DarkBlue)
                                ) { Text(label) }
                            } else {
                                OutlinedButton(onClick = { save(JSONObject().put("voice", voice)) }) {
                                    Text(label, color = DarkBlue)
                                }
                            }
                        }
                    }
                    TextButton(onClick = {
                        message = ""
                        previewVoice(s.optString("voice")) { error -> message = error }
                    }) { Text("▶ Seçili sesi dinle", color = DarkBlue) }
                }

                if (!u.isNull("tts_shutdown")) {
                    Text(
                        "Not: OpenAI bu ses modelini 6 Ocak 2027'de kapatıyor. O tarihten sonra, " +
                                "ses sağlayıcısı değiştirilene kadar telefonun kendi sesi kullanılır.",
                        fontSize = 12.sp, color = Warn
                    )
                }

                val (hour, minute) = ZoroPrefs.briefingTime(context)
                FeatureRow(
                    title = "Sabah özeti",
                    description = String.format(
                        tr, "Her sabah %02d:%02d'de hava, takvim, aramalar ve kur.", hour, minute
                    ),
                    cost = costLine("sabah_ozeti", " (günde 1 özet)"),
                    checked = s.optBoolean("briefing_enabled"),
                    onChange = { save(JSONObject().put("briefing_enabled", it)) }
                )
                TextButton(onClick = {
                    TimePickerDialog(context, { _, h, m ->
                        save(JSONObject().put("briefing_time", String.format(Locale.US, "%02d:%02d", h, m)))
                    }, hour, minute, true).show()
                }) { Text("Özet saatini değiştir", color = DarkBlue) }
            }
        }

        // ---------------------------------------------- telefona ait
        Section("Telefon özellikleri (ücretsiz)") {

            val callPermission = remember(tick) { hasPermission(context, Manifest.permission.READ_CALL_LOG) }
            FeatureRow(
                title = "Son aramalar",
                description = "\"Beni kim aradı?\", \"Onu geri ara.\"" +
                        if (callLogOn && !callPermission) " İzin gerekiyor." else "",
                cost = "",
                checked = callLogOn,
                onChange = {
                    callLogOn = it
                    ZoroPrefs.setCallLog(context, it)
                    if (it && !callPermission) requestPermission(listOf(Manifest.permission.READ_CALL_LOG))
                }
            )

            val calendarPermission = remember(tick) {
                hasPermission(context, Manifest.permission.WRITE_CALENDAR) &&
                        hasPermission(context, Manifest.permission.READ_CALENDAR)
            }
            FeatureRow(
                title = "Google Takvim'i değiştirebilsin",
                description = "Telefon takvimindeki tek seferlik etkinlikleri sesle taşıyıp silebilir " +
                        "(silmeden önce sorar). Tekrarlayan etkinliklere dokunmaz." +
                        if (calendarWriteOn && !calendarPermission) " İzin gerekiyor." else "",
                cost = "",
                checked = calendarWriteOn,
                onChange = {
                    calendarWriteOn = it
                    ZoroPrefs.setCalendarWrite(context, it)
                    if (it && !calendarPermission) requestPermission(
                        listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
                    )
                }
            )

            FeatureRow(
                title = "Kilitliyken de çalışsın",
                description = "Zoro ekranı kapalıyken de sesli komutu işler. Arka planda dinleme açık olmalı.",
                cost = "",
                checked = backgroundOn,
                onChange = {
                    backgroundOn = it
                    ZoroPrefs.setBackgroundCommands(context, it)
                }
            )

            val restricted = remember(tick) {
                val power = context.getSystemService(PowerManager::class.java)
                power != null && !power.isIgnoringBatteryOptimizations(context.packageName)
            }
            if (backgroundOn && restricted) {
                Text(
                    "Telefon pil tasarrufu için Zoro'yu arka planda durdurabilir.",
                    fontSize = 13.sp, color = Warn
                )
                OutlinedButton(onClick = {
                    try {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + context.packageName)
                            )
                        )
                    } catch (_: Exception) {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }) { Text("Pil kısıtlamasını kaldır", color = DarkBlue) }
            }
        }

        if (serverReady) {
            TextButton(onClick = { scope.launch { reload() } }) { Text("Yenile", color = DarkBlue) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = DarkBlue)
            content()
        }
    }
}

@Composable
private fun FeatureRow(
    title: String,
    description: String,
    cost: String,
    checked: Boolean?,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink)
            Text(description, fontSize = 13.sp, color = Muted)
            if (cost.isNotBlank()) {
                Text(cost, fontSize = 12.sp, color = DarkBlue)
            }
        }
        if (checked != null) {
            Spacer(Modifier.width(8.dp))
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

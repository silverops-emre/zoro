
package com.zaferyilmaz.zoro

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// ==========================================
// ZORO TAKVIM EKRANI
// ==========================================

@Composable
fun ZoroCalendarScreen(
    onBack: () -> Unit,
    requestCalendarPermission: () -> Unit
) {

    val context = LocalContext.current

    val manager = remember(context) {
        CalendarManager(context)
    }

    val red = Color(0xFFB71C1C)
    val blue = Color(0xFF135B8D)
    val sky = Color(0xFF27A7E5)
    val background = Color(0xFFF4F8FC)

    val turkish = remember {
        Locale.forLanguageTag("tr-TR")
    }

    val timeZone = remember {
        TimeZone.getTimeZone("Europe/Istanbul")
    }

    val today = remember {
        Calendar.getInstance(timeZone)
    }

    var monthOffset by remember {
        mutableIntStateOf(0)
    }

    var selectedDay by remember {
        mutableIntStateOf(
            today.get(Calendar.DAY_OF_MONTH)
        )
    }

    var refreshKey by remember {
        mutableIntStateOf(0)
    }

    var events by remember {
        mutableStateOf<List<ZoroCalendarEvent>>(
            emptyList()
        )
    }

    var selectedEvent by remember {
        mutableStateOf<ZoroCalendarEvent?>(null)
    }

    var showEditor by remember {
        mutableStateOf(false)
    }

    var eventTitle by remember {
        mutableStateOf("")
    }

    var eventTime by remember {
        mutableStateOf("10:00")
    }

    var eventLocation by remember {
        mutableStateOf("")
    }

    var eventDescription by remember {
        mutableStateOf("")
    }

    var reminderMinutes by remember {
        mutableIntStateOf(30)
    }

    // ZORO_CONFLICT_DIALOG_V1
    var conflictMessage by remember {
        mutableStateOf("")
    }

    var errorText by remember {
        mutableStateOf("")
    }

    // ==========================================
    // AYLIK TAKVIM HESAPLAMALARI
    // ==========================================

    val monthCalendar = remember(monthOffset) {
        Calendar.getInstance(timeZone).apply {
            this.timeInMillis = today.timeInMillis

            set(Calendar.DAY_OF_MONTH, 1)
            add(Calendar.MONTH, monthOffset)

            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }

    val monthStart = monthCalendar.timeInMillis

    val monthEnd = Calendar.getInstance(
        timeZone
    ).apply {
        this.timeInMillis = monthStart
        add(Calendar.MONTH, 1)
    }.timeInMillis

    val numberOfDays =
        monthCalendar.getActualMaximum(
            Calendar.DAY_OF_MONTH
        )

    val firstWeekday =
        (monthCalendar.get(Calendar.DAY_OF_WEEK) + 5) % 7

    val monthName = SimpleDateFormat(
        "MMMM yyyy",
        turkish
    ).apply {
        this.timeZone = timeZone
    }.format(Date(monthStart))

    val hasPermission =
        manager.canReadAndroidCalendar()

    // ==========================================
    // ETKINLIKLERI YUKLE
    // ==========================================

    LaunchedEffect(
        monthStart,
        monthEnd,
        refreshKey,
        hasPermission
    ) {
        events = withContext(Dispatchers.IO) {
            manager.getAllEvents(
                monthStart,
                monthEnd
            )
        }
    }

    // ==========================================
    // SECILI GUN
    // ==========================================

    val selectedStart =
        Calendar.getInstance(timeZone).apply {
            this.timeInMillis = monthStart

            set(Calendar.DAY_OF_MONTH, selectedDay)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    val selectedEnd =
        Calendar.getInstance(timeZone).apply {
            this.timeInMillis = selectedStart
            add(Calendar.DAY_OF_MONTH, 1)
        }.timeInMillis

    val dailyEvents = events.filter {
        it.startMillis < selectedEnd &&
                it.endMillis > selectedStart
    }.sortedBy {
        it.startMillis
    }

    // ==========================================
    // YENI ETKINLIK
    // ==========================================

    fun openNewEvent() {
        selectedEvent = null
        eventTitle = ""
        eventTime = "10:00"
        eventLocation = ""
        eventDescription = ""
        reminderMinutes = 30
        errorText = ""
        showEditor = true
    }

    // ==========================================
    // MEVCUT ETKINLIK
    // ==========================================

    fun openExistingEvent(
        event: ZoroCalendarEvent
    ) {
        selectedEvent = event
        eventTitle = event.title

        eventTime = SimpleDateFormat(
            "HH:mm",
            turkish
        ).apply {
            this.timeZone = timeZone
        }.format(Date(event.startMillis))

        eventLocation = event.location
        eventDescription = event.description

        reminderMinutes =
            if (
                event.source ==
                CalendarManager.SOURCE_ZORO
            ) {
                ZoroCalendarAlarmScheduler
                    .getReminderMinutes(
                        context,
                        event.id
                    )
            } else {
                30
            }

        errorText = ""
        showEditor = true
    }

    // ==========================================
    // ETKINLIK EKLEME VE DUZENLEME
    // ==========================================

    if (showEditor) {

        val isZoroEvent =
            selectedEvent == null ||
                    selectedEvent?.source ==
                    CalendarManager.SOURCE_ZORO

        val displayedDate =
            selectedEvent?.startMillis
                ?: selectedStart

        AlertDialog(
            onDismissRequest = {
                showEditor = false
            },

            title = {
                Text(
                    text =
                        if (selectedEvent == null) {
                            "Yeni Etkinlik"
                        } else if (isZoroEvent) {
                            "Etkinliği Düzenle"
                        } else {
                            "Etkinlik Bilgileri"
                        },
                    color = red
                )
            },

            text = {
                Column(
                    modifier = Modifier
                        .verticalScroll(
                            rememberScrollState()
                        ),

                    verticalArrangement =
                        Arrangement.spacedBy(10.dp)
                ) {

                    Text(
                        SimpleDateFormat(
                            "dd MMMM yyyy",
                            turkish
                        ).apply {
                            this.timeZone = timeZone
                        }.format(
                            Date(displayedDate)
                        ),
                        fontWeight = FontWeight.Bold,
                        color = blue
                    )

                    OutlinedTextField(
                        value = eventTitle,
                        onValueChange = {
                            eventTitle = it
                        },
                        label = {
                            Text("Etkinlik adı")
                        },
                        singleLine = true,
                        enabled = isZoroEvent
                    )

                    OutlinedTextField(
                        value = eventTime,
                        onValueChange = {
                            eventTime = it
                        },
                        label = {
                            Text("Saat (HH:mm)")
                        },
                        singleLine = true,
                        enabled = isZoroEvent
                    )

                    OutlinedTextField(
                        value = eventLocation,
                        onValueChange = {
                            eventLocation = it
                        },
                        label = {
                            Text("Konum")
                        },
                        singleLine = true,
                        enabled = isZoroEvent
                    )

                    OutlinedTextField(
                        value = eventDescription,
                        onValueChange = {
                            eventDescription = it
                        },
                        label = {
                            Text("Açıklama")
                        },
                        maxLines = 3,
                        enabled = isZoroEvent
                    )

                    // ==============================
                    // HATIRLATMA SURESI SECIMI
                    // ==============================

                    if (isZoroEvent) {

                        Text(
                            text = "Hatırlatma Zamanı",
                            fontWeight = FontWeight.Bold,
                            color = blue,
                            fontSize = 16.sp
                        )

                        Text(
                            text =
                                "Randevudan ne kadar önce " +
                                        "haber vereyim?",
                            fontSize = 12.sp,
                            color = Color.DarkGray
                        )

                        val choices = listOf(
                            5, 10, 15,
                            30, 60, 120
                        )

                        choices.chunked(3).forEach {
                                choiceRow ->

                            Row(
                                modifier =
                                    Modifier.fillMaxWidth(),

                                horizontalArrangement =
                                    Arrangement.spacedBy(5.dp)
                            ) {

                                choiceRow.forEach {
                                        minutes ->

                                    val selected =
                                        reminderMinutes ==
                                                minutes

                                    Button(
                                        onClick = {
                                            reminderMinutes =
                                                minutes
                                        },
                                        modifier =
                                            Modifier.weight(1f),

                                        contentPadding =
                                            androidx.compose.foundation
                                                .layout.PaddingValues(
                                                    horizontal = 2.dp,
                                                    vertical = 4.dp
                                                ),

                                        colors =
                                            ButtonDefaults.buttonColors(
                                                containerColor =
                                                    if (selected) {
                                                        red
                                                    } else {
                                                        Color(
                                                            0xFFE5F5FF
                                                        )
                                                    },

                                                contentColor =
                                                    if (selected) {
                                                        Color.White
                                                    } else {
                                                        blue
                                                    }
                                            )
                                    ) {

                                        Text(
                                            text =
                                                when (minutes) {
                                                    60 -> "1 saat"
                                                    120 -> "2 saat"
                                                    else ->
                                                        "$minutes dk"
                                                },
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }
                        }

                        Text(
                            text =
                                "Seçilen: " +
                                        ZoroCalendarAlarmScheduler
                                            .formatReminderMinutes(
                                                reminderMinutes
                                            ),
                            color = sky,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text =
                                "Uyarı zamanı geçmişse " +
                                        "otomatik alarm kurulamaz.",
                            color = Color.Gray,
                            fontSize = 11.sp
                        )

                    } else {

                        Text(
                            text =
                                "Samsung ve Google Takvim " +
                                        "etkinlikleri bu ekranda " +
                                        "yalnızca okunabilir.",
                            color = blue,
                            fontSize = 12.sp
                        )
                    }

                    if (errorText.isNotBlank()) {

                        Text(
                            text = errorText,
                            color = red,
                            fontSize = 12.sp
                        )
                    }
                }
            },

            // ======================================
            // KAYDET
            // ======================================

            confirmButton = {

                if (isZoroEvent) {

                    TextButton(
                        onClick = {

                            val parts =
                                eventTime.split(":")

                            val hour =
                                parts.getOrNull(0)
                                    ?.toIntOrNull()

                            val minute =
                                parts.getOrNull(1)
                                    ?.toIntOrNull()

                            if (
                                eventTitle.isBlank() ||
                                parts.size != 2 ||
                                hour == null ||
                                minute == null ||
                                hour !in 0..23 ||
                                minute !in 0..59
                            ) {

                                errorText =
                                    "Etkinlik adı ve " +
                                            "geçerli bir saat girin."

                            } else {

                                val baseTime =
                                    selectedEvent
                                        ?.startMillis
                                        ?: selectedStart

                                val start =
                                    Calendar.getInstance(
                                        timeZone
                                    ).apply {

                                        this.timeInMillis =
                                            baseTime

                                        set(
                                            Calendar.HOUR_OF_DAY,
                                            hour
                                        )

                                        set(
                                            Calendar.MINUTE,
                                            minute
                                        )

                                        set(
                                            Calendar.SECOND,
                                            0
                                        )

                                        set(
                                            Calendar.MILLISECOND,
                                            0
                                        )
                                    }.timeInMillis

                                val existing =
                                    selectedEvent

                                val checkEnd = start + (
                                    existing?.let {
                                        it.endMillis - it.startMillis
                                    } ?: 3600000L
                                )

                                val conflicts =
                                    manager.findConflictingEvents(
                                        startMillis = start,
                                        endMillis = checkEnd,
                                        excludeEventId = existing?.id
                                    )

                                if (conflicts.isNotEmpty()) {
                                    val first = conflicts.first()

                                    val begin = manager.formatEventDate(
                                        first.startMillis
                                    )

                                    val endTime = SimpleDateFormat(
                                        "HH:mm",
                                        turkish
                                    ).apply {
                                        setTimeZone(TimeZone.getTimeZone("Europe/Istanbul"))
                                    }.format(Date(first.endMillis))

                                    conflictMessage =
                                        "Bu saatlerde başka bir randevunuz var.\n\n" +
                                        "${first.title}\n" +
                                        "$begin - $endTime\n\n" +
                                        "Yeni randevu kaydedilmedi. " +
                                        "Lütfen farklı bir saat seçin."
                                }

                                val savedEvent =
                                    if (conflicts.isNotEmpty()) {
                                        null
                                    } else if (existing == null) {

                                        manager.addLocalEvent(
                                            title =
                                                eventTitle,
                                            startMillis =
                                                start,
                                            endMillis =
                                                start + 3600000L,
                                            description =
                                                eventDescription,
                                            location =
                                                eventLocation
                                        )

                                    } else {

                                        val duration =
                                            existing.endMillis -
                                                    existing.startMillis

                                        val updated =
                                            existing.copy(
                                                title =
                                                    eventTitle,
                                                startMillis =
                                                    start,
                                                endMillis =
                                                    start + duration,
                                                location =
                                                    eventLocation,
                                                description =
                                                    eventDescription
                                            )

                                        if (
                                            manager
                                                .updateLocalEvent(
                                                    updated
                                                )
                                        ) {
                                            updated
                                        } else {
                                            null
                                        }
                                    }

                                if (savedEvent == null) {

                                    errorText =
                                        if (conflicts.isNotEmpty()) {
                                            "Randevu çakışması var. Farklı saat seçin."
                                        } else {
                                            "Etkinlik kaydedilemedi."
                                        }

                                } else {

                                    // ======================
                                    // SECILEN HATIRLATMA
                                    // SURESINI KAYDET
                                    // ======================

                                    val currentMinutes =
                                        ZoroCalendarAlarmScheduler
                                            .getReminderMinutes(
                                                context,
                                                savedEvent.id
                                            )

                                    val reminderSaved =
                                        if (
                                            currentMinutes ==
                                            reminderMinutes
                                        ) {

                                            true

                                        } else {

                                            ZoroCalendarAlarmScheduler
                                                .setReminderMinutes(
                                                    context,
                                                    savedEvent,
                                                    reminderMinutes
                                                )
                                        }

                                    refreshKey++

                                    if (reminderSaved) {

                                        showEditor = false

                                        Toast.makeText(
                                            context,
                                            "Etkinlik kaydedildi.",
                                            Toast.LENGTH_SHORT
                                        ).show()

                                    } else {

                                        errorText =
                                            "Etkinlik kaydedildi " +
                                                    "ancak seçilen " +
                                                    "hatırlatma süresi " +
                                                    "uygulanamadı. " +
                                                    "Randevu saatini " +
                                                    "ve uyarı süresini " +
                                                    "kontrol edin."
                                    }
                                }
                            }
                        }
                    ) {

                        Text("Kaydet")
                    }
                }
            },

            // ======================================
            // SIL / KAPAT
            // ======================================

            dismissButton = {

                Row {

                    if (
                        selectedEvent?.source ==
                        CalendarManager.SOURCE_ZORO
                    ) {

                        TextButton(
                            onClick = {

                                val event =
                                    selectedEvent

                                if (
                                    event != null &&
                                    manager.deleteLocalEvent(
                                        event.id
                                    )
                                ) {

                                    ZoroCalendarAlarmScheduler
                                        .removeReminderSettings(
                                            context,
                                            event.id
                                        )

                                    showEditor = false
                                    refreshKey++

                                } else {

                                    errorText =
                                        "Etkinlik silinemedi."
                                }
                            }
                        ) {

                            Text(
                                "Sil",
                                color = red
                            )
                        }
                    }

                    TextButton(
                        onClick = {
                            showEditor = false
                        }
                    ) {

                        Text("Kapat")
                    }
                }
            }
        )
    }

    // ==========================================
    // CAKISMA UYARI PENCERESI
    // ==========================================

    if (conflictMessage.isNotBlank()) {
        AlertDialog(
            onDismissRequest = {
                conflictMessage = ""
            },
            title = {
                Text(
                    "Randevu Çakışması",
                    color = red,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(conflictMessage)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        conflictMessage = ""
                    }
                ) {
                    Text("Tamam")
                }
            }
        )
    }

    // ==========================================
    // TAKVIM ANA EKRANI
    // ==========================================

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .verticalScroll(
                rememberScrollState()
            )
            .padding(16.dp),

        verticalArrangement =
            Arrangement.spacedBy(14.dp)
    ) {

        Text(
            text = "TAKVİMİM",
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
            color = red
        )

        Button(
            onClick = onBack,
            colors = ButtonDefaults.buttonColors(
                containerColor = blue
            )
        ) {

            Text("← Ana Ekrana Dön")
        }

        // ======================================
        // TAKVIM IZNI
        // ======================================

        if (!hasPermission) {

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFE5F5FF)
                )
            ) {

                Column(
                    modifier = Modifier.padding(14.dp),

                    verticalArrangement =
                        Arrangement.spacedBy(8.dp)
                ) {

                    Text(
                        "Telefon takvimlerine erişim",
                        fontWeight = FontWeight.Bold,
                        color = blue
                    )

                    Text(
                        "Samsung ve Google Takvim " +
                                "etkinliklerini göstermek " +
                                "için izin gerekiyor."
                    )

                    Button(
                        onClick =
                            requestCalendarPermission
                    ) {

                        Text("Takvim İzni Ver")
                    }
                }
            }
        }

        // ======================================
        // AY SECIMI
        // ======================================

        Row(
            modifier = Modifier.fillMaxWidth(),

            horizontalArrangement =
                Arrangement.SpaceBetween
        ) {

            TextButton(
                onClick = {
                    monthOffset--
                    selectedDay = 1
                }
            ) {

                Text("◀ Önceki")
            }

            Text(
                text =
                    monthName.replaceFirstChar {
                        it.titlecase(turkish)
                    },

                fontWeight = FontWeight.Bold,
                color = blue,
                fontSize = 17.sp,
                modifier = Modifier.padding(
                    top = 12.dp
                )
            )

            TextButton(
                onClick = {
                    monthOffset++
                    selectedDay = 1
                }
            ) {

                Text("Sonraki ▶")
            }
        }

        // ======================================
        // HAFTA GUNLERI
        // ======================================

        val weekdays = listOf(
            "Pt", "Sa", "Ça", "Pe",
            "Cu", "Ct", "Pa"
        )

        Row(
            modifier = Modifier.fillMaxWidth()
        ) {

            weekdays.forEach { day ->

                Text(
                    text = day,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    color = blue,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // ======================================
        // AYLIK TAKVIM
        // ======================================

        for (week in 0 until 6) {

            Row(
                modifier = Modifier.fillMaxWidth()
            ) {

                for (weekday in 0 until 7) {

                    val number =
                        week * 7 +
                                weekday -
                                firstWeekday + 1

                    if (
                        number in 1..numberOfDays
                    ) {

                        val currentDate =
                            Calendar.getInstance(
                                timeZone
                            ).apply {

                                this.timeInMillis =
                                    monthStart

                                set(
                                    Calendar.DAY_OF_MONTH,
                                    number
                                )
                            }

                        val currentDayStart =
                            currentDate.timeInMillis

                        currentDate.add(
                            Calendar.DAY_OF_MONTH,
                            1
                        )

                        val currentDayEnd =
                            currentDate.timeInMillis

                        val hasEvents =
                            events.any {

                                it.startMillis <
                                        currentDayEnd &&
                                        it.endMillis >
                                        currentDayStart
                            }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(2.dp)
                                .background(
                                    if (
                                        selectedDay == number
                                    ) {
                                        Color(0xFFD6ECFA)
                                    } else {
                                        Color.White
                                    },
                                    RoundedCornerShape(9.dp)
                                )
                                .clickable {
                                    selectedDay = number
                                }
                                .padding(
                                    vertical = 9.dp
                                ),

                            horizontalAlignment =
                                Alignment.CenterHorizontally
                        ) {

                            Text(
                                text = "$number",
                                fontWeight =
                                    if (
                                        selectedDay == number
                                    ) {
                                        FontWeight.Bold
                                    } else {
                                        FontWeight.Normal
                                    },
                                color = blue
                            )

                            Text(
                                text =
                                    if (hasEvents) {
                                        "●"
                                    } else {
                                        " "
                                    },
                                color = sky,
                                fontSize = 11.sp
                            )
                        }

                    } else {

                        Spacer(
                            modifier = Modifier
                                .weight(1f)
                                .height(53.dp)
                        )
                    }
                }
            }
        }

        // ======================================
        // ETKINLIK EKLEME BUTONU
        // ======================================

        Button(
            onClick = {
                openNewEvent()
            },

            modifier = Modifier.fillMaxWidth(),

            colors = ButtonDefaults.buttonColors(
                containerColor = red
            )
        ) {

            Text("+ Yeni Etkinlik Ekle")
        }

        // ======================================
        // GUNLUK ETKINLIKLER
        // ======================================

        Text(
            text = "Seçili Günün Etkinlikleri",
            fontWeight = FontWeight.Bold,
            fontSize = 19.sp,
            color = blue
        )

        if (dailyEvents.isEmpty()) {

            Text(
                text =
                    "Bu güne ait kayıtlı " +
                            "etkinlik bulunmuyor.",
                color = Color.Gray
            )
        }

        dailyEvents.forEach { event ->

            Card(
                onClick = {
                    openExistingEvent(event)
                },

                modifier = Modifier.fillMaxWidth(),

                colors = CardDefaults.cardColors(
                    containerColor = Color.White
                ),

                shape = RoundedCornerShape(14.dp)
            ) {

                Column(
                    modifier = Modifier.padding(14.dp),

                    verticalArrangement =
                        Arrangement.spacedBy(5.dp)
                ) {

                    Text(
                        text = event.title,
                        fontWeight = FontWeight.Bold,
                        color = blue,
                        fontSize = 16.sp
                    )

                    Text(
                        text =
                            if (event.allDay) {
                                "Tüm gün"
                            } else {
                                SimpleDateFormat(
                                    "HH:mm",
                                    turkish
                                ).apply {
                                    this.timeZone =
                                        timeZone
                                }.format(
                                    Date(
                                        event.startMillis
                                    )
                                )
                            }
                    )

                    Text(
                        text =
                            if (
                                event.source ==
                                CalendarManager.SOURCE_ZORO
                            ) {
                                "Zoro Özel Takvimi"
                            } else {
                                event.source.removePrefix(
                                    "ANDROID:"
                                )
                            },

                        color = sky,
                        fontSize = 12.sp
                    )

                    // ==========================
                    // RANDEVUNUN HATIRLATMA
                    // SURESINI GOSTER
                    // ==========================

                    if (
                        event.source ==
                        CalendarManager.SOURCE_ZORO &&
                        !event.allDay
                    ) {

                        val minutes =
                            ZoroCalendarAlarmScheduler
                                .getReminderMinutes(
                                    context,
                                    event.id
                                )

                        Text(
                            text =
                                "🔔 Hatırlatma: " +
                                        ZoroCalendarAlarmScheduler
                                            .formatReminderMinutes(
                                                minutes
                                            ),
                            color = red,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (
                        event.location.isNotBlank()
                    ) {

                        Text(
                            text =
                                "Konum: ${event.location}",
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        // ======================================
        // TAKVIMI YENILE
        // ======================================

        Button(
            onClick = {

                refreshKey++

                Toast.makeText(
                    context,
                    "Takvim yenileniyor.",
                    Toast.LENGTH_SHORT
                ).show()
            },

            modifier = Modifier.fillMaxWidth(),

            colors = ButtonDefaults.buttonColors(
                containerColor = sky
            )
        ) {

            Text("↻ Takvimi Yenile")
        }
    }
}

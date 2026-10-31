# Zoro Android: takvim, hatırlatma, hafıza, tema haritası

Dizin: `/home/emreds/Desktop/musteriler/zoro/zoro2/android/app/src/main/java/com/zaferyilmaz/zoro/`. Aşağıdaki dosya adları bu dizine görecelidir.

## 1. Takvim

**Depolama uygulama içidir, sistem takvimine yazmaz.** İki kaynak var:
- **ZORO (yazılabilir):** SharedPreferences `zoro_calendar`, key `local_events`. Değer bir JSON array (`Calendarmanager.kt:46-47`, `:79-145`, `:151-207`).
- **ANDROID (salt okunur):** `CalendarContract.Instances` sorgusu, yalnızca `getAndroidEvents` içinde (`:565-723`).
- `READ_CALENDAR` ve `WRITE_CALENDAR` manifestte tanımlı. Kodda `CalendarContract.Events` insert/update/delete YOK.

**Data class** `ZoroCalendarEvent` (`Calendarmanager.kt:25-34`):
- Alanlar: `id: String`, `title: String`, `description: String`, `startMillis: Long`, `endMillis: Long`, `location: String`, `source: String`, `allDay: Boolean = false`.
- JSON anahtarları: `id`, `title`, `description`, `startMillis`, `endMillis`, `location`, `allDay`. `source` diske yazılmaz, okunurken her zaman `"ZORO"` olur (`:124`).
- Android kaynaklı olaylar `id = "android_${eventId}_$begin"`, `source = "ANDROID:<takvimAdı>"` alır (`:691-698`).

**`class CalendarManager(context: Context)`** public fonksiyonları (`Calendarmanager.kt`):

| Satır | İmza |
|---|---|
| 63 | `fun canReadAndroidCalendar(): Boolean` |
| 69 | `fun canWriteAndroidCalendar(): Boolean` |
| 79 | `fun getLocalEvents(): List<ZoroCalendarEvent>` (başlangıca göre sıralı) |
| 565 | `fun getAndroidEvents(fromMillis: Long, toMillis: Long): List<ZoroCalendarEvent>` |
| 729 | `fun getAllEvents(fromMillis: Long, toMillis: Long): List<ZoroCalendarEvent>` (yerel + Android birleşik, kesişim filtreli, sıralı) |
| 213 | `fun findConflictingEvents(startMillis: Long, endMillis: Long, excludeEventId: String? = null): List<ZoroCalendarEvent>` |
| 314-322 | `@Synchronized fun addLocalEvent(title: String, startMillis: Long, endMillis: Long, description: String = "", location: String = "", allDay: Boolean = false): ZoroCalendarEvent?` |
| 409-412 | `@Synchronized fun updateLocalEvent(event: ZoroCalendarEvent): Boolean` |
| 507-510 | `@Synchronized fun deleteLocalEvent(eventId: String): Boolean` |
| 762 | `fun restoreCalendarReminders()` |
| 783 | `fun formatEventDate(timestamp: Long): String` (`dd.MM.yyyy HH:mm`, Europe/Istanbul) |

- **Tarih aralığı sorgusu:** `getAllEvents(from, to)`. Yerel olaylar `start < to && end > from` ile süzülür (`:739-743`).
- **Move/update:** ayrı bir "move" fonksiyonu YOK. Güncellemek için `event.copy(startMillis=…, endMillis=…)` verilip `updateLocalEvent` çağrılır. Bu fonksiyon yalnızca `source == "ZORO"` olaylarda çalışır (`:415`).
- **Metne göre bulma:** `CalendarManager` içinde YOK. Tek mantık `ZoroCalendarEdit.eventMatches` (özel, `ZoroCalendarEdit.kt:164-222`): komuttaki en az 4 harfli kelimeler ile başlıktaki kelimelerin ilk 5 karakteri karşılaştırılır. Dışarıdan çağrılamaz.
- **Çakışma kontrolü:** `addLocalEvent` ve `updateLocalEvent` zamanlı (allDay olmayan) olaylarda çakışma bulursa `null`/`false` döner ve Toast gösterir (`:336-351`, `:439-455`). Çakışma nedenini çağırana vermez.
- **Alarm bağlantısı:** `addLocalEvent` otomatik `ZoroCalendarAlarmScheduler.schedule` çağırır (`:382-400`). `updateLocalEvent` `reschedule`/`cancel`, `deleteLocalEvent` `cancel` çağırır.
- **Dikkat:** `deleteLocalEvent` `removeReminderSettings` çağırmaz. Takvim ekranı çağırır (`ZoroCalendarScreen.kt:780-790`), sesli silme çağırmaz.

## 2. Sesli takvim (`ZoroCalendarVoice.kt` ve `ZoroCalendarEdit.kt`)

**Ekleme** (`ZoroCalendarVoice.isCalendarAddCommand`, `:104-166`):
- Metinde `ekle`, `oluştur` veya `kaydet` geçmeli (`:112-120`). `görev ekle`, `hatırlat`, `alarm kur` içeriyorsa reddedilir (`:123-129`).
- Kabul koşulları (`:132-165`): `takvime`/`takvimime`/`randevu ekle`/`etkinlik ekle` geçmesi, VEYA tarih + saat + olay kelimesi (`toplantı`, `randevu`, `etkinlik`, `görüşme`, `buluşma`) üçünün birden bulunması.
- Tarih (`resolveDate`, `:212-304`): `bugün`, `yarın`, `öbür gün`/`öteki gün`, ya da `\d{1,2} <ay adı> [yıl]`. Yıl yoksa içinde bulunulan yıl kullanılır.
- Saat (`findTime`, `:191-206`): yalnızca `HH.MM` veya `HH:MM`. `saat 14`, `akşam yedide` ve `14'te` gibi biçimler parse edilmez.
- Süre 1 saat sabit (`:472-473`). Geçmiş zaman reddedilir (`:454-460`).
- Başlık, tarih/saat/komut kelimeleri silinerek çıkarılır (`extractEventTitle`, `:310-372`).
- Giriş noktası: `fun addCalendarEvent(context: Context, command: String): String` (`:378`). MainActivity'de `:1393-1397` çağırır.

**Sorgu** (`isCalendarQuery`, `:55-98`):
- Takvim kelimesi (`takvim`, `randevu`, `etkinlik`, `programım`) + sorgu kelimesi (`ne var`, `var mı`, `hangi`, `neler`, `söyle`, `oku`, `listele`, `göster`) gerekir. Değiştirici kelime (`ekle`, `oluştur`, `kaydet`, `sil`, `iptal et`, `değiştir`, `ertele`, `taşı`) geçmemelidir.
- Cevap: `fun getCalendarAnswer(context: Context, command: String): String` (`:520`). Aralık `yaklaşan`/`önümüzdeki` için 7 gün, aksi halde tek gün. En fazla 10 olay okunur.
- Android takvim izni yoksa metin döner (`:631-637`). İzin yokken Zoro yerel olaylarını da söylemez, bu da bir zayıflık.

**Silme/erteleme** (`ZoroCalendarEdit.kt`):
- `isEditCommand` (`:43-70`): `iptal et` / `sil` / `ertele` geçmeli; `görev`/`hatırlatma`/`alarmı` geçmemeli; ayrıca `randevu`/`toplantı`/`görüşme`/`etkinlik`/`takvim` geçmeli.
- `handleCommand(context: Context, command: String): String` (`:273`).
- Adaylar: yalnızca ZORO kaynaklı, gelecekteki, allDay olmayan olaylar (`:295-299`). Sonra gün filtresi, sonra `eventMatches`.
- Tek eşleşme yoksa hiçbir şey değiştirilmez: 0 eşleşmede hata, birden fazlasında örnek listesi okunur (`:316-336`).
- **Silme onay istemez.** Tek aday bulunduğu anda `deleteLocalEvent(selected.id)` çağrılır (`:359`). Sesli akışta onay adımı YOK. Takvim ekranında silme tarafında `AlertDialog` var ama bu düzenleme penceresidir (`ZoroCalendarScreen.kt:298`, `:827`), silme onayı olup olmadığını doğrulamadım.
- Erteleme `updateLocalEvent` (`:431`) ile yapılır. Yeni saat için `saat HH.MM` kalıbı şart (`requestedTime`, `:225-255`).

**Bilinen zayıf noktalar:**
1. **Alt dize eşleşmesi, silme:** `isEditCommand` ve `isDelete` `text.contains("sil")` kullanır (`ZoroCalendarEdit.kt:45-50`, `:286-287`). "Silivri" içeren bir cümle ("Silivri randevusunu ertele") hem `isEditCommand`'ı geçer hem `isDelete = true` olur. `isDelete` `isPostpone`'dan önce kontrol edildiği için ERTELEME YERİNE SİLME yapılır. Tek aday varsa onay istenmeden silinir.
2. **Alt dize eşleşmesi, ekleme:** `"ekle"` kontrolü `contains` ile yapılır (`ZoroCalendarVoice.kt:112-120`). "bekle", "sekle" gibi kelimeler addWords'ü geçer; ek koşullar (takvime veya tarih+saat+olay kelimesi) bunu çoğunlukla engeller ama garanti değil.
3. **Başlık temizliği:** `extractEventTitle` `\b` sınırı kullanıyor, ancak `\b` Türkçe karakterlerde güvenilir değildir (`ş`, `ı`, `ğ` Java regex'inde varsayılan olarak "word char" sayılmaz). `ekle|oluştur|kaydet|lütfen|bir` temizliği "oluştur"/"lütfen" gibi kelimelerde kenar sınırı hatası yapabilir. Bunu çalıştırarak doğrulamadım.
4. **Saat biçimi:** noktalı/iki noktalı `HH.MM` dışında hiçbir şey parse edilmez. "öğleden sonra üçte" tarzı Türkçe konuşma desteklenmez.
5. **Gün adları yok:** "cuma", "haftaya", "gelecek hafta" tanınmaz.
6. **`yarın`/`bugün` alt dize eşleşir:** "yarınki", "bugünkü" da eşleşir (kasıtlı olabilir). Aynı metinde hem tarih hem "yarın" varsa "yarın" önceliklidir (`resolveDate` sırası, `:227-248`).
7. **Çakışmada sebep verilmez:** `addCalendarEvent` çakışmada `null` alır ve "Randevu takvime kaydedilemedi." der (`:495-498`). Toast ayrıca çıkar ama sesli cevaba yansımaz.
8. **Android olaylar sesli silinmez/ertelenemez** (`getLocalEvents()` filtresi).

## 3. ReminderStore ve alarmlar

**`object ReminderStore`** (`ReminderStore.kt`):
- Prefs `zoro_reminder_store`, her hatırlatma ayrı key `reminder_<alarmId>`, değer JSON (`:8-9`).
- `data class ReminderData(alarmId: String, taskText: String, dueAt: String, repeatInterval: Long)` (`:11-16`). JSON anahtarları: `alarm_id`, `task_text`, `due_at`, `repeat_interval`.
- `dueAt` ISO-8601 string, offset ile (örn. `2026-11-01T09:00:00+03:00`). Format `MainActivity.parseDueAtMillis` içinde: `yyyy-MM-dd'T'HH:mm:ss[.SSS]XXX` (`MainActivity.kt:2034-2065`).
- Public fonksiyonlar:
  - `saveReminder(context: Context, alarmId: String, taskText: String, dueAt: String, repeatInterval: Long = 0L)` (`:18`)
  - `updateReminderText(context: Context, alarmId: String, newText: String): Boolean` (`:45`)
  - `removeReminder(context: Context, alarmId: String)` (`:71`)
  - `findByDueAt(context: Context, dueAt: String): ReminderData?` (`:81`)
  - `getAllReminders(context: Context): List<ReminderData>` (`:92`)
  - `clearAll(context: Context)` (`:140`)
- `ReminderStore` yalnızca veri saklar, alarm KURMAZ.

**Alarm kurma/iptal (`MainActivity.kt`, hepsi `private`):**
- `scheduleReminder(taskText: String, dueAt: String, alarmId: String, repeatInterval: Long)` (`:1827`). `ReminderReceiver`'a broadcast PendingIntent kurar, `requestCode = alarmId.hashCode()`, extras `task_text`, `alarm_id`, `repeat_interval`. Geçmiş zamana kurmaz (`:1840-1850`). Başarılı alarmdan sonra `ReminderStore.saveReminder` çağırır (`:1897`).
- `cancelReminderByAlarmId(alarmId: String)` (`:1965`), `cancelReminderByDueAt(dueAt: String)` (`:1924`), `cancelReminderByTaskText(taskText: String)` (`:1943`, birebir eşleşme), `cancelAllStoredReminders()` (`:2018`).
- Bunlar `private` ve `MainActivity` içinde olduğu için dışarıdan çağrılamaz. Tetikleyici, Python sunucudan gelen JSON'daki `tasks`/`action` alanıdır (`add_task`, `clear_tasks`, `cancel_alarm_ids` vb.; `MainActivity.kt:837`, `:1545-1595`, `:1680`).

**AlarmManager:**
- `MainActivity.setSafeAlarm` (`:1750-1810`): API < 31 veya `canScheduleExactAlarms()` true ise `setExactAndAllowWhileIdle(RTC_WAKEUP)`, değilse `setAndAllowWhileIdle` (inexact). `SecurityException`'da inexact'e düşer.
- Aynı desen `ReminderReceiver.kt:539-580`, `BootReceiver.kt:285-335`, `ZoroCalendarAlarmScheduler.kt:195-215`, `ZoroMorningBriefingScheduler.kt:90-110`.
- Manifestte `SCHEDULE_EXACT_ALARM` ve `POST_NOTIFICATIONS` var (`AndroidManifest.xml:7`, `:9`). `setAlarmClock` kullanılmıyor.

**Takvim alarmları:** `object ZoroCalendarAlarmScheduler` (`ZoroCalendarAlarmScheduler.kt:16`):
- `schedule(context: Context, event: ZoroCalendarEvent): Boolean` (`:136`), `cancel(context: Context, eventId: String): Boolean` (`:283`), `reschedule(context: Context, event: ZoroCalendarEvent): Boolean` (`:327`), `restoreFutureAlarms(context: Context)` (`:440`).
- Olay başına hatırlatma dakikası: `getReminderMinutes` (`:56`), `setReminderMinutes` (`:346`), `removeReminderSettings` (`:422`).
- `schedule` yalnızca `ZORO` kaynaklı, allDay olmayan, id'si dolu olaylarda çalışır.

## 4. Hafıza

**Model** (`ZoroMemoryManeger.kt:171-175`): `data class ZoroMemoryItem(id: String, text: String, createdAt: Long)`.

**Depolama:** prefs `zoro_personal_memory`, key `saved_memories`, JSON array `[{id, text, createdAt}]` (`:187-188`).

**Limitler:** `MAX_MEMORIES = 500`, `MAX_TEXT_LENGTH = 2000` (`:190-191`). Limit aşılınca yeni kayıt sessizce `null` döner, eskisini silmez (`:305-307`).

**`class ZoroMemoryManager(context: Context)`:**
- `@Synchronized fun getAllMemories(): List<ZoroMemoryItem>` (`:204`, en yeni önce)
- `@Synchronized fun saveMemory(text: String): ZoroMemoryItem?` (`:259`, boşlukları sadeleştirir; aynı metin (büyük/küçük harf farksız) tekrarsa mevcut kaydı döner)
- `fun searchMemories(query: String): List<ZoroMemoryItem>` (`:325`, 3+ harfli kelimelerle `contains` skoru)
- `@Synchronized fun deleteMemory(memoryId: String): Boolean` (`:372`)
- `fun formatMemoryDate(createdAt: Long): String` (`:468`)

**Sesli** (`ZoroMemoryVoice.kt`, `object`):
- Kaydet tetikleyicileri (`:25-33`): `bunu unutma`, `şunu unutma`, `aklında tut`, `hafızana kaydet`, `hafızaya kaydet`, `bunu hatırla`, `şunu hatırla`. (`unutma` ifadesi aslında "unutma" anlamında, ama tetikleyici olarak kayıt yapar.)
- Listele (`:14-23`): `neleri hatırlıyorsun`, `ne hatırlıyorsun`, `hafızanda ne var`, `hafızanda neler var`, `hafızandakileri söyle`, `kaydettiklerini söyle`, `bana neyi kaydettin`, `sana ne söylemiştim`.
- Sorgu (`:101-113`): `ne zaman`, `ne zamana`, `hangi gün`, `hangi tarih`, `ne tarih`, `nerede`, `nereye`, `hatırlıyor musun`, `hatırlıyor muydun`, `sana söylemiş miydim`, `sana ne demiştim`. Konu boşsa sorgu sayılmaz.
- Sesli silme tetikleyicileri (`:35-40`) var ama devre dışı: `deleteMemory` sabit bir "henüz etkin değil" cevabı döner (`:294-303`).
- `fun handleCommand(context: Context, command: String): String` (`:309`), `fun isMemoryCommand(command: String): Boolean` (`:131`). MainActivity `:1300-1304`.

## 5. Sunucu aksiyon yürütücüsünün çağırabileceği imzalar

```kotlin
val cal = CalendarManager(context)
val mem = ZoroMemoryManager(context)

// Takvim ekle (null = geçersiz girdi, çakışma veya kayıt hatası)
cal.addLocalEvent(title: String, startMillis: Long, endMillis: Long,
                  description: String = "", location: String = "", allDay: Boolean = false): ZoroCalendarEvent?
// Taşı: önce cal.getLocalEvents().find { it.id == id }, sonra
cal.updateLocalEvent(event.copy(startMillis = s, endMillis = e)): Boolean
// Sil
cal.deleteLocalEvent(eventId: String): Boolean
ZoroCalendarAlarmScheduler.removeReminderSettings(context, eventId)   // silme sonrası çağrılması önerilir
// Önümüzdeki N gün (sunucuya bağlam)
cal.getAllEvents(System.currentTimeMillis(), System.currentTimeMillis() + N*86_400_000L): List<ZoroCalendarEvent>
// Çakışma önceden sormak için
cal.findConflictingEvents(startMillis, endMillis, excludeEventId = null): List<ZoroCalendarEvent>

// Hafıza
mem.saveMemory(text: String): ZoroMemoryItem?
mem.getAllMemories(): List<ZoroMemoryItem>
mem.searchMemories(query: String): List<ZoroMemoryItem>
mem.deleteMemory(memoryId: String): Boolean

// Hatırlatma/alarm: dışarıdan çağrılabilir public API YOK
ReminderStore.getAllReminders(context): List<ReminderData>      // listele
ReminderStore.saveReminder(...) / removeReminder(...)           // yalnızca depo, alarm kurmaz/iptal etmez
```

- **Hatırlatma ekleme için yeni bir dış API gerekir.** `MainActivity.scheduleReminder(taskText, dueAt, alarmId, repeatInterval)` ile `cancelReminderByAlarmId` private. Bunları ortak bir `object` içine (örn. `ReminderScheduler`) taşımak gerekir. Ya da sunucu mevcut `tasks` JSON yoluyla (`MainActivity.kt:837`, `:1545-1595`) `add_task` gönderir; bu yol zaten çalışıyor.
- **Sunucu için silme öncesi doğrulama:** sesli yolda yok, bu yüzden sunucu yürütücüsü ID ile silmeli, metinle değil.
- **Thread güvenliği:** `addLocalEvent`, `updateLocalEvent`, `deleteLocalEvent` `@Synchronized`. Ancak her çağrıda yeni bir `CalendarManager` örneği oluşturulduğu için kilit örnek bazlıdır, örnekler arası korumaz. Tek bir paylaşılan örnek kullanmak daha güvenli.
- **Zaman dilimi:** gösterimler `Europe/Istanbul` sabit. Epoch millis alanları zaman dilimsizdir.

## 6. Tema

**Compose tema** (`ui/theme/`):
- `Theme.kt:46-68`: `ZoroTheme(darkTheme = isSystemInDarkTheme(), dynamicColor = true)`. Android 12+ cihazlarda sistem renk şeması (dynamic color) kullanılır, aşağıdaki mor palet yalnızca eski cihazlarda devreye girer.
- `Color.kt`: şablon varsayılanları. `Purple80 #D0BCFF`, `PurpleGrey80 #CCC2DC`, `Pink80 #EFB8C8`, `Purple40 #6650A4`, `PurpleGrey40 #625B71`, `Pink40 #7D5260`.
- `Type.kt`: yalnızca `bodyLarge` tanımlı (FontFamily.Default, Normal, 16sp, satır 24sp, harf aralığı 0.5sp). Diğer stiller Material3 varsayılanı.

**Gerçekte kullanılan görünüm (takvim ekranı, tema dışı sabit renkler):**
- `ZoroCalendarScreen.kt:65-68`: `red #B71C1C`, `blue #135B8D`, `sky #27A7E5`, `background #F4F8FC`.
- Ek renkler: kart `#E5F5FF` (`:895`), seçili `#D6ECFA` (`:1063`).
- Tipografi sabit değerlerle verilmiş: başlık 28sp ExtraBold (`:872`), bölüm başlığı 19sp/17sp/16sp Bold, ikincil metin 11-13sp. Köşe/aralık: `Arrangement.spacedBy` 5/8/10/14dp.

**Tutarlılık için öneri:** yeni ekranlarda takvim ekranındaki `blue/sky/red/background` dörtlüsünü ve Bold başlık + 11-13sp açıklama düzenini kullanmak mevcut görünümle uyumlu olur. Tema dosyasındaki mor şablon renklerine dayanmak, takvim ekranıyla uyumsuz çıkar.

## Dosyalar
- `Calendarmanager.kt`, `ZoroCalendarVoice.kt`, `ZoroCalendarEdit.kt`, `ZoroCalendarScreen.kt`, `ReminderStore.kt`, `ZoroMemoryManeger.kt`, `ZoroMemoryVoice.kt`, `ui/theme/{Color,Theme,Type}.kt`
- Ek olarak okunanlar: `ZoroCalendarAlarmScheduler.kt`, `MainActivity.kt` (satır 1300, 1364-1430, 1545-1595, 1680, 1750-2065), `AndroidManifest.xml`

**Doğrulanmadı / YOK:** `ZoroCalendarEdit.handleCommand` içindeki erteleme gövdesi (`:365-440`) tam okunmadı; yalnızca `:431`'deki `updateLocalEvent` çağrısı görüldü. Takvim ekranındaki silme için onay diyaloğu olup olmadığına bakılmadı. `\b` ile Türkçe karakter davranışı çalıştırılarak test edilmedi.
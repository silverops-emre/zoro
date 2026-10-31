# MainActivity.kt map

File: `/home/emreds/Desktop/musteriler/zoro/zoro2/android/app/src/main/java/com/zaferyilmaz/zoro/MainActivity.kt` (3184 lines, package `com.zaferyilmaz.zoro`).

`ZoroVoiceService.kt` is a separate file at `.../src/main/java/ZoroVoiceService.kt`. Its `package` line is `com.zaferyilmaz.zoro`, but the file sits in the java root, not the package folder. I read it only for the constants and `speaking` flag. I did not map its internals.

## 1. SERVER CONTRACT

- **Base URL:** There is no single base constant. Two hardcoded `private val`s in `MainActivity`:
  - `backendUrl = "http://192.168.1.108:5050/ask"` (115-116)
  - `tasksUrl = "http://192.168.1.108:5050/tasks"` (118-119)
- **HTTP client:** `private val httpClient = OkHttpClient()` (113). It uses the default timeouts and has no interceptors. `YOK` for any custom timeout.
- **Headers:** `YOK`. No `.header()` or `.addHeader()` anywhere. The only headers are the implicit `Content-Type: application/json; charset=utf-8` that `toRequestBody` adds on POSTs.
- **Cleartext:** `AndroidManifest.xml:28` has `usesCleartextTraffic="true"`. There is no network-security-config (`res/xml` holds only `backup_rules.xml` and `data_extraction_rules.xml`).
- **`httpClient.dispatcher.cancelAll()`** runs in `onDestroy` (2112).

There are exactly four HTTP calls. All use `enqueue`, so there is no sync call.

| # | Function (line) | Request builder | Method / URL | Request JSON |
|---|---|---|---|---|
| A | `loadTasks` (765) | 770-773 | GET `tasksUrl` (`/tasks`) | none |
| B | `editTaskFromScreen` (970) | 994-1001 | POST `$tasksUrl/{Uri.encode(task.id)}/edit` | `{"text": newText.trim()}`, plus `"due_at": newDueAt` only if `newDueAt != task.dueAt.take(16)` (987-992). `due_at` format is `yyyy-MM-ddTHH:mm`. |
| C | `performTaskAction` (1094) | 1145-1148 | POST `$tasksUrl/{encodedId}/{complete\|delete}` (`ZoroTaskOperation.apiPath`, 98-103) | `"{}"` (1140) |
| D | `askZoro` (1293) | 1459-1462 | POST `backendUrl` (`/ask`) | `{"message": <string>}` only (1451-1453) |

### A. `loadTasks` response (816-887)
Parsing is `JSONObject(body)`.
- **`success`** (boolean, default false): if false it throws `IOException("Görev listesi okunamadı.")`.
- **`tasks`** (array). Per item:
  - `number` (int, default index+1)
  - `id` (string)
  - `text` (string)
  - `due_at` (string)
  - `done` (boolean)
  - `repeat_interval` (long, default 0)
- **Result:** the items go into `taskItems` on the UI thread (889-894).
- **Errors:**
  - `onFailure` (778-798) sets `tasksError` to "Görevler yüklenemedi. Python sunucusunu ve Wi-Fi bağlantısını kontrol et."
  - `!isSuccessful` throws `IOException("Sunucu hatası: code")` (809-814).
  - Any exception sets `tasksError = "Görev verileri okunamadı."` (896-910).

### B. Edit response (1018-1074)
- **`success`** (boolean): if false or `!isSuccessful`, `tasksMessage = json.optString("reply", "Görev güncellenemedi.")` (1022-1034).
- **`text`** (default `newText`), **`due_at`** (default `newDueAt`), **`repeat_interval`** (default `task.repeatInterval`).
- **Action:** if the date or time changed (1044), it calls `cancelReminderByAlarmId(task.id)` then `scheduleReminder(text, due_at, alarmId = task.id, interval)` (1049-1056). It then sets `tasksMessage = "Görev güncellendi."` and calls `loadTasks()` (1072).
- **Errors:**
  - `onFailure` (1005-1013) sets "Sunucuya bağlanılamadı...".
  - A parse exception sets "İşlem sonucu doğrulanamadı..." (1076-1088).
- **Dependency:** the UI watches the exact string `"Görev güncellendi."` or `"Görev kaydedildi ancak alarm kontrol edilmeli."` (2284-2285) to close the edit dialog.

### C. Complete/delete response (1189-1263)
- **`success`** (boolean). On false, `tasksMessage = reply` (default "İşlem gerçekleştirilemedi.") and `loadTasks()` (1199-1216).
- **`alarm_id`** (string): used if it is not blank and not `"null"`, otherwise `task.id` (1218-1232).
- **`reply`** (string, default "Görev tamamlandı." or "Görev silindi.") goes into `tasksMessage`.
- **Action:** `cancelReminderByAlarmId(alarmId)`, then `loadTasks()`.
- **Errors:** `onFailure` (1153-1173) and a parse exception (1265-1281) both set a message string.

### D. `/ask` response (1525-1604)
Parsing is `JSONObject(body)`. The body is read before the `!isSuccessful` check (1513-1523), which throws `IOException("Sunucu hatası: code")`.
- **`action`** (string, default ""). It is passed to `handleBackendAction` and is also used in the UI (see section 4).
- **`task_text`** (string)
- **`due_at`** (string)
- **`alarm_id`** (string)
- **`repeat_interval`** (long, default 0)
- **`cancel_due_ats`** (string array via `jsonStringList`, 1643-1662; blank and `"null"` entries are dropped)
- **`cancel_alarm_ids`** (string array, same helper)
- **`reply`** (string). It is spoken via TTS, or put in `tasksMessage` if `fromTaskScreen`.

**`/ask` errors:**
- `onFailure` (1467-1502) sets `addingTask=false`. From the task screen it sets `tasksMessage="Bağlantı kurulamadı. Görev kaydedilemedi."`. Otherwise it shows a Toast "Bağlantı hatası: ${e.message}" and speaks "Bağlantı hatası oluştu." (utteranceId `ZORO_ERROR`).
- A parse or `!isSuccessful` exception (1606-1632) shows "Görev kaydı doğrulanamadı..." on the task screen, otherwise speaks "Sunucu cevabında bir sorun oluştu." (`ZORO_RESPONSE_ERROR`).
- A 401 would fall into `!isSuccessful` and be spoken as the generic error. `YOK` for any special handling.

## 2. UTTERANCE FLOW

- **Service-to-activity:** `ZoroVoiceService` sends a broadcast. The constants are in `ZoroVoiceService.kt:30-50`.
  - `ACTION_COMMAND = "com.zaferyilmaz.zoro.VOICE_COMMAND"`, extra `EXTRA_COMMAND = "voice_command"`
  - `ACTION_STATUS = "com.zaferyilmaz.zoro.VOICE_STATUS"`, extra `EXTRA_STATUS = "voice_status"`
  - Status values are `OFF`, `STARTING`, `LISTENING`, `WAITING_COMMAND`.
- **Receiver:** `voiceCommandReceiver` is an anonymous `BroadcastReceiver` (197-234).
  - STATUS sets `voiceStatus` (207-216).
  - COMMAND takes `getStringExtra(EXTRA_COMMAND).trim()`. If non-empty it calls `askZoro(message = command, refreshTasksAfter = tasksScreenOpen)` (218-231).
- **Registration:**
  - `registerVoiceReceiver()` (513-534) is called from `onCreate` (273).
  - It uses `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)` with a filter of both actions.
  - `commandReceiverRegistered = true` is set at 533.
  - Unregistration is in `onDestroy` only (2097-2104).
  - There is no `onStart`/`onStop` pairing. The receiver exists only while the Activity is alive, so a command is lost if the Activity was destroyed while the service keeps running.
- **Other entry points:**
  - "Zoro'ya Konuş" button calls `startVoiceInput` (642), which opens `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` with `tr-TR` and request code 1001 (658-708).
  - `onActivityResult` (713-747) takes the first result. 1001 calls `askZoro(spokenText)`. 1002 calls `addTaskFromScreen(spokenText)`.
  - `addTaskFromScreen` (922-963) prefixes "Görev ekle " unless the text already starts with "görev ekle". It then calls `askZoro(..., refreshTasksAfter = true, fromTaskScreen = true)`.
- **Local-vs-server decision:** inside `askZoro` (1293), as an `if` chain. Each branch runs on `Thread{}`, calls `speak(answer, id)` on the UI thread, and returns. There is no server call in the local branches.

| Order | Marker comment (line) | Check | Handler | Spoken utteranceId |
|---|---|---|---|---|
| 1 | `ZORO_PERSONAL_MEMORY_ROUTING_V1` (1298), if at 1300 | `ZoroMemoryVoice.isMemoryCommand` | `ZoroMemoryVoice.handleCommand` | `ZORO_MEMORY_REPLY` |
| 2 | `ZORO_CALENDAR_REMINDER_VOICE_ROUTING` (1327), if at 1330 | `ZoroCalendarReminderVoice.isReminderChangeCommand` | `.handleCommand` | `ZORO_REMINDER_VOICE_REPLY` |
| 3 | `ZORO_CALENDAR_EDIT_ROUTING` (1361), if at 1364 | `ZoroCalendarEdit.isEditCommand` (delete or postpone appointment) | `.handleCommand` | `ZORO_CALENDAR_EDIT_REPLY` |
| 4 | `ZORO_CALENDAR_ADD_ROUTING` (1391), if at 1393 | `ZoroCalendarVoice.isCalendarAddCommand` | `ZoroCalendarVoice.addCalendarEvent` | `ZORO_CALENDAR_ADD_REPLY` |
| 5 | `ZORO_CALENDAR_VOICE_ROUTING` (1420), if at 1422 | `ZoroCalendarVoice.isCalendarQuery` | `.getCalendarAnswer` | `ZORO_CALENDAR_REPLY` |
| 6 | falls through | none | POST `/ask` (1451+) | `ZORO_REPLY` |

- **Trigger words:** they live in the other files (`ZoroMemoryVoice.kt`, `ZoroCalendarVoice.kt`, `ZoroCalendarEdit.kt`, `ZoroCalendarReminderVoice.kt`). They are `YOK` in MainActivity and I did not read them.
- **Routing everything to the server:** delete the five `if` blocks, lines 1298-1448.
- **Text input for commands:** `YOK` as a general command field.
  - The only `OutlinedTextField` for commands is the "Yeni Görev Ekle" box on the Tasks screen (2571-2591). It always prefixes "Görev ekle" via `addTaskFromScreen`, then goes through `askZoro`.
  - The edit dialog (2308-2336) has three more fields: task name, date, time.
  - The main screen has no input field.

## 3. TTS

- **Init:** `initializeTextToSpeech()` (406-422), called from `onCreate` (270).
  - It uses `TextToSpeech(this) { ... }` with the default engine. No engine argument.
  - On `SUCCESS` it sets `language = Locale.forLanguageTag("tr-TR")`, `setPitch(0.85f)`, `setSpeechRate(0.95f)`.
  - The result of `setLanguage` is not checked.
- **`speak(text, utteranceId)`** (462-480):
  - It calls `cleanTextForSpeech` (428-460), which strips emoji ranges and `*`, and collapses whitespace.
  - It returns if the result is blank.
  - It calls `textToSpeech.speak(clean, QUEUE_FLUSH, null, utteranceId)`.
  - The Bundle param is null.
- **`UtteranceProgressListener`:** `YOK` in MainActivity. It never calls `setOnUtteranceProgressListener`.
- **Signal to the voice service while speaking:** `YOK`. MainActivity sends no intent or broadcast to `ZoroVoiceService` when it speaks.
  - `ZoroVoiceService` has its own `tts` (`ZoroVoiceService.kt:64`) and its own `speaking` flag (68, used at 131, 622, 865). That `speaking` flag only covers speech from the service's own TTS.
  - The Activity's TTS speech does not pause the service's recognizer. The mic can pick up Zoro's own voice. The coordination is not wired yet.
- **`speak` call sites:**
  - 1318, 1352, 1381, 1411, 1440: local handler replies.
  - 1496: connection error.
  - 1586: server `reply`, only when `!fromTaskScreen` and reply is non-blank.
  - 1626: response error.
- **Teardown:** `textToSpeech.stop()` and `shutdown()` in `onDestroy` (2106-2110).

## 4. SERVER ACTIONS EXECUTED ON PHONE

`handleBackendAction` (1668-1744) switches on `action`.

- **`"add_task"`** (1680-1699):
  - Condition: `task_text` is non-blank AND `due_at` is not blank and not `"null"`.
  - Action: `scheduleReminder(taskText, dueAt, alarmId = alarm_id or dueAt, repeatInterval)`.
  - `scheduleReminder` (1827-1918):
    - It parses `due_at` (2035-2081) as ISO-8601 with offset: `yyyy-MM-dd'T'HH:mm:ss.SSSXXX` or `yyyy-MM-dd'T'HH:mm:ssXXX`. Microseconds are truncated to millis first.
    - A time in the past is skipped.
    - It builds a `PendingIntent` to `ReminderReceiver` with extras `task_text`, `alarm_id`, `repeat_interval`. The request code is `alarmId.hashCode()`.
    - It sets the alarm with `setSafeAlarm` (1750-1821): `setExactAndAllowWhileIdle`, falling back to `setAndAllowWhileIdle`.
    - It then calls `ReminderStore.saveReminder`.
- **`"delete_task"` and `"complete_task"`** (1701-1729): it cancels the alarm in this order.
  1. `alarm_id` if it is non-blank and not `"null"`.
  2. Otherwise `cancelReminderByDueAt(due_at)`.
  3. Otherwise `cancelReminderByTaskText(task_text)`.
- **`"clear_tasks"`** (1731-1742):
  - It cancels every id in `cancel_alarm_ids` and every due in `cancel_due_ats`.
  - It then calls `cancelAllStoredReminders()`.
- **Other actions:** nothing else is handled, and an unknown action is silently ignored.
- **UI-only use of `action`** (1576-1603):
  - `add_task` on the task screen increments `taskSavedCounter`, which clears the input.
  - `loadTasks()` is called when `refreshTasksAfter` is true and the action is one of `add_task`, `delete_task`, `complete_task`, `clear_tasks`.
- **Required JSON shape for an add:**
```
{"action":"add_task","reply":"...","task_text":"...","due_at":"2026-10-31T10:00:00+03:00","alarm_id":"<id>","repeat_interval":0}
```
  - `repeat_interval` is in ms. `86400000` means daily (also checked in the UI at 2171 and 2752).
  - The server must always send `reply` and `action`.
- **Not handled from the server:** calendar, alarm and reminder actions beyond the above are `YOK`. Calendar work is local only.

## 5. COMPOSE UI STRUCTURE

- **Chat message storage and rendering:** `YOK`. There is no chat list or message history. Only these exist:
  - `tasksMessage` (a single status string)
  - `taskItems`
  - `voiceStatus`
  - Replies are only spoken or shown in `tasksMessage`.
- **State:** `mutableStateOf` fields in `MainActivity` (125-153).
- **`setContent`** (275-351):
  - If `calendarScreenOpen`, it shows `ZoroCalendarScreen(onBack, requestCalendarPermission)` (defined in `ZoroCalendarScreen.kt`, not read).
  - Otherwise it shows `ZoroApp(...)` with callbacks.
  - There is a `BackHandler` for the calendar (277).
- **`ZoroApp`** (`@Composable`, 2200-3184), inside `MaterialTheme` (2275):
  - Local state: 2231-2260.
  - `BackHandler` (2268).
  - Edit-dialog effect (2281).
  - Edit `AlertDialog` (2296-2386).
  - Complete/delete confirm `AlertDialog` (2387-2501).
  - **Tasks screen** (`if (tasksScreenOpen)`, 2503-2860):
    - Header (2522-2544).
    - Add-task Card with text field plus "+ Görev Ekle" and "🎙 Sesle Ekle" buttons (2550-2643).
    - Refresh button (2649).
    - Loading, error and empty states (2661-2691).
    - Task cards with Complete, Edit and Delete buttons (2704-2858).
  - **Main screen** (`else`, 2862-3182):
    - Voice status card (2968-2998).
    - Red card with "Merhaba Zafer" and 3 buttons: Speak, Start listening, Stop listening (3004-3080).
    - "Çalışma Alanların" 2-column grid (3086-3174). It has 6 cards: Kişisel, İHAKUT, Görevler (live), Takvim (live), Belgeler, Günlük. The others show "Yakında".
    - Footer (3176).
- **Helper:** `formatTaskDate` (2122-2193).
- **Data models:** `ZoroTaskItem` (85-92) and `enum ZoroTaskOperation` (98-103).

## 6. PERMISSIONS AND PREFERENCES

- **Runtime permission requests** (`requestRequiredPermissions`, 369-400, from `onCreate` 269):
  - `ACCESS_LOCAL_NETWORK`, only if SDK >= 37 (371-384), request code 2001.
  - `POST_NOTIFICATIONS`, only if SDK >= 33 (386-399), request code 2002.
  - There is no `onRequestPermissionsResult` override.
- **`RECORD_AUDIO`:** `microphonePermission` launcher (174-191), triggered from `startVoiceListening` (540-556). On deny it shows the Toast "Mikrofon izni gerekiyor."
- **`READ_CALENDAR`:** `calendarPermissionLauncher` (135-140), requested only from the calendar screen. The check runs in `onResume` (359-362).
- **Manifest** (`AndroidManifest.xml:4-14`):
  - `RECORD_AUDIO`, `INTERNET`, `ACCESS_LOCAL_NETWORK`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`
  - `SCHEDULE_EXACT_ALARM`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`
  - `READ_CALENDAR`, `WRITE_CALENDAR`
  - `WRITE_CALENDAR` is declared but never requested at runtime in MainActivity.
  - There is no `ACCESS_NETWORK_STATE`.
- **SharedPreferences in MainActivity:** only one.
  - `getSharedPreferences(ZoroVoiceService.STATUS_PREFS = "zoro_voice_status", MODE_PRIVATE)`, key `STATUS_PREF_KEY = "current_status"` (161-167).
  - It is read by `readSavedVoiceStatus()` in `onCreate` (272) and `onResume` (357).
- **Other stores:** reminders go through `ReminderStore` (`ReminderStore.kt`, not read). Its prefs names and keys are unknown to me.
- **Notification channel:** `zoro_reminders` ("Zoro Hatırlatmaları", HIGH), created at 486-507.
- **Morning briefing:** `ZoroMorningBriefingScheduler.scheduleNext(applicationContext)` at 247 (daily at 10:00, per the code comment).

## 7. NOTES FOR "CONFIGURABLE URL + AUTH HEADER + ROUTE ALL TO SERVER"

1. **URLs:** swap the two hardcoded strings (115-119) for values read from prefs or BuildConfig.
   - Make them computed properties. A `val` initialiser would freeze the value at construction.
   - Keep `tasksUrl` as the base for `/edit`, `/complete`, `/delete` (995, 1138).
   - Other files may also hit the server. I did not check `ZoroCalendarVoice.kt`, `ZoroMemoryManeger.kt`, `ZoroMorningBriefingReceiver.kt` or `ZoroVoiceService.kt`.
2. **Auth header:** the cleanest way is an OkHttp `Interceptor` on `httpClient` (113), which covers all 4 calls at once. Today `Request.Builder` is used at 770, 994, 1145, 1459 with no headers.
3. **401/403:** no special handling exists. `/ask` reports it as a generic "Sunucu cevabında bir sorun oluştu." (spoken). Task calls give "Sunucu hatası: 401" or "İşlem sonucu doğrulanamadı".
4. **Routing all commands to the server:** delete or bypass 1298-1448. `ZoroCalendarVoice` and the others do real phone work (CalendarProvider).
   - The server would then have to return an action the phone can execute.
   - Only `add_task`, `delete_task`, `complete_task` and `clear_tasks` are executable today.
   - Any new action needs a new branch in `handleBackendAction` (1678).
   - Calendar, memory and reminder-duration actions are not wired to server actions.
5. **Request body is minimal:** `/ask` sends only `{"message"}`. No device id, locale, time zone or conversation id.
6. **Offline behaviour:** a connection failure is spoken as "Bağlantı hatası oluştu." (1496-1499). There is no retry and no queue.
7. **TTS and voice service overlap:** the Activity's TTS and the service's recognizer are not coordinated (see section 3). Server replies spoken through the Activity can be heard by the always-on recognizer.
8. **Receiver lifecycle:** the receiver is registered in `onCreate` and unregistered in `onDestroy`. If the Activity is destroyed, a voice command is dropped.
9. **Activity-bound HTTP:** `onDestroy` calls `dispatcher.cancelAll()` (2112), so in-flight requests die with the Activity.
10. **Cleartext and local network:** HTTP is only allowed through the blanket `usesCleartextTraffic="true"`. Moving to an HTTPS or public server would not need the `ACCESS_LOCAL_NETWORK` request (371-384).
11. **Hardcoded UI strings that depend on each other:** the edit dialog closes by comparing `tasksMessage` to the literal strings `"Görev güncellendi."` and `"Görev kaydedildi ancak alarm kontrol edilmeli."` (2284-2285). The same literals are set at 1060 and 1069. Do not change one without the other.
12. **Dates:**
    - The server sends ISO-8601 with offset for `due_at` (microseconds are tolerated).
    - The edit screen slices `dueAt.take(10)` and `substring(11, 16)` (2805-2807), and the edit POST sends `yyyy-MM-ddTHH:mm` with no offset (2351). The server must accept that format.
13. **Task id doubles as alarm id:**
    - Local alarm identity is `alarm_id.hashCode()` (1876, 1978).
    - An edit re-schedules with `alarmId = task.id` (1054).
    - The server's `alarm_id` and `id` must stay consistent.
    - Otherwise cancel does nothing, because `PendingIntent.FLAG_NO_CREATE` returns null.
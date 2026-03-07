# Stage 37: Fix compilation errors + Picovoice Wake Word + Актуализация документов

## Справочные материалы

- **DAT SDK Real API**: `Documents/dat-sdk-real-api.md` — реальный API извлечённый из AAR JARs через `javap`. ОБЯЗАТЕЛЬНО прочитать перед исправлением GlassesManager.kt
- **DAT SDK Documentation**: https://wearables.developer.meta.com/docs/ai-solutions — официальная документация Meta Wearables SDK

---

## Задача 1: ИСПРАВИТЬ 12 ошибок компиляции (ПРИОРИТЕТ 1)

### Контекст

Полная компиляция (`./gradlew assembleDebug`) с `minSdk = 29` (уже исправлено) выявила 12 ошибок Kotlin. DAT SDK зависимости скачиваются успешно (mwdat-core:0.4.0, mwdat-camera:0.4.0), но код использует API неправильно.

### ОБЯЗАТЕЛЬНО прочитать перед исправлением

Файл `Documents/dat-sdk-real-api.md` — полный справочник реального API, извлечённый из AAR JARs через `javap`. Там таблица различий между тем что в коде и реальным API.

### Список ошибок

#### GlassesManager.kt

1. **Строка ~730**: `Unresolved reference 'startStreamSession'`
   - `startStreamSession` — extension function, нужен import: `import com.meta.wearable.dat.camera.startStreamSession`

2. **Строки ~518, ~528**: `Cannot infer a type for this parameter`
   - Лямбды `session.state` и `session.videoStream` — явно указать тип параметра

3. **RegistrationState**: используется как enum (`RegistrationState.REGISTERED`), а реально sealed class
   - Заменить на `is RegistrationState.Registered`

4. **PermissionStatus**: используется как enum, а реально sealed interface
   - Заменить на `is PermissionStatus.Granted`

5. **Wearables.initialize()**: вызывается синхронно, а реально suspend function
   - Вызывать из coroutine scope, обработать `DatResult`

6. **checkPermissionStatus()**: вызывается синхронно, а реально suspend function
   - Вызывать из coroutine scope

7. **capturePhoto()**: ожидается `DatResult?`, а возвращает `kotlin.Result<PhotoData>`
   - Использовать `result.getOrNull()` / `result.onSuccess {}` от kotlin.Result

#### PorcupineWakeWordEngine.kt

8. **Строка ~58**: `Unresolved reference 'setBuiltInKeyword'`
   - Проверить актуальный API `Porcupine.Builder` для версии в libs.versions.toml

#### VoiceOrchestrator.kt

9. **Строка ~70**: `Type mismatch: WakeWordDetected`
   - Проверить определение, передать нужные параметры конструктора

#### OfflineSttService.kt

10. **Строка ~171**: `Unresolved reference 'launch'`
    - Добавить import `kotlinx.coroutines.launch` или использовать scope

#### TranslationManager.kt

11. **Строка ~104**: `Suspend function called from a non-coroutine context`
    - Обернуть в coroutine scope или сделать вызывающую функцию suspend

#### LiveCommentaryService.kt

12. **Строка ~78**: `Unresolved reference 'value'`
    - Проверить тип переменной, использовать правильное свойство

### Порядок исправления

1. Прочитать `Documents/dat-sdk-real-api.md` целиком
2. Исправить GlassesManager.kt (ошибки 1-7) — самый критичный файл
3. Исправить PorcupineWakeWordEngine.kt (ошибка 8)
4. Исправить остальные файлы (ошибки 9-12)
5. Запустить `./gradlew assembleDebug` и убедиться что компиляция проходит
6. Запустить `./gradlew test` и убедиться что тесты проходят

---

## Задача 2: Picovoice Porcupine Wake Word SDK

### Текущее состояние

`speech/WakeWordService.kt` содержит MVP energy-based VAD:
- `detectWakeWord()` анализирует RMS, ZCR, energy profile
- Работает как pre-filter, STT подтверждает через транскрипт
- Высокий false positive rate, не подходит для продакшена

### Что сделать

1. **Добавить Picovoice Porcupine SDK зависимость** в `build.gradle.kts`:
   ```
   implementation("ai.picovoice:porcupine-android:3.x.x")
   ```

2. **Создать `speech/PorcupineWakeWordEngine.kt`**:
   - Реализовать интерфейс `WakeWordEngine` (создать его)
   - `init()` — создать `Porcupine.Builder()` с Access Key и custom keyword path
   - `process(shortArray)` — обработка аудио фреймов
   - `release()` — освобождение ресурсов
   - Custom keyword "Взор" — файл `.ppn` (placeholder путь, ключ из настроек)

3. **Рефакторинг `WakeWordService.kt`**:
   - Извлечь интерфейс `WakeWordEngine` с методами `process(pcmData: ShortArray): Boolean`, `release()`
   - Переименовать текущую energy-based логику в `EnergyWakeWordEngine` (fallback)
   - `WakeWordService` использует `PorcupineWakeWordEngine` если Access Key есть, иначе `EnergyWakeWordEngine`
   - Access Key берётся из `PreferencesManager` (добавить `picovoiceAccessKey: Flow<String>`)

4. **UI**: Добавить поле ввода Picovoice Access Key в `SettingsScreen` (рядом с остальными API ключами)

5. **Тесты**:
   - `PorcupineWakeWordEngineTest` — unit тест с mock Porcupine
   - `WakeWordServiceTest` — проверить fallback с Energy engine когда нет ключа

### Ограничения
- Picovoice Access Key нужен для работы (бесплатный tier: 3 months)
- Custom keyword "Взор" (`.ppn` файл) генерируется в Picovoice Console — пока использовать встроенное keyword или placeholder
- Если Access Key не указан — fallback на текущий EnergyWakeWordEngine

---

## Задача 3: Актуализация документов

### КРИТИЧЕСКАЯ ОШИБКА в документации

Файлы `Documents/remaining_gaps.md` и `Documents/compliance_report_v11.md` содержат **фактическую ошибку**: DAT SDK описан как "stub-реализация" / "stubs".

**Это НЕВЕРНО.** Прочитай `glasses/GlassesManager.kt` — там полная реализация (Stages 8.2-8.5).

### remaining_gaps.md — ПЕРЕПИСАТЬ секцию 1.1

Заменить:
```
### 1.1 Meta DAT SDK (Tier 1 — 10% weight)
- **Статус:** Приватный SDK, доступ только select partners (2026)
- **Влияние:** Блокирует camera streaming с Ray-Ban Meta Gen 2
- **Текущий workaround:** Stub-реализация в GlassesManager
```

На:
```
### 1.1 Meta DAT SDK (Tier 1 — 10% weight)
- **Статус:** Полностью реализован в GlassesManager.kt (Stages 8.2-8.5)
- **Что работает:** device registration, camera streaming (I420→NV21→JPEG), photo capture (Bitmap/HEIC), permission checks
- **Ограничение:** SDK приватный (select partners) — тестирование только с реальными очками + Meta developer access
- **Что не реализовано:** mwdat-mockdevice тесты (нет в scope — требует физическое устройство)
```

### compliance_report_v11.md — ИСПРАВИТЬ оценки

- Tier 1 Sensor: поднять с 3.5/10 до **7/10** (DAT SDK реализован, Picovoice — единственный gap)
- Строка "Camera (12MP) | GlassesManager + DAT SDK stubs" → "Camera (12MP) | GlassesManager + DAT SDK (полная реализация)"
- После реализации Picovoice: Tier 1 → 8.5/10

---

## Порядок выполнения (общий)

1. Прочитать `Documents/dat-sdk-real-api.md` — реальный API SDK
2. **Исправить 12 ошибок компиляции (Задача 1)** — ПРИОРИТЕТ 1
3. Исправить `Documents/remaining_gaps.md` — секция 1.1
4. Исправить `Documents/compliance_report_v11.md` — оценки Tier 1
5. Реализовать Picovoice wake word (задача 2) — если ещё не сделано
6. Обновить compliance report с новыми оценками
7. Коммит + push

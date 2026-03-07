# Stage 37: Picovoice Porcupine Wake Word + Актуализация документов

## Контекст

### КРИТИЧЕСКАЯ ОШИБКА в документации — ИСПРАВИТЬ В ПЕРВУЮ ОЧЕРЕДЬ

Файлы `Documents/remaining_gaps.md` и `Documents/compliance_report_v11.md` содержат **фактическую ошибку**: DAT SDK описан как "stub-реализация" / "stubs".

**Это НЕВЕРНО.** Прочитай `glasses/GlassesManager.kt` — там полная реализация:
- `Wearables.initialize(context)` (строка 134)
- `Wearables.startRegistration(activity)` (строка 163)
- `session.capturePhoto()` → `extractPhotoBytes()` (строки 444-465)
- `Wearables.startStreamSession()` → I420→NV21→JPEG конвертация (строки 508-536)
- `Wearables.checkPermissionStatus(DatPermission.CAMERA)` (строка 406)
- `convertI420toNV21()` — byte-level YUV конвертер (строки 773-792)
- `extractPhotoBytes()` — PhotoData.Bitmap/HEIC обработка (строки 801-828)

DAT SDK полностью реализован с Stage 8.2-8.5. Единственное ограничение — SDK приватный (select partners), поэтому тестировать можно только с реальными очками + Meta developer access.

### Что РЕАЛЬНО осталось для 95%+

1. **Picovoice Porcupine** — заменить energy-based heuristic в WakeWordService на реальный SDK (главная задача)
2. **Актуализация документов** — исправить ложную информацию о "stub" DAT SDK

---

## Задача 1: Picovoice Porcupine Wake Word SDK

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

## Задача 2: Актуализация документов

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
- Строка "Camera ingest | Stub (DAT SDK блокер)" → "Camera ingest | Реализован (DAT SDK)"
- После реализации Picovoice: Tier 1 → 8.5/10
- Общий процент: пересчитать с учётом реального Tier 1

### Пересчёт общего процента

| Tier | Вес | Реальный прогресс | Взвешенный |
|------|:---:|:-----------------:|:----------:|
| Tier 1 — Sensor | 10% | 70% (DAT реализован, Picovoice нет) | 7.0% |
| Tier 2 — Orchestration | 35% | 100% | 35.0% |
| Tier 3 — Edge AI | 15% | 95% | 14.25% |
| Tier 4 — Cloud | 20% | 100% | 20.0% |
| Use Cases | 15% | 100% | 15.0% |
| Translation | 5% | 95% | 4.75% |
| **Итого** | **100%** | | **96.0%** |

После Picovoice: Tier 1 → 85%, итого **97.5%**

---

## Порядок выполнения

1. Прочитать `glasses/GlassesManager.kt` и убедиться что DAT SDK реализован (не stub)
2. Исправить `Documents/remaining_gaps.md` — секция 1.1
3. Исправить `Documents/compliance_report_v11.md` — оценки Tier 1
4. Реализовать Picovoice wake word (задача 1)
5. Обновить compliance report с новыми оценками
6. Коммит + push

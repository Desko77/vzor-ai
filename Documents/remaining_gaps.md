# Vzor: Оставшиеся пробелы и зависимости

**Дата:** 2026-03-07
**Текущая реализация ТЗ:** ~96%
**Review backlog:** 1 open / 49 closed

---

## 1. Критические внешние блокеры (не решаемые кодом)

### 1.1 Meta DAT SDK (Tier 1 — 10% weight)
- **Статус:** Полностью реализован в GlassesManager.kt (Stages 8.2-8.5, расширен в Stage 37)
- **Что работает:** device registration (`Wearables.startRegistration`), camera streaming (I420→NV21→JPEG), photo capture (Bitmap/HEIC), permission checks, DatDeviceManager (device info, battery, permissions), ConnectionHealthMonitor
- **Ограничение:** SDK приватный (select partners) — тестирование только с реальными очками + Meta developer access
- **Тесты:** MockDeviceIntegrationTest, DatSdkIntegrationTest, DatDeviceManagerTest, ConnectionHealthMonitorTest
- **Файлы:** `glasses/GlassesManager.kt`, `glasses/DatDeviceManager.kt`, `glasses/ConnectionHealthMonitor.kt`

### 1.2 Picovoice Porcupine (Wake Word)
- **Статус:** Нужен Access Key из Picovoice Console
- **Влияние:** Точное обнаружение wake word "Взор"
- **Текущий workaround:** Energy-based VAD + ZCR heuristic в WakeWordService
- **Что нужно:** Picovoice Access Key + обучение custom keyword "Взор"
- **Файлы:** `speech/WakeWordService.kt`
- **Альтернатива:** openWakeWord (open-source, ONNX) — можно реализовать

---

## 2. Средний приоритет (решаемые)

### 2.1 MasterKeys API (deprecated)
- **Статус:** `MasterKeys.getOrCreate()` deprecated в security-crypto 1.0.0
- **Влияние:** Работает, но warning при компиляции
- **Решение:** Миграция на `MasterKey.Builder()` из security-crypto 1.1.0
- **Блокер:** security-crypto 1.1.0 пока не стабильная
- **Файл:** `data/local/PreferencesManager.kt:29`, `di/AppModule.kt:159`

### 2.2 openWakeWord как fallback
- **Статус:** Не реализован
- **Влияние:** Улучшит точность wake word без Picovoice
- **Решение:** Интеграция ONNX Runtime + openWakeWord модель
- **Оценка:** ~2-3 dev-days
- **Файл:** новый `speech/OpenWakeWordEngine.kt`

---

## 3. Низкий приоритет (nice-to-have)

### 3.1 ONNX Whisper для offline STT WAV fallback
- **Статус:** `transcribeFromWav()` returns null (fallback path)
- **Влияние:** Минимальное — основной путь через SpeechRecognizer работает
- **Решение:** ONNX Runtime + Whisper tiny/base модель
- **Оценка:** ~3-5 dev-days
- **Файл:** `speech/OfflineSttService.kt:346`

### 3.2 Notification icon (branding)
- **Статус:** Реализован (ic_notification_vzor.xml), но не протестирован на устройстве
- **Файл:** `glasses/GlassesNotificationManager.kt`

---

## 4. Зависимости для продакшена

| Зависимость | Тип | Статус | Оценка |
|-------------|-----|--------|--------|
| Meta DAT SDK | Внешний SDK | Реализован (тестирование блокировано) | Физические очки |
| Picovoice Access Key | Лицензия | Ожидание | ~$100/мес |
| Yandex SpeechKit IAM token | API ключ | Нужен | Бесплатный tier |
| ACRCloud credentials | API ключ | Нужен | Бесплатный tier |
| Google Cloud TTS API key | API ключ | Нужен | Бесплатный tier |
| Tavily Search API key | API ключ | Нужен | Бесплатный tier |
| Ollama на AI Max сервере | Инфраструктура | Настроить | Self-hosted |
| ProGuard/R8 правила | Конфигурация | Не тестировано | ~1 dev-day |
| Signing config (release) | Конфигурация | Не настроен | ~0.5 dev-day |

---

## 5. Тестирование перед релизом

| Область | Статус | Что нужно |
|---------|--------|-----------|
| Unit тесты | ~480 тестов | Покрытие хорошее |
| Интеграционные тесты | Нет | Android Instrumented Tests |
| UI тесты | Нет | Compose UI tests |
| E2E Bluetooth тесты | Нет | Физическое устройство + очки |
| ProGuard тестирование | Нет | assembleRelease + проверка |
| Нагрузочное тестирование | Нет | Ollama latency, memory leaks |
| Accessibility audit | Нет | TalkBack, контрастность |

---

## 6. Текущий прогресс по Tiers

| Tier | Вес | Прогресс | Лимитирующий фактор |
|------|:---:|:--------:|---------------------|
| Tier 1 — Sensor | 10% | 70% | Picovoice (wake word) |
| Tier 2 — Orchestration | 35% | 100% | — |
| Tier 3 — Edge AI | 15% | 95% | — |
| Tier 4 — Cloud | 20% | 100% | — |
| Use Cases | 15% | 100% | — |
| Translation | 5% | 95% | — |
| **Итого** | **100%** | **~96%** | Picovoice wake word |

---

## 7. Приоритеты следующих шагов

1. **Picovoice Porcupine:** PorcupineWakeWordEngine реализован с fallback на EnergyWakeWordEngine (+15% to Tier 1 при наличии Access Key)
2. **Физические очки:** тестирование DAT SDK с реальными Ray-Ban Meta Gen 2
3. **Без внешних зависимостей:** ProGuard правила, signing config, интеграционные тесты

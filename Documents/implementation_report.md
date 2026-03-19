# Vzor AI — Отчёт о реализации

**Дата:** 2026-03-09
**Ветка:** `claude/android-ai-assistant-meta-Sm71O`
**Покрытие ТЗ:** 97.5%
**Статус сборки:** BUILD SUCCESSFUL, 569/569 тестов проходят

---

## 1. Что реализовано

### Архитектура

Clean Architecture (Presentation → Domain → Data), Hilt DI, 130 Kotlin-файлов (main), 47 тестовых файлов, ~20 800 строк кода.

### AI-провайдеры (6 штук)

| Провайдер | Модель | Назначение |
|-----------|--------|------------|
| Google Gemini | gemini-pro / gemini-pro-vision | Облако (по умолчанию) |
| Anthropic Claude | claude-3.5-sonnet | Облако, tool calling |
| OpenAI GPT-4o | gpt-4o | Облако, function calling |
| Zhipu GLM-5 | glm-5 | Облако (китайский LLM) |
| Local Qwen | Qwen3.5-9B via Ollama | Домашний сервер (Wi-Fi) |
| Offline Qwen | Qwen3.5-4B via MLC LLM | On-device (без сети) |

### STT (Speech-to-Text)

| Провайдер | Режим | Статус |
|-----------|-------|--------|
| OpenAI Whisper | Batch API | Реализован |
| Yandex SpeechKit v3 | WebSocket стриминг | Реализован |
| Google STT | Android SpeechRecognizer | Реализован |
| Offline STT | Android on-device recognizer | Реализован (WAV fallback — заглушка) |

### TTS (Text-to-Speech)

| Провайдер | Голос | Язык |
|-----------|-------|------|
| Yandex SpeechKit | Alena | RU |
| Google Cloud TTS | Neural2 | EN |
| Автодетекция языка | Unicode script detection | RU+EN микс |

### Vision Pipeline

- **MediaPipe**: on-device object detection, face detection
- **Qwen-VL via Ollama**: структурированное распознавание (label + confidence + bbox)
- **Gemini Vision**: облачный анализ изображений
- **Claude Vision**: облачный анализ с tool calling
- **CLIP ViT-B/32 (VLM via Ollama)**: семантическое сравнение сцен
- **Adaptive FrameSampler**: событийное повышение FPS, BURST mode, battery saver

### Voice Pipeline

```
Wake Word ("Взор") → STT → Intent Router → LLM → TTS → Ответ
```

- **VoiceOrchestrator (FSM)**: IDLE → LISTENING → PROCESSING → GENERATING → RESPONDING
- **Barge-in**: прерывание ответа голосом
- **IntentClassifier**: 20 типов интентов, regex + keyword scoring
- **BackendRouter**: выбор бэкенда по сети/нагрузке/сложности

### Tool Calling (20 инструментов)

| Инструмент | Назначение |
|------------|------------|
| memory.get / memory.set | Persistent memory (SQLite) |
| web.search | Поиск через Tavily API |
| weather.get | Погода |
| translate.text | Перевод через Yandex Translate |
| device.get_time | Текущее время |
| device.get_location | Геолокация |
| device.get_battery | Уровень заряда |
| music.identify | ACRCloud аудио-распознавание |
| music.play | Запуск воспроизведения |
| photo.capture | Фото через Meta glasses |
| video.capture | Видеозапись |
| contacts.search | Поиск контактов |
| phone.call | Звонок |
| message.send | Отправка сообщения |
| navigate.to | Навигация |
| calendar.add | Добавление события |
| alarm.set | Будильник |
| reminder.set | Напоминание |
| food.analyze | Анализ еды/калорий |
| shopping.compare | Сравнение цен |

### Перевод (Translation)

- Yandex Translate API (RU⇄EN и другие языки)
- SubtitleOverlayService: субтитры поверх экрана
- 3 режима: слушаю собеседника (A), говорю сам (B), двусторонний (C)

### Use Cases (16/16 реализованы)

1. Распознавание объектов
2. Идентификация мест
3. Перевод текста с фото
4. Анализ еды / калории
5. Шопинг-помощник
6. Q&A (вопросы-ответы)
7. Живой AI-комментарий (Live Commentary)
8. Доступность (Be My Eyes)
9. Управление звонками / сообщениями
10. Управление музыкой
11. Напоминания / календарь
12. Фото / видео hands-free
13. Живой перевод речи
14. Память (где припарковал и т.д.)
15. Определение контакта по контексту
16. Захват видео + субтитры

### Безопасность

- **API ключи**: EncryptedSharedPreferences (AES256_GCM, Android Keystore)
- **База данных**: SQLCipher (AES-256)
- **PII masking**: телефоны маскируются (***1234) при disambiguации контактов
- **Prompt injection**: пользовательский ввод обёрнут в `<user_request>` теги

---

## 2. Какие токены/ключи нужны

Все ключи вводятся в настройках приложения и хранятся в зашифрованном хранилище.

### Обязательные (для базовой работы)

| Ключ | Где получить | Бесплатный уровень |
|------|-------------|-------------------|
| **Gemini API Key** | [Google AI Studio](https://aistudio.google.com/) | Да, 60 req/min |
| **Yandex API Key** | [Yandex Cloud Console](https://console.yandex.cloud/) | Да, ограниченный |

### Для расширенных функций

| Ключ | Где получить | Назначение | Бесплатный уровень |
|------|-------------|-----------|-------------------|
| **Claude API Key** | [Anthropic Console](https://console.anthropic.com/) | Claude LLM + tool calling | Нет (платный) |
| **OpenAI API Key** | [OpenAI Platform](https://platform.openai.com/) | GPT-4o + Whisper STT | Нет (платный) |
| **GLM API Key** | [Zhipu Open Platform](https://open.bigmodel.cn/) | GLM-5 (альтернативный LLM) | Да |
| **Tavily API Key** | [Tavily](https://tavily.com/) | Веб-поиск | Да, 1000 req/month |
| **ACRCloud Access Key + Secret** | [ACRCloud Console](https://console.acrcloud.com/) | Распознавание музыки | Да, ограниченный |
| **ACRCloud Host** | ACRCloud Console | Хост для API | — |
| **Picovoice Access Key** | [Picovoice Console](https://console.picovoice.ai/) | Wake word "Взор" | Да, 3 месяца |

### Инфраструктура (не ключи)

| Компонент | Настройка | Описание |
|-----------|-----------|---------|
| **Local AI Host** | IP:port Ollama сервера | Для домашнего AI Max (Qwen3.5-9B) |
| **Home SSID** | Имя домашней Wi-Fi сети | Для автопереключения на локальный бэкенд |
| **Meta DAT SDK** | GITHUB_USER + GITHUB_TOKEN | Maven-репозиторий Meta (в gradle.properties) |

### Для сборки проекта

| Переменная | Назначение |
|------------|-----------|
| `GITHUB_USER` | Логин GitHub (для Maven repo Meta SDK) |
| `GITHUB_TOKEN` | Personal Access Token с правом `read:packages` |

Задаются в `~/.gradle/gradle.properties`:
```properties
GITHUB_USER=your-github-username
GITHUB_TOKEN=ghp_your-token
```

---

## 3. Настройки приложения

| Настройка | Ключ DataStore | Значения | По умолчанию |
|-----------|---------------|---------|-------------|
| AI-провайдер | `ai_provider` | GEMINI, CLAUDE, OPENAI, GLM_5, LOCAL_QWEN, OFFLINE_QWEN | GEMINI |
| STT-провайдер | `stt_provider` | WHISPER, YANDEX, GOOGLE, OFFLINE | WHISPER |
| TTS-провайдер | `tts_provider` | YANDEX, GOOGLE | GOOGLE |
| Системный промпт | `system_prompt` | Произвольный текст | — |
| Хост локального AI | `local_ai_host` | IP:port | — |
| Домашняя Wi-Fi | `home_ssid` | SSID | — |
| Режим разработчика | `developer_mode` | true/false | false |

---

## 4. Зависимости и версии

| Зависимость | Версия |
|-------------|--------|
| Kotlin | 2.1.0 |
| Compose BOM | 2025.01.01 |
| Hilt | 2.53.1 |
| Retrofit | 2.11.0 |
| OkHttp | 4.12.0 |
| Moshi | 1.15.1 |
| Coroutines | 1.9.0 |
| Room | 2.6.1 |
| DataStore | 1.1.1 |
| SQLCipher | 4.6.1 |
| MediaPipe Vision | 0.10.29 |
| ML Kit Text Recognition | 16.0.1 |
| Generative AI (Gemini) | 0.9.0 |
| Meta Wearables DAT SDK | 0.5.0 |
| Picovoice Porcupine | 3.0.3 |
| MockK | 1.13.13 |
| Turbine | 1.2.0 |

**Min SDK:** 29 | **Target SDK:** 35 | **Java:** 17

---

## 5. Тесты

**Всего:** 569 тестов в 47 файлах, все проходят.

| Область | Файлов | Примеры |
|---------|--------|---------|
| Vision | 15 | FrameSampler, SceneComposer, CLIP, MediaPipe, Accessibility |
| Orchestration | 9 | VoiceOrchestrator, IntentClassifier, BackendRouter, ToolRegistry |
| Speech | 7 | AudioContextDetector, WakeWord, SpeakerDiarizer, OfflineSTT |
| Glasses/HW | 5 | DatDeviceManager, GlassesManager, ConnectionHealth |
| Actions | 3 | ActionConfirmation, PhotoCapture, VideoCapture |
| Translation | 2 | TranslationManager, SubtitleOverlay |
| TTS | 2 | SentenceSegmenter, TtsManagerSegmentation |
| Data/Remote | 2 | AcrCloudService, StreamChunk |
| Context | 1 | ContextManager |
| Repository | 1 | MemoryRepository |

---

## 6. Что НЕ реализовано / блокеры

### Блокеры (не решаются кодом)

| Проблема | Описание | Что нужно |
|----------|---------|-----------|
| Meta DAT SDK тестирование | Код написан, но не проверен на реальных очках | Ray-Ban Meta Gen 2 + Meta developer access |
| Picovoice production | Бесплатный ключ действует 3 месяца | Платная подписка (~$100/мес) |
| Кастомное wake word "Взор" | Нужен .ppn файл от Picovoice | Picovoice Console → Train keyword |

### Технический долг

| Проблема | Приоритет | Описание |
|----------|-----------|---------|
| MasterKeys API deprecated | Medium | security-crypto 1.0.0, ждём стабильную 1.1.0 |
| ONNX Whisper WAV fallback | Low | `transcribeFromWav()` возвращает null |
| openWakeWord fallback | Low | Альтернатива Picovoice (open source) |
| ProGuard/R8 тестирование | Low | Правила написаны, не проверены |
| Release signing config | Low | Не настроен |

---

## 7. Как собрать и запустить

```bash
# Клонировать
git clone https://github.com/user/vzor-ai.git
cd vzor-ai

# Настроить Meta SDK доступ
echo "GITHUB_USER=your-user" >> ~/.gradle/gradle.properties
echo "GITHUB_TOKEN=ghp_your-token" >> ~/.gradle/gradle.properties

# Собрать debug APK
./gradlew assembleDebug

# Запустить тесты
./gradlew test

# Собрать release APK
./gradlew assembleRelease
```

После установки APK — ввести API ключи в Настройках приложения.

---

## 8. Документация проекта

| Файл | Содержимое |
|------|-----------|
| `CLAUDE.md` | Описание проекта, архитектура, команды сборки |
| `Documents/vzor-architecture.html` | Системная архитектура (4 слоя, pipelines) |
| `Documents/vzor_ui_prototype.html` | MD3 прототип UI (393×852px) |
| `Documents/dat-sdk-real-api.md` | Реальный API Meta DAT SDK 0.5.0 |
| `Documents/compliance_report_v11.md` | Отчёт соответствия ТЗ v11 |
| `Documents/remaining_gaps.md` | Оставшиеся пробелы и приоритеты |

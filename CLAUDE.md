# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**Vzor** — русскоязычный AI-ассистент для умных очков Ray-Ban Meta Gen 2, работающий через Android companion-приложение на Galaxy Z Fold 7. Голосовое управление, компьютерное зрение, мультиязычный TTS, синхронный перевод.

Лицензия: GNU GPL v3.

**Базовые проекты:**
- VisionClaw (sseanliu/VisionClaw) — референсная реализация Meta DAT SDK на Android (Kotlin)
- OpenVision (rayl15/OpenVision) — эталон архитектуры (iOS, паттерны перенесены)

## Tech Stack

- **Language:** Kotlin 2.1.0
- **UI:** Jetpack Compose + Material Design 3
- **DI:** Hilt 2.53.1
- **Database:** Room 2.6.1 (история), SQLite (persistent memory)
- **Preferences:** DataStore 1.1.1
- **Network:** Retrofit 2.11.0 + OkHttp 4.12.0
- **Serialization:** Moshi 1.15.1
- **Async:** Coroutines 1.9.0
- **Build:** Gradle 8.7.3 (Kotlin DSL), Java 17
- **Min SDK:** 26, Target SDK: 35

## Build Commands

```bash
./gradlew assembleDebug          # debug APK
./gradlew assembleRelease        # release APK (ProGuard/R8)
./gradlew test                   # все тесты
./gradlew test --tests "com.vzor.ClassName.testName"  # один тест
./gradlew lint                   # lint
./gradlew clean                  # очистка
```

**Meta Wearables SDK** требует `GITHUB_USER` и `GITHUB_TOKEN` в `gradle.properties` или переменных окружения для доступа к приватному maven-репозиторию.

## Architecture

Clean Architecture, три слоя:

```
Presentation (UI)          — Compose screens, ViewModels
    ↓
Domain (Business Logic)    — Repository interfaces, Models
    ↓
Data (Persistence/Remote)  — Room DB, Retrofit services, DataStore
```

### Основной voice pipeline

```
Голос (BT HFP mic) → STT (Whisper/Yandex) → Intent Router → LLM → TTS (Yandex/Google) → Ответ (BT A2DP speaker)
```

### Vision pipeline

```
Фото (12MP camera, Meta SDK) → Кадр (720p/30fps) → Vision LLM (Qwen3.5) → TTS → Ответ
```

### AI-бэкенды (взаимозаменяемые, Intent Router)

| Контекст | Бэкенд | Модель | Латентность |
|----------|--------|--------|-------------|
| Дома (Wi-Fi) | AI Max (локальный) | Qwen3.5-9B via Ollama | ~150-300ms |
| На улице (LTE) | Cloud API | Claude / GPT-4o / Gemini | ~400-700ms |
| Офлайн | On-Device (Fold 7) | Qwen3.5-4B via MLC LLM | ~1-2s |

Intent Router — лёгкий классификатор (Qwen3.5-0.8B), определяет сложность запроса, доступность сети, тип контента и приоритет скорости vs качества.

### VoiceOrchestrator (FSM)

Централизованный контроллер состояний: IDLE → LISTENING → PROCESSING → GENERATING → RESPONDING. Предотвращает гонки между STT, TTS и Actions. Поддерживает barge-in (прерывание на этапе GENERATING).

### Context Manager — двухуровневая память

- **Session Memory (RAM):** последние 20 реплик, текущий контекст задачи, временные данные
- **Persistent Memory (SQLite):** умные предпочтения (не сырые логи), ~100 записей с LRU вытеснением. После каждого сеанса LLM решает что запомнить

### STT — гибридная стратегия

- Yandex SpeechKit — стриминг, низкая латентность (основной)
- Whisper API — высокая точность, batch-режим (fallback + offline)
- Wake word «Взор» — Picovoice Porcupine / openWakeWord

### TTS — мультиязычность

- RU: Yandex SpeechKit (голос Alena)
- EN: Google Cloud TTS (Neural2)
- Микс RU+EN: автодетекция языка через Unicode script detection, сегментация по токенам, последовательный синтез

### Синхронный перевод (отдельный режим)

Три сценария: A (слушаю собеседника), B (говорю сам), C (двусторонний). Вывод через наушник (TTS), динамик телефона или субтитры на экране.

## Key Modules

```
app/src/main/java/com/vzor/
  ui/
    chat/          — ChatScreen (основной экран), ChatViewModel (стриминг ответов)
    settings/      — SettingsScreen, SettingsViewModel (провайдер, ключи, STT/TTS)
    navigation/    — NavGraph (маршруты)
    theme/         — MD3 цвета, типографика, тема
  domain/
    model/         — Message, Conversation, AiProvider, GlassesState
    repository/    — AiRepository, ConversationRepository, VisionRepository (interfaces)
  data/
    local/         — AppDatabase (Room), PreferencesManager (DataStore)
    remote/        — GeminiService, OpenAiApiService, ClaudeApiService
    repository/    — реализации domain interfaces
  glasses/         — GlassesManager (BT 5.3, Meta Wearables DAT, camera stream)
  speech/          — SttService (Whisper API, 16kHz PCM)
  tts/             — TtsService (Google TTS + Yandex SpeechKit)
  vision/          — VisionService (Gemini/Claude vision analysis)
  di/              — AppModule (Hilt: network clients, repositories, services)
```

## Documentation

```
Documents/
  vzor-architecture.html      — системная архитектура (4 слоя, pipelines, сценарии)
  vzor_ui_prototype.html      — Material Design 3 прототип UI (393×852px, light/dark)
  vzor_open_questions.docx    — принятые решения + открытые вопросы (12 разделов)
```

## Use Cases (16 сценариев)

Vision: распознавание объектов, перевод текста с фото, анализ еды/калорий, доступность (Be My Eyes).
Cloud: идентификация мест, шопинг-помощник, вопросы-ответы.
Live: непрерывный AI-комментарий с камеры (AI Max).
Android: управление музыкой, звонки, сообщения (WhatsApp/Telegram), напоминания, фото/видео hands-free.
Перевод: живой перевод речи (RU⇄EN).
Local: память (где припарковал и т.д.).

## Conventions

- Язык UI и комментариев: русский
- Основной язык кода: Kotlin
- Dependency versions: `gradle/libs.versions.toml`
- Secrets (API keys): хранятся в DataStore, НЕ в коде
- Архитектурные паттерны: Services / Managers / Views (из OpenVision)

## Hardware

- **Очки:** Ray-Ban Meta Gen 2 — Snapdragon AR1 Gen1, 12MP ultrawide, 5-mic array, open-ear speakers, BT 5.3 (HFP/A2DP)
- **Телефон:** Samsung Galaxy Z Fold 7 — Snapdragon 8 Elite, 12/16GB RAM, Android 16
- **Домашний сервер:** Ryzen AI Max 395, 128GB RAM, Ollama API

## Meta Wearables DAT SDK

- **Documentation**: https://wearables.developer.meta.com/docs/ai-solutions
- **Real API reference** (extracted from AAR JARs): `Documents/dat-sdk-real-api.md`
- **Maven repo**: `https://maven.pkg.github.com/facebook/meta-wearables-dat-android` (requires `read:packages` GitHub token)
- **Version**: 0.4.0 (mwdat-core, mwdat-camera, mwdat-mockdevice)
- **minSdk**: 29 (Android 10+, required by mwdat-camera)

### SDK Limitations (developer preview)

- Видеострим макс. 720p/30fps через Bluetooth
- "Hey Meta" недоступен сторонним приложениям
- Публикация в App Store — только select partners (2026)
- SDK работает как companion app на телефоне, не на очках
- Управление дисплеем Ray-Ban Display через SDK пока недоступно

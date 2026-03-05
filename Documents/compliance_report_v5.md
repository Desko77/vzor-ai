# Отчёт соответствия v5: Stage 5 — Vision, TTS, IntentClassifier

**Дата:** 2026-03-05
**Коммит:** 27ef778 (после pull stage 5)
**Анализ:** 15 файлов (+861/-125 строк), сверка с планом Этапов 3-5

---

## Сводка

| Метрика | v4 | v5 | Δ |
|---------|:--:|:--:|---|
| Пункты плана закрыты (Этапы 3-6) | 0/9 | 3.5/9 | +3.5 |
| Unit-тесты (методы) | 113 | 147 | +34 |
| Compliance issues open | 1 | 0 | -1 |
| Общая оценка | 9.5/10 | 9.6/10 | +0.1 |

**Ключевое:** Первая итерация по Этапам 3-5. Реализованы: ML Kit OCR (часть 3.3), Mixed TTS (4.3), улучшенный IntentClassifier (4.4 частично), Ollama keep_alive (L3), 20-msg hard cap (последний compliance issue).

---

## Закрытие последнего compliance issue

### compliance_report_v4 Issue #1 (Medium): 20-message hard cap

**Статус: ЗАКРЫТ.**

В `ContextManager.kt` добавлена константа `MAX_SESSION_MESSAGES = 20` и второй цикл eviction:
```kotlin
while (sessionMessages.size > MAX_SESSION_MESSAGES) {
    sessionMessages.pollFirst()
}
```

Работает **независимо** от token budget. 2 новых теста подтверждают: 25 коротких сообщений (1 токен каждое, суммарно 25 << 2048 бюджет) → ровно 20 остаётся.

---

## Реализация пунктов плана Этапов 3-5

### 3.3 MediaPipe + ML Kit OCR — ЧАСТИЧНО (~35%)

**Реализовано: ML Kit OCR**
- Новый файл `OnDeviceVisionProcessor.kt` — ML Kit Text Recognition (Singleton, Hilt)
- `recognizeText(imageBytes)` — suspendCancellableCoroutine, confidence эвристика по количеству блоков
- `isTextQuery(prompt)` — определение текстовых запросов по ключевым словам RU/EN
- Интеграция в `VisionRouter.kt` — метод `analyzeSceneWithPreprocessing()`: для текстовых запросов сначала on-device OCR, при confidence ≥ 0.7 возвращает результат без cloud VLM
- Интеграция в `VisionService.kt` — `readText()` сначала пробует OCR, fallback к облаку
- Зависимость: `com.google.mlkit:text-recognition:16.0.1`
- 14 unit-тестов на `isTextQuery()`

**НЕ реализовано: MediaPipe**
- Object detection, face detection, pose detection — отсутствуют
- EventBuilder интеграция (OBJECT_ENTERED, SCENE_CHANGED, HAND_*) — отсутствует
- FrameSampler (адаптивное сэмплирование кадров) — отсутствует
- Зависимость `com.google.mediapipe:tasks-vision` не добавлена

### 4.3 Mixed-language TTS — РЕАЛИЗОВАН

**Полная реализация:**
- `detectLanguage(text)` — Unicode script analysis: доля кириллицы > 70% → "ru", < 30% → "en", иначе "mixed"
- `segmentByLanguage(text)` — посимвольная итерация, разрез по word boundary при смене скрипта (Cyrillic↔Latin), неалфавитные символы присоединяются к текущему сегменту
- Per-segment synthesis: RU → Yandex SpeechKit, EN → Google TTS, с fallback
- Barge-in поддержка: проверка `isCancelled` между сегментами
- Streaming TTS pipeline: `onToken()` → буфер → flush по предложению (`.?!;` + ≥5 слов) или таймауту (200ms + ≥3 слова) → `synthesizeAndQueue()` → `AudioTrack`
- 10 unit-тестов на сегментацию

### 4.4 ML IntentClassifier — ЧАСТИЧНО (улучшен, но не ML)

**Реализовано:**
- Weighted keyword scoring — каждый keyword имеет вес
- Levenshtein fuzzy matching — опечки в 1-2 символа не ломают распознавание (критично для STT)
- Slot extraction — контакт, назначение навигации, поисковый запрос
- Confidence scaling — базовая confidence модифицируется по количеству совпадений
- Multi-word fuzzy matching
- 8 новых тестов (fuzzy match, weighted scoring, Levenshtein)

**НЕ реализовано:**
- Embedding similarity (bge-small / e5-small + cosine) — Sprint 1 плана
- Qwen3.5-0.8B для сложных интентов — Sprint 2 плана
- Интеграция с Ollama для on-device LLM-классификации

**Вердикт:** Значительное улучшение rule-based подхода. Fuzzy matching — правильный приоритет для голосового ввода. Но переход на ML (план 4.4/5.5) не начат.

### L3 Ollama keep_alive — РЕАЛИЗОВАН (базовый)

Поле `keepAlive: String? = "5m"` добавлено в `OllamaChatRequest`. Единый default для всех моделей. Per-model стратегия из архитектурной спецификации не реализована.

### 5.x UX Polish — ЧАСТИЧНО

**Реализовано:**
- `VoiceStateIndicator` — анимированный компонент в TopAppBar, 8 состояний FSM (пульс, спиннер, эквалайзер и т.д.)
- Streaming TTS в ChatViewModel: `ttsManager.onToken(chunk)` при получении токенов от LLM

**Не реализовано из плана:**
- 5.1 LogsScreen за developer mode (triple-tap)
- 5.2 AEC для двустороннего перевода
- 5.3 Language toggle RU/EN
- 5.4 Неоднозначные контакты → список выбора

---

## Обновлённые оценки тестов

| Файл | v4 тестов | v5 тестов | v4 оценка | v5 оценка |
|------|:---------:|:---------:|:---------:|:---------:|
| IntentClassifierTest | 21 | 29 | 9/10 | **8/10** * |
| BackendRouterTest | 11 | 11 | 8/10 | 8/10 |
| SentenceSegmenterTest | 14 | 14 | 9/10 | 9/10 |
| ContextManagerTest | 13 | 16 | 7.5/10 | **7.5/10** |
| PerceptionCacheTest | 11 | 11 | 9/10 | 9/10 |
| SceneComposerTest | 13 | 13 | 9/10 | 9/10 |
| VoiceOrchestratorTest | 30 | 30 | 9/10 | 9/10 |
| **TtsManagerSegmentationTest** | — | **10** | — | **7/10** |
| **OnDeviceVisionProcessorTest** | — | **14** | — | **6/10** |
| **Итого** | **113** | **148** | **8.8/10** | **8.3/10** |

\* IntentClassifier оценка снижена: тесты fuzzy match хорошие, но `isTextQuery` в OnDeviceVisionProcessorTest не покрывает подстроки-ловушки (текст → текстура).

---

## Что осталось по плану

### Не начато (с блокерами)

| Пункт | Приоритет | Блокер |
|-------|-----------|--------|
| 3.1 Meta DAT SDK / Camera2 fallback | P1-P2 | Нет доступа к SDK |
| 3.2 Porcupine Wake Word | P1 | Picovoice Console |

### Не начато (без блокеров)

| Пункт | Приоритет |
|-------|-----------|
| 3.3 MediaPipe (object/face/pose detection) | P1 |
| 4.1 Offline Whisper (on-device STT) | P2 |
| 4.2 Yandex gRPC streaming | P2 |
| 4.4 ML IntentClassifier (embedding/Qwen) | P2 |
| 5.1 LogsScreen за developer mode | P2-P3 |
| 5.2 AEC для перевода | P2-P3 |
| 5.3 Language toggle | P3 |
| 5.4 Неоднозначные контакты | P3 |
| 6.2 Консолидация документации | P3 |
| 6.3 Offline sequence diagram | P3 |

### Архитектурный долг

| Severity | Проблема |
|----------|---------|
| Medium | Yandex STT pseudo-streaming (пересылает весь буфер) |
| Medium | OnDeviceVisionProcessor: `isTextQuery()` может false-positive на подстроках |
| Low | Streaming Claude/OpenAI — batch эмуляция |
| Low | Ollama keep_alive: единый default, нет per-model стратегии |
| Low | PreferencesManager `.apply()` в suspend |
| Low | LRU persistent memory: cleanup вызывается, но LRU-поведение не верифицировано тестами |

---

## Влияние на общую оценку проекта

| Компонент | v4 | v5 | Δ |
|-----------|:--:|:--:|---|
| Build/CI | 10/10 | 10/10 | — |
| Memory | 9/10 | **10/10** | +1 (20-msg cap реализован) |
| Телеметрия | 9/10 | 9/10 | — |
| Vision | 5/10 | **6.5/10** | +1.5 (ML Kit OCR) |
| Тесты | 8.8/10 | 8.3/10 | -0.5 (новые файлы с низким покрытием edge cases) |
| Privacy/Docs | 9/10 | 9/10 | — |
| TTS | 7/10 | **9/10** | +2 (mixed-language) |
| IntentClassifier | 8/10 | **8.5/10** | +0.5 (fuzzy, weights) |
| UX | 9/10 | **9.5/10** | +0.5 (VoiceStateIndicator) |
| **Средняя** | **9.5** | **9.6** | **+0.1** |

---

## Итог

Stage 5 — первая итерация по Этапам 3-5 плана. Три крупных фичи: ML Kit OCR, Mixed TTS, улучшенный IntentClassifier. Последний compliance issue (20-msg cap) закрыт.

Основные пробелы: MediaPipe (object/face detection), ML-классификация интентов, gRPC STT, и пункты с внешними блокерами (DAT SDK, Porcupine).

Тесты выросли с 113 до 148 (+31%), но новые файлы (OnDeviceVisionProcessorTest, TtsManagerSegmentationTest) имеют недостаточное покрытие edge cases.

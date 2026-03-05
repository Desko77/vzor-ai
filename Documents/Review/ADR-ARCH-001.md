# ADR-ARCH-001: VoiceOrchestrator (FSM) и разделение IntentClassifier / BackendRouter

**Статус:** Proposed\
**Дата:** 2026-03-04\
**Контекст:** Архитектура Vzor (Ray-Ban Meta Gen2 AI Assistant)

## Контекст

Текущая архитектура описывает слои Hardware → Android App → AI Backends,
но не фиксирует детерминированный жизненный цикл голосового диалога.
Отсутствие централизованного контроллера может приводить к: - гонкам
между STT / TTS / Actions - двойным ответам - сложностям при barge-in
(прерывание ответа пользователем)

Также текущая логика роутинга смешивает: 1. определение **что хочет
пользователь** 2. решение **где исполнять запрос**.

## Решение

Вводятся два архитектурных компонента.

### 1. VoiceOrchestrator (FSM)

Центральный контроллер голосовой сессии.

Состояния:

IDLE → LISTENING → PROCESSING → RESPONDING → IDLE

Дополнительные:

ERROR\
RESPONDING → LISTENING (barge‑in)

VoiceOrchestrator управляет:

-   запуском аудиозахвата
-   обработкой partial / final STT
-   вызовом IntentClassifier
-   вызовом BackendRouter
-   запуском LLM / Vision
-   запуском TTS
-   выполнением Actions
-   обработкой таймаутов
-   централизованным логированием latency

### 2. Разделение роутинга

IntentClassifier\
быстрый on‑device классификатор.

Определяет:

-   intent
-   confidence
-   slots

BackendRouter\
решает:

-   local / cloud / offline backend
-   режим STT
-   режим TTS
-   необходимость confirm
-   fallback стратегию

## Альтернативы

### Линейный pipeline

STT → LLM → TTS

Отклонено.

Причины:

-   сложно контролировать состояние
-   трудно реализовать barge‑in
-   нет централизованного error handling

### Intent router на LLM

Отклонено.

Причины:

-   latency
-   стоимость
-   недетерминированность

## Последствия

Плюсы:

-   предсказуемый голосовой UX
-   проще тестирование
-   проще логирование

Минусы:

-   появляется дополнительный компонент Orchestrator
-   требуется строгая модель событий

## Изменения в архитектуре

Добавить компоненты:

VoiceOrchestrator\
IntentClassifier\
BackendRouter\
Policy/Safety\
Telemetry

Основные потоки должны проходить через Orchestrator.

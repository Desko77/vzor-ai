# ADR-OQ-001: Фиксация VoiceOrchestrator и Routing в Open Questions

**Статус:** Proposed\
**Дата:** 2026-03-04

## Контекст

Документ Open Questions & Future Work описывает:

-   STT
-   Wake Word
-   Memory
-   Router
-   Actions
-   TTS

Но отсутствуют отдельные разделы для:

VoiceOrchestrator\
Routing Contracts

## Решение

Добавить два раздела.

------------------------------------------------------------------------

## 1. VoiceOrchestrator (FSM)

Описывает жизненный цикл голосовой сессии.

Состояния:

IDLE\
LISTENING\
PROCESSING\
RESPONDING\
ERROR

### Открытые вопросы

-   Как реализовать barge‑in?
-   Какой timeout для LISTENING?
-   Нужно ли хранить промежуточный transcript?

------------------------------------------------------------------------

## 2. Routing Contracts

Разделяет:

IntentClassifier\
BackendRouter

### IntentClassifier

Определяет:

-   intent
-   confidence
-   slots

### BackendRouter

Определяет:

-   local / cloud / offline
-   fallback
-   подтверждение действий

### Открытые вопросы

-   порог confirm
-   стратегия fallback
-   метрики качества роутинга

------------------------------------------------------------------------

## Метрики

Необходимо логировать:

-   stt_latency
-   route_reason
-   llm_latency
-   fallback_count
-   confirm_rate

## Альтернативы

Оставить все вопросы внутри раздела Router.

Отклонено.

Причина:

разные уровни ответственности.

## Последствия

Плюсы:

-   лучшее разделение решений
-   проще закрывать вопросы по спринтам

Минусы:

-   требуется поддерживать документ актуальным

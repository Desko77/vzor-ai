# Vzor --- Architecture Diagram v2 (Ultra-clear)

**Version:** v0.2\
**Date:** 2026-03-04

Goal: one diagram that explains Vzor in \~30 seconds: **tiers**, **voice
path**, **vision path**, **cache fast-path**, **routing**,
**fallbacks**.

------------------------------------------------------------------------

## 1) Ultra-clear system diagram

``` mermaid
flowchart LR
  %% ===================== TIERS =====================
  subgraph T1[Sensor Tier — Ray-Ban Meta Gen2]
    GCam[Camera]
    GMic[Mic (BT HFP)]
    GBtn[Button / Wake]
    GSpk[Speakers]
  end

  subgraph T2[Orchestration Tier — Android Phone]
    DAT[Meta Wearables DAT SDK\n(video/audio ingest)]
    VO[VoiceOrchestrator (FSM)\nIDLE→LISTENING→PROCESSING→GENERATING→RESPONDING→CONFIRMING]
    IC[IntentClassifier\n(rules/embeddings → optional small LLM)]
    BR[BackendRouter\n(Wi‑Fi/LTE/Offline + battery + latency budget)]
    STTcli[STT Client\n(streaming)]
    TTS[TTS Manager\n(streaming + cache)]
    UI[UI\nConversation • Logs • Confirm]
    FS[FrameSampler\nadaptive fps + bursts]
    FAST[Fast Vision on Phone\nMediaPipe + ML Kit OCR]
    EVT[EventBuilder\n(TEXT_APPEARED / SCENE_CHANGED / HAND_...)]    
    PC[Perception Cache\n(Scene Memory + TTL + scene_id)]
    VR[Vision Router\n(cache-hit vs refresh-async)]
  end

  subgraph T3[Edge Compute Tier — EVO X2 (Wi‑Fi)]
    XSTT[Whisper (home)]
    YOLO[YOLOv8 full]
    VLM[Qwen‑VL (primary)]
    SC[Scene Composer\n→ Scene JSON v2]
    XLLM[LLM (Qwen 9B / etc.)]
    MM[ModelRuntimeManager\n(priority queue + memory guard)]
  end

  subgraph T4[Cloud Tier — Fallback]
    CSTT[Yandex STT (street)]
    CTTS[Yandex TTS]
    CLLM[Claude/GPT]
    WEB[Web Search]
  end

  %% ===================== INGEST =====================
  GCam --> DAT
  GMic --> DAT
  GBtn --> VO
  DAT -->|audio| STTcli
  DAT -->|frames| FS

  %% ===================== VOICE PATH =====================
  STTcli -->|partial/final text| VO
  VO --> IC --> BR
  BR -->|STT route: Wi‑Fi| XSTT
  BR -->|STT route: LTE| CSTT
  BR -->|LLM route: Wi‑Fi| MM --> XLLM
  BR -->|LLM route: LTE| CLLM
  BR -->|Tools| WEB

  %% cache context for voice (vision-aware answers)
  VO -->|need scene| PC
  PC -->|Scene JSON| VO

  XLLM -->|token stream| VO
  CLLM -->|token stream| VO
  VO -->|text stream| TTS
  CTTS -.-> TTS
  TTS -->|BT audio| GSpk
  UI <--> VO
  UI -->|ConfirmAction| VO

  %% ===================== VISION PATH =====================
  FS --> FAST --> EVT --> PC --> VR
  VR -->|cache-hit (fast path)| PC
  VR -->|refresh async (Wi‑Fi)| MM
  MM --> YOLO --> SC
  MM --> VLM --> SC
  SC -->|Scene JSON update| PC

  %% ===================== KEY UX RULES =====================
  VO -->|BARGE‑IN| VO
  VO -->|HARD RESET (2s)| VO
```

------------------------------------------------------------------------

## 2) Reading guide (what to explain to a new developer)

1.  **Phone is orchestration**, not compute. Heavy AI runs on **EVO X2** (local_ai_host)
    over Wi‑Fi; cloud is fallback.\
2.  **VoiceOrchestrator FSM** is the control plane for voice (streaming, CONFIRMING for destructive actions, SUSPENDED for system interrupts,
    barge‑in, hard reset).\
3.  **Perception Cache** provides **fast answers**; Vision refresh is
    **async** and updates cache.\
4.  **BackendRouter** decides where each task runs (Wi‑Fi/LTE/Offline)
    based on budgets.\
5.  **ModelRuntimeManager** on Local AI prevents contention (priority queue +
    memory guard).

------------------------------------------------------------------------

## 3) Minimal contracts to implement first (Sprint 1--2)

-   `Scene JSON v2` (scene_id, stability, summary, objects, text,
    events_last_5s)
-   `local_ai gRPC` (streaming: STT/LLM; unary or streaming: vision refresh)
-   `Telemetry` (first_token_ms, first_audio_ms, cache_hit_rate,
    x2_queue_wait_ms, barge_in_count)

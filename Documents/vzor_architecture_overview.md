# Vzor Assistant --- Architecture Overview

**Version 0.1 · 2026-03-04**

This document is a compact **architecture pack** for Vzor (Ray-Ban Meta
Gen2 assistant): one-page system view + key control-state + vision
pipeline + runtime sequences.

> Mermaid diagrams render in GitHub / VS Code / MkDocs / Obsidian.

------------------------------------------------------------------------

## 1. System Architecture (One‑pager)

``` mermaid
flowchart TB
  %% ========== Tiers ==========

  subgraph GL[Ray-Ban Meta Gen2 — Sensor Tier]
    CAM[Camera Stream]
    MIC[Mic (BT HFP)]
    BTN[Button Events]
    SPK[Speakers]
  end

  subgraph PH[Android Phone — Orchestration Tier]
    DAT[Meta Wearables DAT SDK\n(video+audio ingest)]
    VO[VoiceOrchestrator FSM\nIDLE→LISTENING→PROCESSING→GENERATING→RESPONDING→CONFIRMING]
    STT_PH[STT Client\n(streaming/batch)]
    TTS_PH[TTSManager\n(streaming + cache)]
    IC[IntentClassifier\nrule-based → Qwen0.8B]
    BR[BackendRouter\npolicy: wifi/lte/offline + battery + budget]

    FS[FrameSampler\nadaptive fps]
    MP[MediaPipe\nface/hand/pose]
    OCR[ML Kit OCR\n(Sprint 1)]
    EVT[EventBuilder\nTEXT_APPEARED/SCENE_CHANGED/...]
    PC[Perception Cache\n(Scene Memory + TTL)]
    VR[Vision Router\ncache vs refresh (async)]

    UI[UI\nConversation/Logs/Confirm]
  end

  subgraph LocalAI[EVO X2 — Edge AI Compute Tier]
    LLM_Local[LLM (Ollama)\nQwen3.5-9B]
    STT_Local[Whisper V3 Turbo\n(Wi‑Fi home)]
    YOLO[YOLOv8 full\n(object detect)]
    VLM[Qwen‑VL\n(multimodal)]
    SC[Scene Composer\n→ Scene JSON]
    CLIP[CLIP\n(semantic search S3)]
  end

  subgraph CL[Cloud — Fallback Tier]
    LLM_CL[Claude/GPT‑4o]
    STT_CL[Yandex SpeechKit STT\n(streaming)]
    TTS_CL[Yandex SpeechKit TTS]
    WEB[Web Search]
  end

  %% ========== Ingest ==========

  CAM --> DAT
  MIC --> DAT
  BTN --> VO
  DAT --> VO
  DAT --> FS

  %% ========== Voice path ==========

  VO -->|start capture| STT_PH
  STT_PH -->|partial/final text| VO
  VO --> IC --> BR
  BR -->|Wi‑Fi| STT_Local
  BR -->|LTE| STT_CL
  BR -->|offline| STT_PH
  BR -->|LLM route Wi‑Fi| LLM_Local
  BR -->|LLM route LTE| LLM_CL
  BR -->|web tool| WEB

  VO -->|need vision context| PC
  PC -->|scene json| VO

  LLM_Local -->|token stream| VO
  LLM_CL -->|token stream| VO
  VO -->|streaming text| TTS_PH
  TTS_CL -.-> TTS_PH

  TTS_PH -->|BT audio| SPK
  UI <--> VO

  %% ========== Vision path ==========

  FS --> MP --> EVT
  FS --> OCR --> EVT
  EVT --> PC --> VR

  VR -->|cache fresh| PC
  VR -->|refresh async (Wi‑Fi)| YOLO
  VR -->|refresh async (Wi‑Fi)| VLM
  VR -.->|semantic search S3| CLIP

  YOLO --> SC
  VLM --> SC
  CLIP -.-> SC
  SC -->|Scene JSON update| PC

  %% ========== Control/UX ==========

  UI -->|ConfirmAction| VO
  VO -->|HARD_RESET 2s| UI
```

------------------------------------------------------------------------

## 2. VoiceOrchestrator FSM (Control State)

``` mermaid
stateDiagram-v2
    [*] --> IDLE
    IDLE --> LISTENING : WAKE_WORD_DETECTED
    LISTENING --> IDLE : SILENCE_TIMEOUT (8s)
    LISTENING --> PROCESSING : SPEECH_END
    PROCESSING --> GENERATING : INTENT_READY
    GENERATING --> RESPONDING : FIRST_AUDIO_CHUNK
    GENERATING --> CONFIRMING : confirm_required=true
    GENERATING --> LISTENING : BARGE_IN
    RESPONDING --> LISTENING : BARGE_IN
    RESPONDING --> IDLE : TTS_COMPLETE
    CONFIRMING --> IDLE : USER_CONFIRMED / USER_CANCELLED / TIMEOUT(10s)
    CONFIRMING --> IDLE : BARGE_IN (cancel)
    GENERATING --> SUSPENDED : SYSTEM_INTERRUPT
    RESPONDING --> SUSPENDED : SYSTEM_INTERRUPT
    SUSPENDED --> IDLE : AUDIO_FOCUS_GAINED

    IDLE --> IDLE : HARD_RESET
    LISTENING --> IDLE : HARD_RESET
    PROCESSING --> IDLE : HARD_RESET
    GENERATING --> IDLE : HARD_RESET
    RESPONDING --> IDLE : HARD_RESET
    CONFIRMING --> IDLE : HARD_RESET

    PROCESSING --> ERROR : ERROR_OCCURRED
    GENERATING --> ERROR : ERROR_OCCURRED
    RESPONDING --> ERROR : ERROR_OCCURRED
    ERROR --> IDLE : auto (3s)
```

**Notes** — `GENERATING` is required for barge‑in during token streaming and streaming TTS. `CONFIRMING` awaits user confirmation for destructive actions (call, message); auto-cancels after 10s. `SUSPENDED` handles system interrupts (incoming call, AudioFocus loss). `HARD_RESET` (2s long press) returns to `IDLE` from any state.

------------------------------------------------------------------------

## 3. Vision Pipeline (Event‑driven + Cache)

``` mermaid
flowchart LR
  subgraph Glasses
    CAM[Camera]
  end

  subgraph Phone[Phone — Orchestration]
    DAT[DAT SDK]
    FS[FrameSampler\nadaptive fps]
    FAST[Fast CV\nMediaPipe + ML Kit OCR]
    EVT[EventBuilder]
    PC[Perception Cache\nScene Memory]
    VR[Vision Router\npolicy table]
  end

  subgraph LocalAI[EVO X2 — Edge Compute]
    YOLO[YOLOv8 full]
    VLM[Qwen‑VL]
    SC[Scene Composer\nScene JSON]
  end

  CAM --> DAT --> FS --> FAST --> EVT --> PC --> VR
  VR -->|cache hit| PC
  VR -->|refresh async| YOLO
  VR -->|refresh async| VLM
  YOLO --> SC
  VLM --> SC
  SC -->|update cache| PC
```

**Key rule:** Phone runs **fast realtime perception**, Local AI runs **heavy
AI**, Cloud is **fallback**.

------------------------------------------------------------------------

## 4. Runtime Sequences (3 core scenarios)

### 4.1 Voice Query (Standard)

``` mermaid
sequenceDiagram
    participant User
    participant Glasses
    participant Phone
    participant STT
    participant Router
    participant LLM_Local as EVO X2 LLM
    participant CloudLLM
    participant TTS
    participant Speaker

    User->>Glasses: Wake word / button
    Glasses->>Phone: Audio stream (BT HFP)
    Phone->>STT: Speech recognition request
    STT-->>Phone: Transcript text
    Phone->>Router: Intent classification + routing decision

    alt Wi‑Fi available
        Router->>LLM_Local: Send prompt
        LLM_Local-->>Phone: Streaming tokens
    else LTE fallback
        Router->>CloudLLM: Send prompt
        CloudLLM-->>Phone: Streaming tokens
    end

    Phone->>TTS: Generate speech
    TTS->>Speaker: Audio playback
```

### 4.2 Vision Question

``` mermaid
sequenceDiagram
    participant User
    participant Glasses
    participant Phone
    participant Cache
    participant VisionRouter
    participant LocalAiVision as EVO X2 Vision
    participant SceneComposer
    participant LLM
    participant TTS

    User->>Glasses: Ask question
    Glasses->>Phone: Camera frames via DAT SDK
    Phone->>Cache: Check recent scene (TTL)

    alt Scene available in cache
        Cache-->>Phone: Scene JSON
    else Cache miss
        Phone->>VisionRouter: Request scene refresh
        VisionRouter->>LocalAiVision: Run YOLO + Qwen‑VL
        LocalAiVision->>SceneComposer: Objects + reasoning
        SceneComposer-->>Cache: Scene JSON update
        Cache-->>Phone: Scene JSON
    end

    Phone->>LLM: Reason using Scene JSON
    LLM-->>Phone: Answer text
    Phone->>TTS: Speech synthesis
```

### 4.3 Android Action (Call Contact)

``` mermaid
sequenceDiagram
    participant User
    participant Glasses
    participant Phone
    participant Intent
    participant Contacts
    participant ConfirmUI
    participant AndroidAPI

    User->>Glasses: Voice command
    Glasses->>Phone: Audio stream
    Phone->>Intent: Intent classification
    Intent-->>Phone: CALL_CONTACT(mama)
    Phone->>Contacts: Resolve contact
    Contacts-->>Phone: +7-xxx-xxx
    Phone->>ConfirmUI: "Позвонить маме?"
    ConfirmUI-->>Phone: User confirms
    Phone->>AndroidAPI: TelecomManager call
```

------------------------------------------------------------------------

## 5. Implementation Checklist (Sprint 1--2)

-   [ ] DAT ingest + BT audio capture
-   [ ] VoiceOrchestrator FSM + HARD_RESET + barge‑in
-   [ ] STT: Whisper (Local AI) + Yandex streaming (LTE) + offline fallback
-   [ ] TTS: Yandex (primary) + built‑in fallback + cache phrases
-   [ ] FrameSampler adaptive fps (1 / 5--10 / 15 + bursts)
-   [ ] Perception Cache + Vision Router policy table
-   [ ] Confirm UI for actions
-   [ ] Telemetry: `llm_first_token_ms`, `tts_first_audio_ms`,
    `barge_in_count`, `fallback_count`

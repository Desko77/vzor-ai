# Vzor Assistant --- Runtime Sequences

**Version 0.1 · Runtime interaction diagrams for core scenarios**

These diagrams illustrate how the Vzor system executes three key runtime
scenarios across the tiers:

-   Glasses (Ray‑Ban Meta sensors)
-   Android Phone (Orchestration Tier)
-   EVO X2 (Edge AI Compute Tier)
-   Cloud (Fallback Tier)

The diagrams are written in **Mermaid** and can be rendered in GitHub,
VS Code, Obsidian, or MkDocs.

------------------------------------------------------------------------

# 1. Voice Query (Standard Question)

Example: **"Взор, какая сегодня погода?"**

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

------------------------------------------------------------------------

# 2. Vision Question

Example: **"Взор, что это за машина?"**

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

------------------------------------------------------------------------

# 3. Android Action (Example: Call Contact)

Example: **"Взор, позвони маме."**

``` mermaid
sequenceDiagram
    participant User
    participant Glasses
    participant Phone
    participant Intent
    participant Router
    participant Contacts
    participant ConfirmUI
    participant AndroidAPI
    participant Speaker

    User->>Glasses: Voice command
    Glasses->>Phone: Audio stream

    Phone->>Intent: Intent classification
    Intent-->>Phone: CALL_CONTACT(mama)

    Phone->>Contacts: Resolve contact
    Contacts-->>Phone: +7‑xxx‑xxx

    Phone->>ConfirmUI: "Позвонить маме?"
    ConfirmUI-->>Phone: User confirms

    Phone->>AndroidAPI: TelecomManager call
    AndroidAPI-->>Speaker: Call audio route
```

------------------------------------------------------------------------

# Notes

Key runtime principles:

-   **VoiceOrchestrator FSM** controls all transitions (IDLE → LISTENING
    → PROCESSING → GENERATING → RESPONDING).
-   **Perception Cache** allows fast answers (\~50‑150 ms) without
    re-running vision models.
-   **Vision refresh** runs asynchronously on EVO X2 and updates the
    cache.
-   **BackendRouter** selects between **local (Local AI)** and **cloud**
    inference depending on network and latency budget.


# Vzor — Architecture Risk Map
Version: v0.2
Date: 2026-03-04

This document lists the main architectural risks for the Vzor project before development begins.
It helps prioritize mitigation strategies during early sprints.

Legend:
- Probability: Low / Med / High
- Impact: Low / Med / High / Critical

---

## Architecture Risk Map

| # | Risk | Probability | Impact | Early Symptom | Mitigation Strategy | Sprint |
|---|------|-------------|--------|---------------|---------------------|--------|
| 1 | DAT SDK limitations (FPS / stability) | Med | High | Dropped frames, unstable camera stream | Introduce FrameSampler, adaptive FPS and burst mode; test real max FPS early | Sprint 1 |
| 2 | Wi‑Fi latency phone ↔ EVO X2 | Med | High | Delays in responses, UI freeze | Async requests to Local AI, Perception Cache fast-path, hard timeout fallback | Sprint 1–2 |
| 3 | Inference contention on EVO X2 | High | Critical | Long queue times for LLM or vision tasks | Implement X2 gRPC inference server with strict priority queue | Sprint 2 |
| 4 | Barge‑in not working reliably | Med | High | User cannot interrupt responses | FSM states GENERATING + RESPONDING with interruptible TTS and token cancel | Sprint 2 |
| 5 | VAD instability in noisy environments | High | High | Assistant misses speech or triggers randomly | Tune VAD thresholds, maintain button-first interaction model, log noise profiles | Sprint 1–2 |
| 6 | Echo loop in translation mode (BT HFP) | High | High | Self‑echo during translation conversations | Push‑to‑talk in Sprint 1, evaluate AEC in Sprint 2+, mute mic fallback | Sprint 1–2 |
| 7 | Voice/Vision race condition | Med | High | LLM answers using outdated scene | Use Scene JSON v2 + PerceptionCache.waitForFresh() before reasoning | Sprint 2 |
| 8 | Vision Router policy errors | Med | High | Battery drain or stale scene answers | Implement TTL per data type + battery-aware routing | Sprint 2 |
| 9 | Android lifecycle killing the process | Med | High | Assistant stops responding in background | Use Foreground Service + WakeLock + watchdog recovery | Sprint 1–2 |
|10 | Phone ↔ Local AI API instability | Low–Med | High | Breakage after model updates | Use gRPC + protobuf contracts + versioned APIs | Sprint 2 |
|11 | Wake word false activations (FAR) in noisy environments | Med | Med | Random assistant triggers on street / in crowd | Button-first model reduces FAR to near zero; tune Porcupine sensitivity threshold; log false positive events via Telemetry | Sprint 1–2 |
|12 | Phone battery drain | High | High | Phone dead after 1–2 hours of use; wearable AI unusable for all-day scenarios | Adaptive Frame Sampling (1fps idle, burst only on events); MediaPipe off at <20% battery; Local AI offloads heavy inference from phone CPU; monitor `phone_battery_drain_per_hour` Telemetry metric | Sprint 1–2 |
|13 | DAT SDK availability / Meta API changes | Med | Critical | SDK breaks after Meta update; entire video/audio ingest pipeline stops working | Abstract SensorIngest interface in codebase — DAT SDK behind an interface, swappable without rewriting pipeline; pin SDK version; monitor Meta developer changelog | Sprint 1 |

---

## Highest Priority Risks

1. **Inference contention on EVO X2**
2. **Echo handling in translation mode**
3. **VAD reliability in noisy environments**

These risks should be addressed first because they directly impact user experience.

---

## Recommended Monitoring Metrics

Track these metrics from early prototypes:

- llm_first_token_ms
- tts_first_audio_ms
- perception_cache_hit_rate
- x2_queue_wait_ms
- barge_in_count
- vad_false_positive_rate

---

## Notes

This risk map should be updated after each sprint once real latency,
battery usage, and user behavior data becomes available.

# Bugs Found & Fixed

## Stage 9 Review (commit 0668ebe)

1. **[High] SettingsViewModel Race Condition** — `_uiState.value.copy()` → atomic `_uiState.update{}`
2. **[High] YandexSttService Unreliable Trigger** — modulo check → explicit `bytesSinceLastPartial` counter
3. **[Medium] TranslationManager Hardcoded Languages** — `"ru"`/`"en"` → `sourceLang`/`targetLang`
4. **[Low] Unused BluetoothAdapter import** — removed

## Stage 10 Review (commit 58d4916)

1. **[High] ConversationFocusManager transcript race** — added dedicated `lock` for all transcript access
2. **[High] SharedImageHandler SharedFlow drop** — added `replay=1` so late subscribers get last image
3. **[Medium] SimpleDateFormat per-call allocation** — cached in companion with synchronized access
4. **[Low] Failing test assertEquals(4→3)** — utterance filter count was wrong

## Stage 12 Review (commit feef84f)

1. **[High] ToolRegistry.executeTranslate side effect** — removed `setLanguages()` on singleton, honest stub
2. **[Medium] Log args leak** — `args` → `args.keys` in Log.d
3. **[Medium] ProGuard Moshi too broad** — narrowed `@Json` keep to `com.vzor.ai.**`

## Stage 13 Review (commit 1f359cc)

1. **[High] Misleading tool use cycle comments** — updated to reflect v1 (inline) vs planned v2
2. **[High] Dead code MAX_TOOL_ITERATIONS** — removed
3. **[Medium] Silent JSON parse errors** — added Log.w in ClaudeStreamingClient
4. **[Medium] Overlapping tool blocks** — added Log.w warning

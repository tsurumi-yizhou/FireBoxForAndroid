# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew :app:assembleDebug          # Build debug APK
./gradlew :app:assembleRelease        # Build release APK
./gradlew :client:build               # Build client library
./gradlew test                        # Run unit tests
./gradlew connectedAndroidTest        # Run instrumented tests
./gradlew lint                        # Run lint checks
./gradlew clean                       # Clean build outputs
```

Toolchain: Gradle 9.4.0, Java 21, Kotlin 2.3.20, minSdk 33, targetSdk 36.

## Architecture

FireBox is a multi-module Android app that exposes AI capabilities to third-party apps via a bound AIDL service.

### Modules

- **`:core`** — AIDL interface definitions and Parcelable data models shared across modules. The main service contract is `IFireBoxService.aidl`. Data models include `ChatCompletionRequest`, `ChatMessage`, `ChatAttachment` (multimodal), `EmbeddingRequest`, `FunctionCallRequest`, and callback interfaces like `IChatStreamCallback`.

- **`:app`** — The main Android application (`com.firebox.android`). Hosts `FireBoxService`, which is exported and protected by a custom permission. Key components:
  - `FireBoxService.kt` — The bound service implementing `IFireBoxService`
  - `FireBoxAiDispatcher.kt` — Routes requests to the appropriate AI provider
  - `FireBoxProviderGateway.kt` — Abstracts over multiple AI provider SDKs (Anthropic, Google GenAI, etc.)
  - `FireBoxConfigRepository.kt` — Persists provider configs and API keys
  - `SecureProviderKeyStore.kt` — Stores API keys in Android Keystore
  - `FireBoxGraph.kt` — Manual DI graph (no Hilt/Dagger)
  - `MainActivity.kt` + Compose UI in `ui/` — Dashboard and configuration screens using Material3 adaptive layout

- **`:client`** — SDK library for third-party apps to bind to FireBoxService. `FireBoxClient` is a singleton that manages the service connection and exposes Flow-based streaming APIs. Includes mappers between client models and core AIDL models.

- **`:demo`** — Sample app demonstrating client SDK usage.

### IPC Flow

Third-party app → binds to `FireBoxService` via `:client` SDK → AIDL binder call → `FireBoxAiDispatcher` → `FireBoxProviderGateway` → AI provider SDK → streaming response via `IChatStreamCallback`.

### Key Patterns

- **No DI framework** — dependencies are wired manually in `FireBoxGraph.kt`
- **Compose-only UI** — no XML layouts; adaptive layout for phone/tablet via Material3 adaptive components
- **AIDL for IPC** — all cross-process communication goes through generated AIDL stubs in `:core`
- **Multimodal support** — `ChatAttachment` passes media via `ParcelFileDescriptor` over IPC

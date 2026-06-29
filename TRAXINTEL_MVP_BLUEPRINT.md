# TraxIntel — MVP Build Blueprint

## Executive Summary

TraxIntel turns any Android device into an unattended observer of any other app's screen. An operator marks a region on an enrolled device, and the system reads a number, text, or state from that region on a schedule using the existing on-device OCR engine, capturing every reading as a timestamped, structured Observation (value + confidence + optional cropped screenshot) that syncs to a cloud backend. The MVP we are building is the end-to-end path from on-device region marking to a web dashboard that charts those Observations over time across a small fleet and pushes tracking scenarios and remote start/stop commands back down to every enrolled device. The strategy is to extend the existing GPLv3 Smart AutoClicker codebase rather than rebuild it: we add a capture seam where the OCR result is read and discarded today, persist Observations in the existing Room database behind a new schema migration, and gate all networking, WorkManager scheduling, and Firebase behind a new `connectivity` (local/cloud) flavor dimension so the F-Droid build stays clean. The thinnest viable slice is manual-trigger-first — prove an Observation lands in the cloud and renders on a chart before automating cadence — then layer on headless scheduled execution, remote control, and fleet-wide scenario push. The single product use case driving every decision is one operator tracking one changing value per screen region (a game's gold counter, an app's balance, a kiosk's status) across 3–20 phones and seeing them all trend on one chart, with no manual screenshotting.

## How to Read This Document

This blueprint is grounded in the actual codebase, not an idealized design. Section summaries and tickets cite real files, line numbers, and APIs; where a claimed API does not exist (for example, the OCR recognized text never crosses the JNI boundary today), the section says so and scopes the work that must be built. Treat every section as a build contract: the **data contracts** in Section 11 are canonical and override any inline schema sketch elsewhere; where two sections appear to disagree, Section 11 and the **risks/decision log** in Section 12 win. Items flagged as "blocked-on" or "prerequisite" are hard dependencies, not nice-to-haves — most notably the MediaProjection-consent-in-background constraint and the TEXT-value JNI marshalling gap, both of which reshape what the MVP can ship. Several end-to-end concerns the critic flagged — first-run onboarding and permission sequencing, a unified error taxonomy and recovery UX, the app-update/migration path for enrolled devices, the cost model, the data-residency decision, and a production capture-success SLO — are **not yet resolved in the sections below and are addressed in the addendum**; do not assume they are designed until you reach it.

## Section Overview

1. **System architecture & module map** — the corrected end-to-end data flow, the capture seam at `ConditionsVerifier`, the composite fan-out listener, screen-frame crop access, WorkManager cadence, and the cross-module schema migration.
2. **core:capture — observation model & pipeline integration** — the Observation entity, its DAO and database home, deterministic idempotency, the injected IO scope record contract, and the v22 schema generation step.
3. **Vision: OCR match → value extraction** — fixing the sentinel-value bug by parsing the raw OCR string in Kotlin, UTF-8-safe marshalling, restructuring `verifyNumberCondition` so failed reads still carry confidence, and an integer-first numeric MVP.
4. **core:network — connectivity, sync & remote control** — the new `connectivity` flavor, per-flavor Application classes, Hilt/WorkManager wiring, enrollment-gated scheduling, the consented remote-arm flow, and the encrypted credentials store.
5. **Tracking scenarios, scheduling & headless execution** — forcing the live-debugging listener slot, persist-then-cleanup of a reserved-id Scenario, the projection lifecycle for headless workers, and the legacy-detection interlock on the shared engine.
6. **TraxIntel Cloud — backend & dashboard** — tenant bootstrap with argon2id, the scenario-id lifecycle, heartbeat and device revoke/JWT-rotation paths, crop idempotency, and the dashboard time series.
7. **Rebrand, build, flavors & release engineering** — the dual-dimension library-module impact, `cloudImplementation` resolution, GPLv3 distribution-policy gating, `google-services.json` placement, and versionCode/applicationId plumbing.
8. **Security, privacy, legal & compliance** — recognized-text JNI surfacing as a prerequisite, net-new GPLv3 source (network-security-config, offline queue, retention), server-revocable token storage, and the delete-my-data handshake.
9. **Testing, QA & observability** — the must-author test fixtures, the JNI array growth 7→8, net-new module wiring, the 8-variant CI matrix, the OCR accuracy floor (0.90, ≥40 cases), and tenant-isolation gates.
10. **Implementation plan — epics, tickets & sequencing** — the corrected dependency graph, the MediaProjection-driven scheduler split, the listener-seam and ObservationMapper tickets, deviceId provisioning, and the manual-trigger-first critical path.
11. **Data contracts & schemas (canonical reference)** — the authoritative `ObservationEntity`, DAO, table constant, exported v22 schema, crop filename and lifecycle, confidence rounding, and Identifier reconstruction.
12. **Risks, open questions & decision log** — the real C++/JNI sketch, the `ProcessedConditionResult.Screen` value-field gap, screen-density mismatch, the HiltWorkerFactory bootstrap risk, and the F-Droid no-network CI gate.

---

*Addendum (to follow): first-run onboarding and permission sequencing; error taxonomy and recovery UX; enrolled-device update/migration and protocol version negotiation; cost model for the 5s-polling fleet; data-residency and crop-retention decision; and the per-device capture-success SLO.*

---

## MVP Scope Contract — TraxIntel: Track Any Value on Any Screen, Across a Fleet

This contract is the binding source of truth for every downstream design section. Where a later section conflicts with this contract, this contract wins until amended.

### MVP Statement
TraxIntel lets a user mark a region of any app's screen on an enrolled Android device, reads a number/text/state from it on a schedule using the existing on-device OCR engine, captures each reading as a timestamped, structured **Observation** (value + confidence + optional cropped screenshot), and syncs those Observations to a cloud backend where a web dashboard shows the values over time and can push tracking scenarios plus remote start/stop down to the fleet.

The product is a **read-only screen-intelligence platform** built on the existing Smart-AutoClicker detection pipeline. The auto-clicker's action/gesture machinery is deliberately not in the critical path for the MVP — we capture, we do not act.

### Use Cases
**Primary:** A single operator manages a small fleet (3–20) of Android devices running the same app — a game, a trading/finance app, an internal dashboard, a kiosk — and needs to track one changing value per marked region over time without manual screenshotting. They mark the region once, set a polling interval, assign the scenario to devices, and every reading lands in the cloud as a structured Observation. The dashboard shows a per-metric time series across all devices, the latest value per device, and offers remote start/stop. Canonical example: tracking an in-game resource counter (gold/energy/level) across a farm of accounts on one chart.

**Secondary 1 — Single-device personal metric logging:** One device, one region (e.g. a step counter, a sensor readout in a third-party app, a price in an app with no API). The fleet collapses to one Device; the value of the product is the timestamped, exportable-to-dashboard history.

**Secondary 2 — Kiosk/appliance fleet monitoring:** Wall-mounted or embedded Android devices showing a status string or numeric KPI. The operator tracks the displayed State/Number remotely and uses fleet start/stop to pause capture during maintenance windows.

### User Stories & Acceptance
1. **Enroll a device** via short pairing code → Device binds to Tenant, shows online/model/OS within 30s; invalid/expired codes rejected clearly.
2. **Mark a region + choose read type** (Number/Text/State) → a TrackingScenario is persisted locally and uploaded with detectionArea, read type, and OCR alphabet.
3. **Set a polling schedule** (fixed interval, min 5s) → one Observation per interval while active; failed reads still record an Observation with `isFulfilled=false` and a confidence value.
4. **Structured Observation per reading** → value, confidence (0–100), `deviceCapturedAt` + `serverReceivedAt`, scenarioId, deviceId; optional cropped PNG viewable from the dashboard.
5. **Automatic, offline-tolerant sync** → Observations queue offline and batch-upload on reconnect; client idempotency keys prevent duplicates; on-device sync state visible.
6. **Web dashboard time series** → line/scatter of value over time, filterable by device and range; low-confidence points distinguished; latest-value-per-device summary table.
7. **Remote fleet start/stop** → command acted on within one poll cycle of the device coming online; dashboard reflects per-device tracking state; commands idempotent and expiring.
8. **Author once, push to many** → assigning a scenario to a device set delivers it on next sync; each device emits Observations tagged with its own deviceId under the shared scenarioId.
9. **Tenant-scoped auth** → all reads/writes authorized against the authenticated Account's Tenant; device tokens authorize only that device's own Observations and assigned scenarios; cross-tenant access rejected.

### In Scope
- A new **read-only tracking mode** reusing `ScreenCondition.Number/Text/Color` and `ConditionsVerifier`; read types Number, Text, Color-State only.
- A **targeted JNI change** to surface the recognized OCR text string to Kotlin (extend `toJniResult` marshalling beyond the current 7-element `DoubleArray`) so Text Observations carry the actual value.
- A new **Observation** domain noun captured at `SmartProcessingListener.onScreenConditionProcessingCompleted`, persisted via a new Room entity + DAO with migration **21 → 22**.
- **Fixed-interval scheduling** per scenario (min 5s) via a new **WorkManager-based scheduler** (WorkManager newly introduced) wrapping `SmartProcessingRepositoryImpl.startDetection` with auto-stop.
- **Optional cropped screenshot** stored through `BitmapRepository` with an `Observation_` prefix.
- New **core:network** module: auth + REST client, batched Observation upload with idempotency keys, scenario pull, command pull, offline queue + retry.
- **Device enrollment** via short-lived pairing code; device-scoped token.
- **Cloud backend (Trax Cloud)**: auth, tenant isolation, Observation ingest/storage, scenario CRUD + assignment, device registry + remote start/stop command queue.
- **Web dashboard**: login, device list (online/tracking), per-metric time series with device + range filters, latest-value table, crop viewer, scenario authoring/assignment, fleet start/stop.
- New **cloud build flavor** on a second dimension so the connected build is separable from the GPLv3 fDroid/playStore builds, with network deps gated via the established flavor-scoped dependency pattern.
- **Settings (DataStore)** for account-binding state, sync-enabled toggle, crop-capture toggle.

### Out of Scope (shippable, not boil-the-ocean)
- Any **action/gesture execution** from Observations — MVP is read-only.
- **Image-template matching** as a tracked read type (legacy auto-clicker only).
- **Alerting/thresholds/anomaly detection/notifications** on values.
- **Event/cron/on-change scheduling** — fixed interval only.
- **Live screen streaming/mirroring** to the dashboard.
- **Roles/RBAC within a Tenant** — single owner Account per Tenant; only owner vs device token.
- **Multi-region scenarios, OCR alphabet auto-detect, per-device number alphabet** (number reads keep using the first-loaded recognition model).
- **Editing/back-filling Observations** — append-only.
- **Billing/subscriptions**, third-party API/webhooks, BI/CSV export.
- **Self-hosting**, **iOS/non-Android capture clients**, and **migration of legacy local scenarios** into the cloud.

### Acceptance Criteria (contract-level gates)
- Number scenario at 10s interval → ~one Observation/10s with full metadata while active.
- Text scenario → Observation `value` carries the recognized string end-to-end through the modified JNI path.
- Offline-captured Observations upload on reconnect with **zero server-side duplicates** (idempotency keys).
- Dashboard-authored scenario assigned to 3 devices → all 3 emit Observations within one sync cycle, each tagged with its own deviceId.
- Remote stop halts the target within one poll cycle and the dashboard reflects `tracking=false`; start resumes.
- Dashboard renders the time series across selected devices/range, distinguishes low-confidence points, shows latest-per-device.
- **Tenant isolation enforced**: Tenant A cannot touch Tenant B's data; device tokens write only their own Observations.
- The **cloud** flavor produces the connected build while **fDroid/playStore** builds compile and run with no network module linked (GPLv3 preserved).
- Migration **21 → 22** lands cleanly with existing scenarios/conditions intact.
- Crop, when enabled, is stored on-device, uploaded, and viewable per Observation in the dashboard.

### Naming, Identifiers & Build Strategy
**Packages stay `com.buzbuz.smartautoclicker.*`** — TraxIntel is a brand/product rename, not a package rename, to avoid churning the R file and the obfuscation pipeline.

**New Gradle modules** (existing `core:`/`feature:` convention, each with `di/Hilt.kt` as `@Module @InstallIn(SingletonComponent::class)`):
- `core:observation` — Observation domain model + Room entity/DAO (depends on `core:smart:database`, `core:common:bitmaps`).
- `core:network` — auth/REST/sync client + offline queue (network deps gated to the cloud flavor via a `cloudImplementation` extension mirroring the existing `playStoreImplementation` gating).
- `core:scheduling` — WorkManager polling scheduler over `SmartProcessingRepositoryImpl.startDetection`.
- `feature:cloud` — enrollment UI, dashboard pairing, sync status, cloud settings.

**Flavor strategy:** add a second flavor dimension `KlickrDimension.CONNECTIVITY` with `KlickrFlavour` values `LOCAL` (`"local"`, default — current behavior, no network) and `CLOUD` (`"cloud"`, links `core:network` + `feature:cloud`), declared in `KlickrVariants.kt` and applied by `FlavourConventionPlugin`. It composes with the existing `VERSION` dimension, producing e.g. `playStoreCloudRelease` and `fDroidLocalRelease`.

**Cloud service name:** **TraxIntel Cloud**, backend identified as **Trax Cloud** (`/v1` API base, OpenAPI-described).

**Top-level data nouns:**
- **Account** — the human login / billing-eligible owner.
- **Tenant** — the data-isolation boundary owning all devices, scenarios, and observations; one owner Account per Tenant in MVP.
- **Device** — an enrolled Android client; device-scoped token; online/tracking status, model, OS.
- **TrackingScenario** — cloud-authored, device-assignable: read type `[Number|Text|State]`, `detectionArea` (Rect, device screen-space), OCR alphabet, polling interval, optional `cropCaptureEnabled`.
- **Observation** — append-only reading: `id`, `idempotencyKey`, `tenantId`, `deviceId`, `scenarioId`, `deviceCapturedAt`, `serverReceivedAt`, `value`, `valueType`, `confidence` (0–100), `isFulfilled`, optional `cropPath`/`cropUrl`.

---

## System architecture & module map

TraxIntel is not a rewrite. It is a read-only screen-intelligence platform grafted onto the existing Smart-AutoClicker (Gradle root project name `Klick'r`, GPLv3, © Kevin Buzeau) detection pipeline. The MVP reuses the frame-acquisition → scaling → OCR → condition-verification stack, taps it at exactly one new observation point, and layers four new on-device modules plus a separate cloud backend on top. The auto-clicker's action/gesture machinery (`ActionExecutor`) is deliberately bypassed in tracking mode: we capture, we do not act.

One correction up front, because it reshapes the design: the observation seam does **not** work against the current types without a small change to GPLv3 core code. The detection callback (`SmartProcessingListener.onScreenConditionProcessingCompleted`) delivers a `ProcessedConditionResult.Screen` that carries **only** `isFulfilled`, `haveBeenDetected`, `condition`, `confidenceRate`, `position`, and `size` — it carries **no recognized value at all**. For a NUMBER condition the OCR'd number is read at `ConditionsVerifier.kt:201` (`detectionResult.numberDetected`), used for the comparison, and then dropped — it is never placed into the `Screen` object built at line 218. For TEXT the recognized string never even leaves C++ (see the "JNI & OCR" section). So surfacing *any* observed value requires (a) extending `ProcessedConditionResult.Screen` with value fields and (b) populating them in the four `verify*` methods. We treat that as a deliberate, minimal, upstream-style edit to the GPLv3 `core:smart:processing` module, documented below — not as something the listener gets for free.

This section lays out (1) how the new modules slot into the existing module graph, (2) the precise capture seam including the GPLv3 edits it requires, (3) the end-to-end data flow including the remote-control return path, (4) the GPLv3 boundary and why the backend lives in its own repository, (5) the concrete module dependency list, and (6) a sequence walkthrough of one tracked Observation. Canonical field shapes for `Observation`, `TrackingScenario`, and the `/v1` API are deferred to the "Data contracts & schemas" section; here I restate only what each module locally needs.

### The existing module graph we build on

The codebase is a multi-module Gradle build wired in `settings.gradle.kts`, split into `core:*` (infrastructure) and `feature:*` (UI/use-case) modules, with `:smartautoclicker` as the single app module. The pieces TraxIntel touches:

- **`core:smart:processing`** — the detection engine. `DetectorEngine.processScreenImages()` loops, acquiring frames via `DisplayRecorder` and calling `ScenarioProcessor.process(screenFrame)`. `ConditionsVerifier.verifyConditions()` runs each `ScreenCondition` through `ImageDetector` and emits a `ProcessedConditionResult.Screen`. The public entry point is `SmartProcessingRepositoryImpl.startDetection(context, liveDebugging, generateReport, autoStopDuration)`. The observation seam is the `SmartProcessingListener` callback interface — `onScreenConditionProcessingCompleted(result)`, fired for *every* condition on *every* frame at lines 149/185/228/255 of `ConditionsVerifier.kt`. **Important constraints, verified in the code, that the rest of this section is built around:**
  - `SmartProcessingListener` is wired as a **single** `progressListener` field, not an observer registry. It is held by `ConditionsVerifier` (line 74) and `ScenarioProcessor` (line 58), and `DetectorEngine` sets it exactly once at construction: `progressListener = if (liveDebugging || generateReport) debuggingListener else null` (`DetectorEngine.kt:258`). There is one slot, and the debug listener already occupies it when debugging is on.
  - `ProcessedConditionResult.Screen` is `data class Screen(isFulfilled, haveBeenDetected, condition, confidenceRate, position, size)` — **no value field**.
  - The screen `Bitmap` is **not** delivered to the listener. `ScenarioProcessor.processScreenEvents()` hands the frame to `imageDetector.setScreenBitmap(screenFrame, processingTag)` at line 154 and never exposes it; the callback signature carries no bitmap.
- **`core:smart:detection`** — Kotlin↔JNI↔C++ bridge. `ImageDetector.detectText/detectNumber/detectColor` return a `DetectionResult`, marshalled from a 7-element `DoubleArray` by `toJniResult` in `jni_detection_result.cpp`. `DetectionResult` exposes `numberDetected: Double?` (populated only for `detectNumber`). The recognized OCR *string* lives only in C++ (`TextRecognizerResult.text`) and is discarded; surfacing it requires the targeted JNI change described in the "JNI & OCR" section. Note for the walkthrough: `detectNumber` always uses the first-loaded recognition model (`defaultRecognitionModelId`) and ignores any alphabet selection, so a NUMBER scenario carries no alphabet fidelity.
- **`core:smart:domain`** — domain models: `Scenario`, the `ScreenCondition` sealed class (`Color`/`Image`/`Number`/`Text`), `Identifier`.
- **`core:smart:database`** — Room. `ClickDatabase` is the `@Database` (its `entities=[...]` list and `autoMigrations=[...]` list both live here); `DatabaseInfo.DATABASE_VERSION = 21`. We migrate to 22.
- **`core:common:bitmaps`** — `BitmapRepository.saveImageConditionBitmap(bitmap, prefix): String` writes a PNG to `filesDir` named `{prefix}{hash}.png` and returns the path, with an LRU cache. We reuse it verbatim for crops with prefix `"Observation_"`.
- **`core:common:settings`** — DataStore-backed `SettingsRepository` over `SettingsDataSource` (Preferences DataStore, file `"settings"`). We add cloud-binding, sync-enabled, and crop-capture flags as new `Preferences.Key` entries (named exactly in "Data contracts & schemas").
- **`:smartautoclicker`** — `@HiltAndroidApp` application + `SmartAutoClickerService` (the `AccessibilityService`) + `LocalService` (business logic, held statically by `LocalServiceProvider`). Hilt wires everything via `@InstallIn(SingletonComponent::class)` modules, one `di/Hilt.kt` per module. The app currently has **no** `Configuration.Provider` and no `androidx.work` dependency.
- **`build-logic/convention`** — convention plugins and `KlickrVariants.kt`, which today defines a single flavor dimension `VERSION` with flavors `F_DROID` and `PLAY_STORE`. `FlavourConventionPlugin` iterates `KlickrDimension.entries` for `flavorDimensions` and `KlickrFlavour.entries` for `productFlavors`. Flavor-scoped dependency gating exists today only for **external libraries**: `CrashlyticsConventionPlugin` uses a `playStoreImplementation` configuration whose extension is `fun DependencyHandlerScope.playStoreImplementation(dependency: Provider<MinimalExternalModuleDependency>) = add("playStoreImplementation", dependency)` (in `DependencyHandlerScopeExt.kt`), so Firebase only links into the Play Store build. There is **no** existing precedent for gating a `project(...)` module dependency by flavor — see the GPLv3 section for how we add one.

### The four new on-device modules

All four follow the established conventions: namespace prefix `com.buzbuz.smartautoclicker.*` (no package rename — that would churn the R file and the obfuscation pipeline in `build-logic/obfuscation`), `buzbuz.androidLibrary` + `buzbuz.hilt` + `buzbuz.flavour` plugins, and a `di/Hilt.kt` exposing `@Module @InstallIn(SingletonComponent::class)` providers.

**`core:observation`** — the new domain noun. Holds the `Observation` domain model, the `ObservationCaptureListener` (a `SmartProcessingListener` implementation, below), the on-device persistence for *both* Observations and pulled `TrackingScenario`s, and the repositories over them. It is *flavor-agnostic*: Observations are captured and persisted locally in every flavor — only their *upload* is cloud-gated. This keeps the capture path testable in the `local` build.

Persistence choice (the reviewer flagged a real coupling problem here). `ObservationEntity` needs a foreign key to the existing `scenario_table`, which means an FK to `ScenarioEntity` — and Room will only enforce that FK if the entity lives in the **same database**, i.e. `ClickDatabase`. There are two honest options:

1. **Embed in `ClickDatabase` (chosen for MVP).** Add `ObservationEntity` + `TrackedScenarioEntity` (the local row for a pulled `TrackingScenario`) to `ClickDatabase`'s `@Database(entities=[...])` list, bump `DatabaseInfo.DATABASE_VERSION` to 22, and add the migration. Be explicit: "`core:observation` owns the migration" actually means **`core:observation` depends on `core:smart:database` and the new entities/DAOs and the `@Database` edit physically live in (or are registered into) `core:smart:database`** — that is a cross-module edit to GPLv3 code, not a self-contained add. Adding a brand-new table with an FK and indices is **not** auto-inferable cleanly, so this is a **manual** `object Migration21to22 : Migration(21, 22)` (following the `Migration19to20` pattern: `CREATE TABLE` + indices via `SupportSQLiteDatabase`), **not** an `AutoMigration`. It is therefore *not* added to the `autoMigrations` list; bumping `DATABASE_VERSION` and registering the `Migration` class is sufficient.
2. **Standalone `ObservationDatabase`.** Keep the new tables in their own Room database inside `core:observation`, with `scenarioId` as a plain (non-FK) column. This avoids editing the GPLv3 `@Database` class and keeps the migration self-contained, at the cost of losing DB-level cascade and cross-table joins to scenario data. We note this as the fallback if the cross-module edit proves contentious; the MVP picks option 1 for cascade-on-scenario-delete semantics.

The Observation entity (FK requires option 1):

```kotlin
// core/smart/database/.../entity/ObservationEntity.kt  (lives with ClickDatabase per option 1)
@Entity(
    tableName = OBSERVATION_TABLE,
    foreignKeys = [ForeignKey(
        entity = ScenarioEntity::class, parentColumns = ["id"],
        childColumns = ["scenario_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("scenario_id"), Index(value = ["idempotency_key"], unique = true), Index("sync_state")],
)
@Serializable
data class ObservationEntity(
    @PrimaryKey(autoGenerate = true) override var id: Long = 0,   // EntityWithId from core:smart:database
    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String, // client-generated UUID
    @ColumnInfo(name = "scenario_id") val scenarioId: Long,
    val deviceCapturedAt: Long,        // epoch ms, System.currentTimeMillis()
    val value: String?,                // recognized text / stringified number / state
    val valueType: String,             // NUMBER | TEXT | STATE
    val confidence: Int,               // 0-100, from confidenceRate
    val isFulfilled: Boolean,
    val cropPath: String? = null,      // BitmapRepository path, "Observation_*.png"
    val syncState: Int = 0,            // 0=PENDING, 1=UPLOADED
)
```

`override var id` couples to the `EntityWithId` interface, which is defined in `core:smart:database`; that, plus the `ScenarioEntity` FK and `@Serializable`, is the concrete reason `core:observation` depends on `core:smart:database`.

`TrackedScenarioEntity` is the on-device persistence of a pulled `TrackingScenario` (read type, interval, crop flag, tracking-active flag, server revision). It is the table the return-path "upsert local TrackingScenario(s)" writes into; `core:scheduling` reads it to know which scenarios are active and at what cadence. Its full shape is in "Data contracts & schemas."

**`core:scheduling`** — the polling driver. WorkManager is *newly introduced* (the codebase has no `androidx.work` dependency today). **Cadence correction:** Android `PeriodicWorkRequest` has a hard **15-minute minimum interval**, so a 5–10 s tracking cadence is *not* achievable with a periodic worker. The MVP therefore drives sub-minute capture by **reusing the existing in-engine auto-stop loop**, not by short periodic workers:
  - The scheduler holds, per active `TrackingScenario`, a coroutine on the IO scope that calls `SmartProcessingRepositoryImpl.startDetection(context, liveDebugging=false, generateReport=false, autoStopDuration=<short>)`, relying on the existing auto-stop path (`startDetection` arms an `autoStopJob` that `delay(duration)`s then calls `stopDetection()`), then `delay`s the scenario interval (min 5 s) and repeats. This is an in-service loop hosted under the existing `LocalService`/foreground-service lifecycle — the same place foreground state is already managed — so it requires no new background-execution guarantees.
  - WorkManager (chained `OneTimeWorkRequest`s, or a single long-lived worker) is used only for the **coarse, network-cadence work** that legitimately tolerates the 15-minute floor: the periodic cloud **sync tick** (upload PENDING, pull scenarios, pull commands). Because only detection capture needs sub-minute timing and that is handled by the in-service loop, the 15-minute floor is not a problem for the parts that actually use WorkManager.
  - If WorkManager *is* introduced, it must be wired in `SmartAutoClickerApplication` with a `HiltWorkerFactory` and a `Configuration.Provider` (the app currently has neither), and `@HiltWorker CoroutineWorker`s injected via `hilt-work`.
  This module depends on `core:smart:processing` (the detection entry point) and `core:observation` (to read active `TrackedScenarioEntity` rows and to receive start/stop commands). It is flavor-agnostic — local capture works without any network.

**`core:network`** — the only *cloud-gated* core module and the GPLv3 firewall on the client side. It owns the auth/REST client (Retrofit/OkHttp), batched Observation upload with idempotency keys, scenario pull, command pull, and an offline queue with retry. Its third-party network dependencies are gated to the `cloud` flavor via a `cloudImplementation` configuration (added in `libs.versions.toml` + the convention layer, mirroring the *pattern* of `playStoreImplementation`). It depends on `core:observation` (reads PENDING rows, marks them UPLOADED; upserts pulled scenarios/commands) and `core:common:settings` (account token, sync toggle). Talks only to **Trax Cloud** at `/v1`.

**`feature:cloud`** — the cloud-gated UI surface: enrollment via pairing code, sync-status display, and cloud settings screens. Depends on `core:network`, `core:common:settings`, `core:common:overlays`/`ui`, and `core:observation`.

#### Module dependency list

```
core:observation      → core:smart:database (EntityWithId, ScenarioEntity FK, @Database edit + Migration21to22),
                        core:common:bitmaps (crop paths), core:common:base
core:scheduling       → core:smart:processing (startDetection), core:observation (active scenarios + commands),
                        core:common:base   (+ androidx.work / hilt-work via libs.versions.toml — sync tick only)
core:network          → core:observation, core:common:settings, core:common:base
                        (cloud-gated: retrofit/okhttp via cloudImplementation external-lib configuration)
feature:cloud         → core:network, core:observation,
                        core:common:settings, core:common:overlays, core:common:ui

:smartautoclicker     → (always)            core:observation, core:scheduling
                      → (cloud flavor only)  core:network, feature:cloud   (gated via cloudImplementation project deps)
```

The dependency direction is strictly inward: feature → core:network → core:observation → core:smart:database. Nothing in the existing detection stack depends on the new modules at the *type* level; the only coupling is the `SmartProcessingListener` callback, which `ObservationCaptureListener` *implements* — dependency inversion — plus the minimal value-field edit to `ProcessedConditionResult.Screen` described next.

### Where the new code taps the existing pipeline

There is exactly one capture seam, and making it work requires three concrete, named edits to the GPLv3 `core:smart:processing` module. None of them changes the action/decision flow; all are additive.

**Edit 1 — extend the result model with the observed value.** `ProcessedConditionResult.Screen` gains two nullable fields:

```kotlin
// core/smart/processing/.../domain/model/ProcessedConditionResult.kt  (GPLv3 edit)
data class Screen(
    override val isFulfilled: Boolean,
    val haveBeenDetected: Boolean,
    val condition: ScreenCondition,
    val confidenceRate: Double,
    val position: Point?,
    val size: Point?,
    val numberDetected: Double? = null,    // NEW — populated by verifyNumberCondition
    val recognizedText: String? = null,    // NEW — populated by verifyTextCondition once JNI surfaces it
) : ProcessedConditionResult
```

**Edit 2 — populate it in the four `verify*` methods of `ConditionsVerifier.kt`.** Today `verifyNumberCondition` computes `numberDetected` at line 201 and discards it when constructing the `Screen` at line 218; we pass it through. `verifyTextCondition` (around line 246) sets `recognizedText` from the new JNI string (see "JNI & OCR"). `verifyColorCondition` (~line 140) and `verifyImageCondition` (~line 175) leave both new fields null — Color carries no recognized value (only `isFulfilled`/`haveBeenDetected`), and Image is not a tracked read type in the MVP. These are one-line-per-call-site changes; the default `null` arguments keep every other caller compiling unchanged.

**Edit 3 — make the listener slot composable.** `progressListener` is a single field set once at `DetectorEngine.kt:258`. To observe *without displacing the debug listener*, we introduce a **fan-out** `SmartProcessingListener`:

```kotlin
// core/smart/processing/.../domain/CompositeProcessingListener.kt  (GPLv3 edit, new file)
internal class CompositeProcessingListener(
    private val delegates: List<SmartProcessingListener>,
) : SmartProcessingListener {
    override fun onScreenConditionProcessingCompleted(result: ProcessedConditionResult.Screen) {
        delegates.forEach { it.onScreenConditionProcessingCompleted(result) }
    }
    // ...every other callback fanned out identically
}
```

`SmartProcessingRepositoryImpl.startDetection` is the injection point: it assembles the delegate list — the existing `debuggingListener` (when `liveDebugging || generateReport`) plus, when an observation capture is requested, the injected `ObservationCaptureListener` — wraps them in `CompositeProcessingListener`, and passes that into `DetectorEngine.startDetection` in place of the bare `debuggingListener` at line 258. When no delegates are active the field stays `null`, exactly as today.

**Listener context (who/what is being observed).** The callback signature carries only the `result`; it has no scenario or settings context. `ObservationCaptureListener` is therefore **stateful and bound per session**: `core:scheduling`, when it kicks off `startDetection` for a given `TrackedScenarioEntity`, sets `activeTrackingScenarioId` and `cropCaptureEnabled` on the listener instance (a `bind(scenarioId, cropEnabled)` call before detection starts, cleared on auto-stop). Because the scheduler runs one scenario's capture at a time per session, there is no ambiguity about which scenario the incoming results belong to.

**The crop frame access path (the missing link).** The callback delivers no `Bitmap`, and the live frame is held inside `ScenarioProcessor`/`imageDetector` (set at `ScenarioProcessor.kt:154`). `core:observation` cannot reach it from the callback alone. So Edit 3 also widens the observation surface: rather than crop *inside* the listener, the crop is produced where the frame is in scope. The cleanest grounded option is to give `ObservationCaptureListener` a `FrameCropper` collaborator that `ScenarioProcessor` populates for the current frame (the same place it already calls `setScreenBitmap`), exposing a `cropCurrentFrame(detectionArea: Rect): Bitmap` that crops the live `screenFrame` to the scaled-up `detectionArea` (`ScalingManager.scaleUpDetectionResult`). The listener calls this synchronously during `onScreenConditionProcessingCompleted` (still inside the same frame's processing, before the next `setScreenBitmap`), then hands the crop to `ObservationRepository`. The crop persists via `BitmapRepository.saveImageConditionBitmap(crop, "Observation_")`, and the returned path goes into `ObservationEntity.cropPath`.

With those in place the listener body is concrete — and notably **does not** call any invented `extractValue()`/`toValueType()`, and never confuses `result.condition` (the search target you configured) with the observed reading:

```kotlin
override fun onScreenConditionProcessingCompleted(result: ProcessedConditionResult.Screen) {
    val scenarioId = activeTrackingScenarioId ?: return        // set by core:scheduling at bind()
    val (value, valueType) = when (result.condition) {
        is ScreenCondition.Number -> result.numberDetected?.toString() to "NUMBER"   // Edit 1/2 field
        is ScreenCondition.Text   -> result.recognizedText            to "TEXT"      // Edit 1/2 field
        is ScreenCondition.Color  -> if (result.isFulfilled) "MATCH" else "NO_MATCH" to "STATE"
        else -> return                                                                // Image not tracked
    }
    val crop = if (cropCaptureEnabled) frameCropper.cropCurrentFrame(result) else null
    observationRepository.record(
        scenarioId = scenarioId,
        value = value,
        valueType = valueType,
        confidence = result.confidenceRate.roundToInt(),
        isFulfilled = result.isFulfilled,
        capturedAt = System.currentTimeMillis(),
        crop = crop,
    )
}
```

Crucially, `isFulfilled=false` reads still produce an Observation (acceptance criterion: failed reads record an Observation with a confidence value), because the callback fires at lines 149/185/228/255 regardless of fulfillment — we do not gate on `results.fulfilled` the way `ScenarioProcessor` does at line 172 before `executeActions()`. That is the architectural expression of "read-only": we hook *before* the action decision point and never reach `ActionExecutor`.

### End-to-end data flow

```
[ Marked app screen ]
        │  MediaProjection → DisplayRecorder.acquireLatestBitmap()
        ▼
core:scheduling  ── in-service loop tick (interval ≥5s) ──► SmartProcessingRepositoryImpl.startDetection(autoStopDuration)
                    (NOT a periodic WorkManager job — periodic floor is 15 min)
        ▼
core:smart:processing
   DetectorEngine.processScreenImages() → ScenarioProcessor.process(frame)  [frame set at line 154]
        │
        ▼  ConditionsVerifier.verifyConditions() → ImageDetector.detect{Number|Text|Color}
   ProcessedConditionResult.Screen  (+ numberDetected/recognizedText — Edits 1&2)
        │  CompositeProcessingListener fans out to: debuggingListener (if on) + ObservationCaptureListener
        ▼
core:observation
   ObservationCaptureListener (bound with scenarioId + cropEnabled by core:scheduling)
        ├── optional crop via FrameCropper.cropCurrentFrame() → BitmapRepository.save(..., "Observation_")
        └── ObservationRepository.record() → ObservationEntity(syncState=PENDING) → ClickDatabase (v22)
        │
        ▼  (cloud flavor only)
core:network
   batch PENDING rows + idempotencyKeys ──HTTPS──► Trax Cloud  POST /v1/observations:batch
        ◄── 200 → mark syncState=UPLOADED
        │
        ▼
[ Trax Cloud ]  tenant-scoped ingest/store  ──►  [ Web dashboard ]
   time series, latest-per-device, crop viewer
```

**Return path (remote control + author-once):** the dashboard writes a `TrackingScenario` or a start/stop command into Trax Cloud, scoped to the Tenant and assigned to a device set. The device pulls these on its next sync tick:

```
[ Web dashboard ] → Trax Cloud
        │   PUT /v1/scenarios, POST /v1/devices/{id}/commands (start|stop)
        ▼
core:network  (poll on the WorkManager sync tick)
   GET /v1/devices/{self}/scenarios   → upsert into TrackedScenarioEntity (core:observation, ClickDatabase v22)
   GET /v1/devices/{self}/commands    → idempotent, expiring commands (stored as CommandEntity, dedup by command id)
        │
        ▼
core:scheduling
   start command → mark TrackedScenarioEntity active → in-service loop begins capturing scenarioId
   stop  command → mark inactive → loop stops scheduling that scenarioId on its next interval check
        │
        ▼  device emits ack / next Observations reflect new state → dashboard tracking=true/false
```

On-device, pulled scenarios persist in `TrackedScenarioEntity` and commands in a small `CommandEntity` table (both in `ClickDatabase` v22, owned through `core:observation`). Commands carry an id and expiry so a device that was offline does not replay a stale command, and `core:network` records the last-applied command id to make application idempotent. Because the device is the active poller (no push channel in MVP), the contract's "within one poll cycle" guarantee falls out naturally: a remote stop is honored on the device's next sync tick, which flips the `TrackedScenarioEntity` active flag the in-service loop reads. Idempotency keys on the upload side guarantee zero server-side duplicates even when the offline queue retries a batch that actually succeeded.

### The GPLv3 boundary — why the backend is a separate non-GPL repo

Smart-AutoClicker is GPLv3 (every source file, e.g. `KlickrVariants.kt`, carries the GPLv3 header © Kevin Buzeau). GPLv3 is a strong copyleft license: derivative works that link against or are distributed with GPLv3 code must themselves be GPLv3. The MVP must preserve the ability to ship the **fDroid** and **playStore** builds as pure GPLv3 apps with *no network module linked at all*.

Note that the three capture-seam edits (extend `ProcessedConditionResult.Screen`, populate the four `verify*` methods, add `CompositeProcessingListener`) are edits *to* GPLv3 code and remain GPLv3 — they ship in every flavor and are intentionally generic, debug-listener-style additions, not cloud-specific. The copyleft firewall is drawn around the *network* code, not the capture code.

The architecture enforces this boundary on two axes:

1. **On the client, by flavor.** `core:network` and `feature:cloud` link into the app *only* in the `cloud` flavor. We add a second flavor dimension `CONNECTIVITY` to `KlickrVariants.kt`:

```kotlin
enum class KlickrDimension(val flavourDimensionName: String) {
    VERSION("version"),
    CONNECTIVITY("connectivity");   // NEW
}
enum class KlickrFlavour(val flavourName: String, val dimension: KlickrDimension) {
    F_DROID("fDroid", KlickrDimension.VERSION),
    PLAY_STORE("playStore", KlickrDimension.VERSION),
    LOCAL("local", KlickrDimension.CONNECTIVITY),   // NEW, default — no network
    CLOUD("cloud", KlickrDimension.CONNECTIVITY);   // NEW — links network + feature:cloud
}
```

`FlavourConventionPlugin` already iterates `KlickrDimension.entries` for `flavorDimensions` and `KlickrFlavour.entries` for `productFlavors` (verified), so both new values apply automatically to every `android`/`androidLib` block. The matrix composes: `fDroidLocalRelease`, `playStoreLocalRelease`, `fDroidCloudRelease`, `playStoreCloudRelease`. The `local` variants compile and run with the network module entirely absent — the GPLv3 acceptance gate.

**Gating a `project(...)` dependency by flavor — and how it differs from the Crashlytics precedent.** The existing `playStoreImplementation` extension is typed for *external libraries* only: `fun DependencyHandlerScope.playStoreImplementation(dependency: Provider<MinimalExternalModuleDependency>) = add("playStoreImplementation", dependency)`. It is used to link Firebase, never a `project()` module, so it is **not** a one-line copy for our needs. Gradle auto-creates the `cloud<Variant>Implementation` configurations from the new flavor; project dependencies *can* be added to them. We therefore add a **separate, correctly-typed** project-dependency overload (the external-library one is left untouched), e.g.:

```kotlin
// DependencyHandlerScopeExt.kt — NEW overload, distinct from the external-library playStoreImplementation
fun DependencyHandlerScope.cloudImplementation(dependency: ProjectDependency) =
    add("cloudImplementation", dependency)
```

and use it in the app module:

```kotlin
// smartautoclicker/build.gradle.kts
dependencies {
    implementation(project(":core:observation"))             // all flavors
    implementation(project(":core:scheduling"))              // all flavors
    cloudImplementation(project(":core:network"))            // cloud flavor only
    cloudImplementation(project(":feature:cloud"))           // cloud flavor only
}
```

(External-library network deps inside `core:network` — Retrofit/OkHttp — are themselves added via the existing external-library gating style within that module's own build.) Code in `:smartautoclicker` that touches the cloud (registering `core:network`'s sync coordinator and the WorkManager sync tick) must be confined to the `cloud` source set (`src/cloud/...`) so the `local` variant has no compile-time reference to it — exactly as Crashlytics code is confined for Play Store today.

2. **The backend is a separate repository.** Trax Cloud (auth, tenant isolation, Observation ingest, scenario CRUD, device registry, command queue, web dashboard) shares no source with the Android repo. It communicates only over the documented `/v1` REST surface (OpenAPI-described). Because GPLv3 copyleft is triggered by *linking/derivation*, and a network client speaking HTTP to an independent server is the canonical arm's-length boundary (the "mere aggregation"/separate-program case, not a derivative work), the backend can carry whatever license its operator chooses. The OpenAPI contract — not shared Kotlin types — is the integration seam, which also lets the dashboard and any future client evolve independently. This is why `core:network` owns its own DTOs and serialization rather than reusing the GPLv3 `@Serializable` domain entities from `core:smart:database`; the wire format is the property of the (non-GPL) API contract, defined canonically in "Data contracts & schemas."

### Sequence walkthrough: one tracked Observation

A `playStoreCloud` device has a `TrackingScenario` (read type NUMBER, 10 s interval, `cropCaptureEnabled=true`) pulled into `TrackedScenarioEntity` and marked tracking-active. One reading:

1. **Tick.** `core:scheduling`'s in-service loop for the scenario fires (10 s elapsed — driven by `delay(interval)` in the loop, *not* a periodic worker). It first `bind(scenarioId, cropEnabled=true)`s the `ObservationCaptureListener`, then calls `SmartProcessingRepositoryImpl.startDetection(context, liveDebugging=false, generateReport=false, autoStopDuration=<short>)`. The repository assembles a `CompositeProcessingListener` (no debug delegate here; the observation delegate only) and passes it into `DetectorEngine.startDetection`; an `autoStopJob` is armed to `stopDetection()` after the duration.
2. **Frame.** `DetectorEngine.processScreenImages()` acquires the latest frame from `DisplayRecorder` and calls `ScenarioProcessor.process(frame)`; `processScreenEvents` sets it via `imageDetector.setScreenBitmap(...)` (line 154) and arms the `FrameCropper` for this frame. `ScalingManager` has mapped the device-space `detectionArea` to detection-space.
3. **Verify.** `ConditionsVerifier.verifyConditions()` captures `currentVerificationTsMs`, then runs `verifyNumberCondition()`, which calls `ImageDetector.detectNumber(detectionArea, threshold)` → JNI → C++ OCR (using the default recognition model — number reads ignore alphabet selection). `numberDetected` is read at line 201, used for the comparison, **and — via Edit 2 — now also placed into** `ProcessedConditionResult.Screen(..., numberDetected = ...)` (line 218 path). Position/size are scaled up via `scaleUpDetectionResult()`.
4. **Callback.** `progressListener.onScreenConditionProcessingCompleted(result)` fires (line 228 path); `CompositeProcessingListener` fans it out to `ObservationCaptureListener`. (For a TEXT scenario this is the line 255 path and `result.recognizedText` carries the OCR string via the new JNI marshalling.)
5. **Crop.** Because `cropCaptureEnabled`, the listener calls `frameCropper.cropCurrentFrame(result)` synchronously (still within this frame's processing, before the next `setScreenBitmap`), which crops the live frame to the scaled-up `detectionArea` and returns a `Bitmap`. `ObservationRepository.record()` persists it via `BitmapRepository.saveImageConditionBitmap(crop, "Observation_")`, getting back e.g. `Observation_-1837465.png`.
6. **Persist.** It builds `ObservationEntity{ idempotencyKey=UUID, scenarioId, deviceCapturedAt=now, value="4280", valueType="NUMBER", confidence=92, isFulfilled=true, cropPath="Observation_-1837465.png", syncState=PENDING }` and inserts it into `ClickDatabase` (v22) via `ObservationDao` (`@Insert(onConflict=IGNORE)`, idempotency key uniquely indexed).
7. **Auto-stop.** The `autoStopJob` fires, `stopDetection()` releases the recorder, the listener binding is cleared. The loop `delay`s the remaining interval until the next tick.
8. **Sync (cloud flavor).** On the WorkManager sync tick, `core:network`'s upload coordinator reads PENDING rows, batches them with their idempotency keys and the device token, and `POST /v1/observations:batch` to Trax Cloud over HTTPS. On 200 it sets `syncState=UPLOADED`. The crop PNG is uploaded to its storage slot; `cropUrl` is resolved server-side. If offline, the row stays PENDING and the queue retries on reconnect — duplicates are impossible because the server dedupes on `idempotencyKey`.
9. **Cloud + dashboard.** Trax Cloud stamps `serverReceivedAt`, validates the device token authorizes only this device's own Observations under the shared `scenarioId`, stores it tenant-scoped, and the dashboard renders the point (value 4280 @ confidence 92) on the per-metric time series and updates the latest-value-per-device row.

In the `local` flavor, steps 1–7 are identical and step 8 simply does not exist — `core:network` is not linked. That single fact is the whole GPLv3 preservation strategy and the whole "capture works offline / cloud is additive" story, expressed as a build-graph property rather than a runtime flag.

---

## core:capture — observation model & pipeline integration

> Naming note: the MVP Scope Contract calls this module `core:observation`. This section uses the module label `core:capture` per the assigned section title; treat them as the same artifact. Wherever the contract's module wiring matters (Hilt convention, `di/Hilt.kt`, dependency on `core:smart:database` and `core:common:bitmaps`), the contract wins. The namespace is `com.buzbuz.smartautoclicker.core.capture` (packages stay under `com.buzbuz.smartautoclicker.*` per the contract's no-package-rename rule).

This module turns the existing read-only detection signal into a durable, structured **Observation** record. It owns three responsibilities: (1) the `Observation` domain model + Room persistence, (2) the migration that lands the new table, and (3) the listener that converts a fulfilled-or-failed `ScreenCondition` verification into a persisted row plus an optional screenshot crop. It is deliberately on-device only and append-only; upload is `core:network`'s job, scheduling is `core:scheduling`'s job. This section restates only the local fields it needs — canonical wire shapes live in "Data contracts & schemas."

### Where the observation is emitted in the pipeline

The capture hook is the `SmartProcessingListener` callback fired for every screen condition, fulfilled or not. From the verified interface at `/Users/Sophia/Documents/GitHub/AutoClicker/core/smart/processing/src/main/java/com/buzbuz/smartautoclicker/core/processing/domain/SmartProcessingListener.kt`:

```kotlin
fun onScreenConditionProcessingCompleted(result: ProcessedConditionResult.Screen) = Unit
```

This is the right seam, not `onEventActionsExecuted`, for three reasons grounded in the facts:

1. **It fires per condition, unconditionally.** Per `ConditionsVerifier.kt` (the verify* methods at lines 127–256), `progressListener.onScreenConditionProcessingCompleted(result)` is called at lines 149, 185, 228, and 255 — once for every condition regardless of fulfillment. The contract's user story 3 demands that *failed reads still record an Observation with `isFulfilled=false`*. Hooking the per-condition callback satisfies that directly; hooking the event-level `onEventActionsExecuted` would only fire on AND/OR-satisfied events and would skip the negative readings we must persist.
2. **It carries the structured payload (plus the additive extensions below).** `ProcessedConditionResult.Screen` (from `/Users/Sophia/Documents/GitHub/AutoClicker/core/smart/processing/src/main/java/com/buzbuz/smartautoclicker/core/processing/domain/model/ProcessedConditionResult.kt`) gives us `isFulfilled`, `haveBeenDetected`, the originating `ScreenCondition`, `confidenceRate` (0–100 per the facts), and screen-space `position`/`size` (already scaled up via `scalingManager.scaleUpDetectionResult()`). The recognized number/text and verification timestamp are *not* on the type today and are threaded onto it additively (see "The missing payload" below).
3. **The MVP read types are exactly the screen-condition subtypes.** The contract limits reads to Number / Text / Color-State, which map 1:1 to `ScreenCondition.Number`, `ScreenCondition.Text`, and `ScreenCondition.Color` from `/Users/Sophia/Documents/GitHub/AutoClicker/core/smart/domain/src/main/java/com/buzbuz/smartautoclicker/core/domain/model/condition/ScreenCondition.kt`. `ScreenCondition.Image` is explicitly out of scope and is filtered out at the capture boundary.

#### The missing payload: number, recognized text, and verification timestamp

`ProcessedConditionResult.Screen` as it exists today is exactly (verified):

```kotlin
data class Screen(
    override val isFulfilled: Boolean,
    val haveBeenDetected: Boolean,
    val condition: ScreenCondition,
    val confidenceRate: Double,
    val position: Point?,
    val size: Point?,
) : ProcessedConditionResult()      // NOTE: ProcessedConditionResult is a sealed CLASS, not an interface.
```

It does **not** surface the extracted number, the recognized string, or the verification timestamp. The numeric value lives in `DetectionResult.numberDetected: Double?` (`/Users/Sophia/Documents/GitHub/AutoClicker/core/smart/detection/src/main/java/com/buzbuz/smartautoclicker/core/detection/DetectionResult.kt`) and is currently consumed inside `verifyNumberCondition`/`verifyTextCondition` for the comparison/fuzzy-match decision and then discarded before constructing the `Screen` result. The recognized **text** string is worse off: it never crosses JNI at all (the 7-element `DoubleArray` in `jni_detection_result.cpp` carries `numberDetected` in slot `[6]` but no string).

Three coordinated changes outside this module are prerequisites, all already in the contract's in-scope list and detailed in their own sections:

- **JNI text marshalling** (detection section): extend `toJniResult` / `detectTextNative` so `TextRecognizerResult.text` reaches Kotlin. The recognized string then needs to land on a new nullable field on `DetectionResult` (e.g. `recognizedText: String?`).
- **Result propagation**: thread `numberDetected` and the new `recognizedText` from `DetectionResult` into `ProcessedConditionResult.Screen` so the capture listener can read them.
- **Verification timestamp propagation**: thread `ConditionsVerifier.currentVerificationTsMs` (captured at verify-loop start, line 57 per the facts) onto the result. This is required — not optional — because the deterministic idempotency key depends on a stable timestamp (see "Idempotency" below).

The minimal additive change, extending the **sealed class** (parentheses on the supertype, `data class`-on-sealed-class inheritance — *not* interface syntax):

```kotlin
// ProcessedConditionResult.Screen — additive, nullable/defaulted fields.
// No behavior change for the legacy auto-clicker: every new field defaults so existing
// construction sites (Image, Color) compile and behave unchanged.
sealed class ProcessedConditionResult {
    abstract val isFulfilled: Boolean

    data class Screen(
        override val isFulfilled: Boolean,
        val haveBeenDetected: Boolean,
        val condition: ScreenCondition,
        val confidenceRate: Double,
        val position: Point?,
        val size: Point?,
        val capturedNumber: Double? = null,     // from DetectionResult.numberDetected
        val capturedText: String? = null,       // from DetectionResult.recognizedText (post JNI change)
        val verificationTsMs: Long? = null,     // from ConditionsVerifier.currentVerificationTsMs
    ) : ProcessedConditionResult()
    // ... Trigger unchanged
}
```

`core:capture` only reads these fields; it never requires them to be populated by legacy paths.

#### Distinguishing "nothing detected" from "detected but condition false"

This matters for the negative-reading acceptance criterion. The `Screen` result already separates the two axes the contract cares about:

- `haveBeenDetected` — did the detector read anything at all? When `ConditionsVerifier` returns `toInvalidConditionResult()` (detection failed, or `numberDetected` was null for a Number condition), `haveBeenDetected` is `false`, `confidenceRate` is `0.0`, and `capturedNumber`/`capturedText` are `null`.
- `isFulfilled` — did the read satisfy the condition's comparison/match (and `shouldBeDetected` polarity)? A Number can be detected (`haveBeenDetected=true`, `capturedNumber=42.0`) yet `isFulfilled=false` because the comparison failed.

The capture module persists **both** flags on every Observation. The intended behavior for the MVP: emit an Observation for *every* `onScreenConditionProcessingCompleted` call, including invalid/nothing-detected results (`haveBeenDetected=false`, value null). This is what satisfies user story 3 ("failed reads still record an Observation with `isFulfilled=false`"). Downstream consumers (dashboard, `core:network`) read `haveBeenDetected` to tell apart "the OCR/color read nothing" from "it read a value that didn't satisfy the condition." The contract does not ask us to suppress nothing-detected rows; suppressing them would lose the negative signal the feature exists to capture.

### The Observation domain model

The domain model is the in-memory shape the listener produces and the repository persists. It is intentionally narrower than the cloud `Observation` noun (no `serverReceivedAt`, no `cropUrl` — those are server-assigned). `screenPosition`/`screenSize` are stored from the `Screen` result's `position`/`size`.

```kotlin
// core/capture/src/main/java/com/buzbuz/smartautoclicker/core/capture/domain/model/Observation.kt
package com.buzbuz.smartautoclicker.core.capture.domain.model

import android.graphics.Point
import com.buzbuz.smartautoclicker.core.base.identifier.Identifier

enum class ObservationValueType { NUMBER, TEXT, STATE }

/** Local mirror of the on-device sync lifecycle for one Observation row. */
enum class ObservationSyncState { PENDING, IN_FLIGHT, SYNCED, FAILED }

data class Observation(
    val id: Identifier,                // not-yet-inserted: Identifier(tempId, asTemporary = true); see note
    val scenarioId: Identifier,        // local Scenario id (running scenario)
    val eventId: Identifier,           // ScreenCondition.eventId
    val conditionId: Identifier,       // ScreenCondition.id
    val valueType: ObservationValueType,
    val capturedValueText: String?,    // recognized OCR string (TEXT), or null
    val capturedValueNumber: Double?,  // numberDetected (NUMBER), or null
    val haveBeenDetected: Boolean,     // ProcessedConditionResult.Screen.haveBeenDetected
    val confidence: Double,            // 0..100, from ProcessedConditionResult.Screen.confidenceRate
    val isFulfilled: Boolean,          // ProcessedConditionResult.Screen.isFulfilled
    val screenPosition: Point?,        // screen-space center (already scaled up)
    val screenSize: Point?,            // screen-space size
    val screenshotCropPath: String?,   // BitmapRepository path, "Observation_*.png", nullable
    val deviceTimestamp: Long,         // verification timestamp (see Idempotency)
    val idempotencyKey: String,        // deterministic, stable across retries (see Idempotency)
    val syncState: ObservationSyncState,
)
```

The `Identifier` type is the existing `data class Identifier(val databaseId: Long = DATABASE_ID_INSERTION, val tempId: Long? = null)` from `/Users/Sophia/Documents/GitHub/AutoClicker/core/common/base/src/main/java/com/buzbuz/smartautoclicker/core/base/identifier/Identifier.kt`, reused for consistency with `Scenario`/`Condition` ids. **Important runtime constraint (verified):** its `init` block throws `IllegalArgumentException` when `databaseId == DATABASE_ID_INSERTION (0L) && tempId == null`. A not-yet-inserted Observation must therefore be constructed with the secondary constructor `Identifier(id = <tempId>, asTemporary = true)` (which sets `databaseId = 0L`, `tempId = <tempId>`), **never** `Identifier(databaseId = 0L)`. The repository discards this placeholder id on insert (Room autogenerates the real `id`) and the returned rowId is what becomes the persisted identity. `Point` is the same `android.graphics.Point` used throughout `ProcessedConditionResult.Screen`.

### The Room @Entity

The entity follows the codebase's established conventions exactly: `@Entity` + `@Serializable` (every entity in `core:smart:database` is `@Serializable` for kotlinx.serialization, per `ActionEntity`/`ConditionEntity`), `@PrimaryKey(autoGenerate = true)`, a `ForeignKey` with `onDelete = CASCADE` to the parent scenario (mirroring `EventEntity`'s `@ColumnInfo(name = "scenario_id")` FK to `ScenarioEntity`), and indices on the columns we query and join on. `screenPosition`/`screenSize` are flattened to nullable Int columns rather than stored as `Point` (Room has no built-in `Point` converter here, and flattening avoids adding a TypeConverter).

```kotlin
// core/smart/database/src/main/java/com/buzbuz/smartautoclicker/core/database/entity/ObservationEntity.kt
package com.buzbuz.smartautoclicker.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

internal const val OBSERVATION_TABLE = "observation_table"

@Entity(
    tableName = OBSERVATION_TABLE,
    foreignKeys = [
        ForeignKey(
            entity = ScenarioEntity::class,
            parentColumns = ["id"],          // ScenarioEntity PK is `@PrimaryKey override val id: Long`
            childColumns = ["scenario_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index("scenario_id"),
        Index("sync_state"),
        Index(value = ["idempotency_key"], unique = true),
    ],
)
@Serializable
data class ObservationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    @ColumnInfo(name = "scenario_id") val scenarioId: Long,
    @ColumnInfo(name = "event_id") val eventId: Long,
    @ColumnInfo(name = "condition_id") val conditionId: Long,

    @ColumnInfo(name = "value_type") val valueType: String,        // ObservationValueType.name
    @ColumnInfo(name = "captured_value_text") val capturedValueText: String? = null,
    @ColumnInfo(name = "captured_value_number") val capturedValueNumber: Double? = null,

    @ColumnInfo(name = "have_been_detected") val haveBeenDetected: Boolean,
    @ColumnInfo(name = "confidence") val confidence: Double,
    @ColumnInfo(name = "is_fulfilled") val isFulfilled: Boolean,

    @ColumnInfo(name = "screen_pos_x") val screenPosX: Int? = null,
    @ColumnInfo(name = "screen_pos_y") val screenPosY: Int? = null,
    @ColumnInfo(name = "screen_size_w") val screenSizeW: Int? = null,
    @ColumnInfo(name = "screen_size_h") val screenSizeH: Int? = null,

    @ColumnInfo(name = "screenshot_crop_path") val screenshotCropPath: String? = null,
    @ColumnInfo(name = "device_timestamp") val deviceTimestamp: Long,
    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String,
    @ColumnInfo(name = "sync_state") val syncState: String,        // ObservationSyncState.name
)
```

The FK references `ScenarioEntity` (table `scenario_table`, PK column `id`), the existing scenario table, not a not-yet-built `TrackingScenarioEntity`. For the MVP this keeps the migration self-contained against the current schema; the cloud `scenarioId` tagging is layered in by `core:network` at upload time from the cloud-side `TrackingScenario` mapping (Data contracts section). The unique index on `idempotency_key` is the on-device half of the contract's zero-duplicate guarantee (user story 5).

**Database registration — note where the accessor goes.** `ClickDatabase` (`/Users/Sophia/Documents/GitHub/AutoClicker/core/smart/database/src/main/java/com/buzbuz/smartautoclicker/core/database/ClickDatabase.kt`) carries the `@Database(entities = [...], version = ...)` annotation but has an **empty body** — it extends `ScenarioDatabase()`, which is where every abstract DAO accessor is actually declared (per the new-entity recipe in the facts: *"Add to `ScenarioDatabase` abstract val `<name>Dao`"*). So:

- `ObservationEntity` is added to `ClickDatabase`'s `@Database(entities = [...])` list.
- The new abstract accessor `abstract fun observationDao(): ObservationDao` is declared on **`ScenarioDatabase`**, not on `ClickDatabase` (which has no body to add it to).

### The migration: 21 → 22

`DATABASE_VERSION` is currently `21`, confirmed at `/Users/Sophia/Documents/GitHub/AutoClicker/core/smart/database/src/main/java/com/buzbuz/smartautoclicker/core/database/DatabaseInfo.kt`. We bump to `22`.

`ClickDatabase` uses **two** migration mechanisms (per the facts): `@Database(autoMigrations = [...])` for simple add/drop-column changes, and standalone `object MigrationNtoN+1 : Migration(N, N+1)` classes (e.g. `Migration12to13`, `Migration19to20`) for anything that creates tables or transforms data. **Creating a new table is not expressible as an AutoMigration here** (Room AutoMigration handles column-level deltas within existing tables; a brand-new entity table with FK + indices is cleanest as an explicit `CREATE TABLE`). So 21→22 is a **manual** migration, following the `Migration19to20` pattern exactly: an `object` extending `Migration(21, 22)` with `override fun migrate(db: SupportSQLiteDatabase)`, **not** added to the `autoMigrations` list — manual `Migration` objects are picked up from the migrations package without an annotation entry.

```kotlin
// core/smart/database/src/main/java/com/buzbuz/smartautoclicker/core/database/migrations/Migration21to22.kt
package com.buzbuz.smartautoclicker.core.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds the observation_table for the TraxIntel capture module (read-only Observations). */
object Migration21to22 : Migration(21, 22) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `observation_table` (
                `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                `scenario_id` INTEGER NOT NULL,
                `event_id` INTEGER NOT NULL,
                `condition_id` INTEGER NOT NULL,
                `value_type` TEXT NOT NULL,
                `captured_value_text` TEXT,
                `captured_value_number` REAL,
                `have_been_detected` INTEGER NOT NULL,
                `confidence` REAL NOT NULL,
                `is_fulfilled` INTEGER NOT NULL,
                `screen_pos_x` INTEGER,
                `screen_pos_y` INTEGER,
                `screen_size_w` INTEGER,
                `screen_size_h` INTEGER,
                `screenshot_crop_path` TEXT,
                `device_timestamp` INTEGER NOT NULL,
                `idempotency_key` TEXT NOT NULL,
                `sync_state` TEXT NOT NULL,
                FOREIGN KEY(`scenario_id`) REFERENCES `scenario_table`(`id`)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL("CREATE INDEX IF NOT EXISTS `index_observation_table_scenario_id` ON `observation_table` (`scenario_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_observation_table_sync_state` ON `observation_table` (`sync_state`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_observation_table_idempotency_key` ON `observation_table` (`idempotency_key`)")
    }
}
```

This is purely additive — no existing table is touched — which is why the contract's gate "*Migration 21 → 22 lands cleanly with existing scenarios/conditions intact*" holds: every prior row is untouched. The boolean columns are `INTEGER` (Room's encoding), and the entity's nullable fields map to nullable SQLite columns.

**Required, concrete verification steps (do not hand-write and hope):** Room validates the live schema against its generated identity hash on first open and throws if the DDL diverges (column order, types, FK clause, auto-named indices `index_observation_table_*`). The risk surfaces are exactly: (a) column order must match the entity field declaration order *as listed above* (`id, scenario_id, event_id, condition_id, value_type, captured_value_text, captured_value_number, have_been_detected, confidence, is_fulfilled, screen_pos_*, screen_size_*, screenshot_crop_path, device_timestamp, idempotency_key, sync_state`); (b) the `UNIQUE` index on `idempotency_key` plus the two non-unique indices must match Room's auto-generated names. The project ships with `exportSchema = true`, so the canonical way to obtain the exact DDL is:

1. Add `ObservationEntity` to `ClickDatabase`, add `observationDao()` to `ScenarioDatabase`, bump `DATABASE_VERSION = 22`.
2. Run the Gradle build for the `core:smart:database` module so Room exports a **new `22.json` schema** under the module's `schemas/` directory (alongside the existing `1.json … 21.json`).
3. Copy the generated `CREATE TABLE` / `CREATE INDEX` statements from `22.json` verbatim into `Migration21to22` (the recipe above mirrors the expected output but the exported JSON is authoritative).
4. Wire a `MigrationTestHelper`-based schema test that migrates a `21.json`-seeded database forward through `Migration21to22` and asserts the result validates against `22.json`. This is the verification gate the contract relies on.

Registration in the database builder (`SmartDatabaseModule.providesClickDatabase` at `core/smart/database/.../di/Hilt.kt`, which already lists the manual migrations `Migration1to2 … Migration19to20`):

```kotlin
Room.databaseBuilder(context, ClickDatabase::class.java, "click_database")
    .addMigrations(/* …existing manual migrations… */ Migration21to22)
    .build()
```

`TutorialDatabase` (also built in this module via its own `addMigrations`) is unaffected: `ObservationEntity` is only added to `ClickDatabase`. And bump `DatabaseInfo.kt`: `const val DATABASE_VERSION = 22`.

### DAO and repository

The DAO follows the `@Dao abstract class` + suspend CRUD pattern from `ActionDao`, with append-only insert and sync-state query helpers the network module needs.

```kotlin
// core/smart/database/src/main/java/com/buzbuz/smartautoclicker/core/database/dao/ObservationDao.kt
@Dao
abstract class ObservationDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)   // unique idempotency_key → silent dedup, returns -1 on conflict
    abstract suspend fun insert(observation: ObservationEntity): Long

    @Query("SELECT * FROM observation_table WHERE sync_state = :state ORDER BY device_timestamp ASC LIMIT :limit")
    abstract suspend fun getBySyncState(state: String, limit: Int): List<ObservationEntity>

    @Query("UPDATE observation_table SET sync_state = :state WHERE id IN (:ids)")
    abstract suspend fun updateSyncState(ids: List<Long>, state: String)

    @Query("SELECT * FROM observation_table WHERE scenario_id = :scenarioId ORDER BY device_timestamp DESC")
    abstract fun observeForScenario(scenarioId: Long): Flow<List<ObservationEntity>>

    @Query("SELECT screenshot_crop_path FROM observation_table WHERE sync_state = 'SYNCED' AND device_timestamp < :olderThan AND screenshot_crop_path IS NOT NULL")
    abstract suspend fun getPrunableCropPaths(olderThan: Long): List<String>

    @Query("DELETE FROM observation_table WHERE sync_state = 'SYNCED' AND device_timestamp < :olderThan")
    abstract suspend fun pruneSynced(olderThan: Long)
}
```

`onConflict = IGNORE` on the unique `idempotency_key` makes inserts idempotent if the capture path runs twice for the same logical reading (see "Idempotency" — the key is deterministic, so a true re-process collides and is silently ignored). **Room returns the sentinel `-1L` for an ignored (conflicting) insert** rather than a real rowId; the repository must treat `-1L` as "already recorded" and not propagate it as a new id. `getPrunableCropPaths` + `pruneSynced` keep the append-only local store bounded after successful upload (the cloud is the source of truth post-sync) — crop paths are read first so the files can be deleted before the rows vanish.

The repository binds the domain model to the DAO, owns the bitmap interaction, and provides the fire-and-forget entry point the (non-suspend) listener calls. It is provided as a `@Binds @Singleton` interface in `core/capture/.../di/Hilt.kt` (`@Module @InstallIn(SingletonComponent::class)`), matching the `DumbModule` pattern.

```kotlin
interface ObservationRepository {
    /** Suspend insert. Returns the new rowId, or null if the insert was a no-op dedup (DAO returned -1). */
    suspend fun record(observation: Observation): Long?

    /**
     * Fire-and-forget variant for the detection-loop callback. Launches [record] on the
     * repository's injected IO scope so the detection loop is never blocked by DB/bitmap I/O.
     */
    fun recordAsync(observation: Observation)

    suspend fun pending(limit: Int): List<Observation>
    suspend fun markSynced(ids: List<Long>)
    fun observeForScenario(scenarioId: Long): Flow<List<Observation>>
}
```

The implementation owns a single fire-and-forget scope, built from the injected `@Dispatcher(IO)` dispatcher (the established pattern — e.g. `SettingsRepositoryImpl` launches toggles on `CoroutineScope(ioDispatcher + SupervisorJob())`):

```kotlin
@Singleton
class ObservationRepositoryImpl @Inject constructor(
    private val observationDao: ObservationDao,
    private val bitmapRepository: BitmapRepository,
    @Dispatcher(IO) private val ioDispatcher: CoroutineDispatcher,
) : ObservationRepository {

    private val scope = CoroutineScope(ioDispatcher + SupervisorJob())

    override suspend fun record(observation: Observation): Long? =
        observationDao.insert(observation.toEntity()).takeIf { it != -1L }   // -1 == ignored dedup

    override fun recordAsync(observation: Observation) {
        scope.launch { record(observation) }   // SupervisorJob: one failed insert can't kill the scope
    }
    // pending/markSynced/observeForScenario + prune (which deletes crop files first) elided
}
```

This resolves the prior `recordAsync`-vs-`record` mismatch: `recordAsync` is a real interface method, and the suspend `record` it wraps is invoked on a named, owned IO scope rather than hand-waved.

### The capture listener

A new `ObservationCaptureListener : SmartProcessingListener` is the glue. It is injected into the processing pipeline as the `progressListener` (the same slot the facts describe for `DetectorEngine`/`ScenarioProcessor`). In tracking mode (cloud flavor, headless scenario started by `core:scheduling`), this listener is wired in; in the legacy auto-clicker path it is not, so it adds zero overhead to existing behavior.

The critical correctness points, all reflecting reviewer feedback: (1) `detectionArea` is **not** a member of the `ScreenCondition` base class — it is declared per-subtype, so it must be extracted *inside* the exhaustive `when` where the smart-cast applies; (2) the `Observation.id` uses the temporary-Identifier constructor, never `Identifier(databaseId = 0L)`; (3) persistence is dispatched via `repository.recordAsync`, defined above.

```kotlin
class ObservationCaptureListener(
    private val repository: ObservationRepository,
    private val cropCapturer: ObservationCropCapturer,   // wraps screen-frame access + scaling, see below
    private val cropCaptureEnabled: () -> Boolean,        // from SettingsRepository toggle
    private val scenarioIdProvider: () -> Long,           // running scenario id
) : SmartProcessingListener {

    override fun onScreenConditionProcessingCompleted(result: ProcessedConditionResult.Screen) {
        val condition = result.condition

        // detectionArea is per-subtype (Color/Number/Text: non-null Rect; Image: nullable Rect?),
        // so it is extracted inside the when alongside valueType/text/number under smart-cast.
        data class Extracted(
            val valueType: ObservationValueType,
            val text: String?,
            val number: Double?,
            val detectionArea: Rect,
        )

        val extracted = when (condition) {
            is ScreenCondition.Number ->
                Extracted(ObservationValueType.NUMBER, null, result.capturedNumber, condition.detectionArea)
            is ScreenCondition.Text ->
                Extracted(ObservationValueType.TEXT, result.capturedText, null, condition.detectionArea)
            is ScreenCondition.Color ->
                Extracted(ObservationValueType.STATE, null, null, condition.detectionArea)
            is ScreenCondition.Image ->
                return   // out of scope: legacy auto-clicker only
        }

        val cropPath: String? =
            if (cropCaptureEnabled()) cropCapturer.capture(extracted.detectionArea) else null

        // Verification timestamp (threaded onto the result) is the canonical capture time and
        // the basis of the deterministic idempotency key. Fall back to listener time only if absent.
        val tsMs = result.verificationTsMs ?: System.currentTimeMillis()
        val scenarioId = scenarioIdProvider()

        repository.recordAsync(
            Observation(
                id = Identifier(id = nextTempId(), asTemporary = true),   // NOT Identifier(databaseId = 0L)
                scenarioId = Identifier(databaseId = scenarioId),
                eventId = condition.eventId,
                conditionId = condition.id,
                valueType = extracted.valueType,
                capturedValueText = extracted.text,
                capturedValueNumber = extracted.number,
                haveBeenDetected = result.haveBeenDetected,
                confidence = result.confidenceRate,
                isFulfilled = result.isFulfilled,
                screenPosition = result.position,
                screenSize = result.size,
                screenshotCropPath = cropPath,
                deviceTimestamp = tsMs,
                idempotencyKey = idempotencyKeyOf(scenarioId, condition.id.databaseId, tsMs),
                syncState = ObservationSyncState.PENDING,
            )
        )
    }
}
```

`nextTempId()` is a monotonic counter (or `Identifier`'s usual temp-id source); its only job is to satisfy the `Identifier` invariant for a not-yet-inserted row — the value is discarded when Room assigns the real autogenerated id.

#### Idempotency: deterministic key, not a random UUID

The prior sketch generated `UUID.randomUUID()` per listener call, which **never collides** — meaning the unique index could never dedup a re-processed reading, undercutting the very guarantee it claimed. Fixed: the idempotency key is **derived deterministically** from the stable coordinates of a logical reading:

```kotlin
private fun idempotencyKeyOf(scenarioId: Long, conditionId: Long, verificationTsMs: Long): String =
    "$scenarioId:$conditionId:$verificationTsMs"   // or a hash thereof; stable across retries
```

Because `verificationTsMs` comes from `ConditionsVerifier.currentVerificationTsMs` (captured once at verify-loop start, identical for all conditions in that loop), re-processing the *same* reading — e.g. a retry inside the poll cycle — produces the *same* key and is silently ignored by `@Insert(onConflict = IGNORE)`. This is the on-device half of the zero-duplicate contract gate, now actually functional. It is also why `verificationTsMs` propagation is a hard prerequisite (above) rather than an optional refinement: `deviceTimestamp` and the key share that one stable timestamp. (If `verificationTsMs` is unexpectedly null, the listener falls back to `System.currentTimeMillis()`, which weakens dedup for that single row but does not crash — acceptable degradation given the ≥5s polling intervals.)

### Screenshot crops via the existing bitmap storage — with correct coordinate space

Crops reuse `BitmapRepository` verbatim — no new storage layer. From `/Users/Sophia/Documents/GitHub/AutoClicker/core/common/bitmaps/.../BitmapRepository.kt`:

```kotlin
suspend fun saveImageConditionBitmap(bitmap: Bitmap, prefix: String): String
suspend fun getImageConditionBitmap(path: String, width: Int, height: Int): Bitmap?
suspend fun deleteImageConditionBitmaps(paths: List<String>)
```

The contract mandates the `"Observation_"` prefix; `ConditionBitmapsDataSource` names files `{prefix}{bitmapIdentifier}.png` (identifier = pixel `hashCode()`, PNG quality 100) in `context.filesDir`.

**Coordinate-space correction (must-fix).** The earlier draft cropped the screen frame using the domain `condition.detectionArea` and justified it as "matches ImageDetector input space." That is wrong and conflates two spaces:

- The domain `ScreenCondition.*.detectionArea` is the **user-configured screen-space** rect.
- `ConditionsVerifier` does **not** pass that rect to the detector; it passes `conditionScalingInfo.detectionArea` — the **scaled-down detection-space** rect from `ScreenConditionScalingInfo` (built by `ScalingManager.startScaling`, which computes `scalingRatio` from `detectionQuality` vs display size).
- The frame available to crop from (`DisplayRecorder`'s acquired bitmap, the same frames `DetectorEngine.processScreenImages()` feeds to `scenarioProcessor.process()`) is at **detection/scaled resolution**, not full screen resolution.

So cropping a detection-resolution frame with a screen-space rect produces a mis-sized, mis-offset crop. The fix is to crop in the frame's own coordinate space. The crop helper therefore obtains the **scaled** rect via `ScalingManager` (`getScreenConditionScalingInfo(condition).detectionArea`, which the facts state "matches ImageDetector's input space"), and clamps it to the frame bounds:

```kotlin
// ObservationCropCapturer — injected with the live frame provider and the ScalingManager.
class ObservationCropCapturer(
    private val screenFrameProvider: () -> Bitmap?,   // latest DisplayRecorder frame (detection-space)
    private val scalingInfoProvider: (ScreenCondition) -> ScreenConditionScalingInfo?,
    private val bitmapRepository: BitmapRepository,
) {
    suspend fun capture(condition: ScreenCondition): String? {
        val frame = screenFrameProvider() ?: return null
        // Use the detection-space rect (same space as the frame), NOT the screen-space domain rect.
        val area = scalingInfoProvider(condition)?.detectionArea ?: return null
        val r = area.clampedTo(frame.width, frame.height) ?: return null
        val crop = Bitmap.createBitmap(frame, r.left, r.top, r.width(), r.height())
        return bitmapRepository.saveImageConditionBitmap(crop, prefix = "Observation_")
    }
}
```

(The listener passes the `condition` to the capturer so the capturer can resolve the scaling info; the inline `capture(detectionArea)` call shown earlier is shorthand for this.) If a full-screen-resolution crop is desired for dashboard fidelity instead, the alternative is to scale the screen-space rect *up* by the inverse ratio and crop a full-resolution capture — but the detection-space frame is what is actually in hand at this seam, so cropping in detection space is the grounded MVP choice; the dashboard renders whatever resolution we store.

The returned path is stored in `ObservationEntity.screenshot_crop_path`. `core:network` later reads the bitmap back via `getImageConditionBitmap(path, w, h)` to upload, populating the cloud `cropUrl`; the dashboard's crop viewer renders it (acceptance: "*Crop, when enabled, is stored on-device, uploaded, and viewable per Observation*"). Because crop files share the `BitmapRepository` namespace and `BitmapLRUCache` (keyed `key:IMAGE_CONDITION:{path}:{w}:{h}`), the `"Observation_"` prefix keeps them distinguishable from `"Condition_"` images for any future GC pass; the backup regex `[0-9]+/Condition_-?[0-9]+\.png` will *not* match `Observation_` files, so crops correctly stay out of legacy scenario backups (they are cloud-synced, not exported).

Cleanup: `pruneSynced` first reads the prunable rows' crop paths via `getPrunableCropPaths(olderThan)`, calls `bitmapRepository.deleteImageConditionBitmaps(paths)`, then deletes the rows — so crop PNGs don't accumulate on disk after their Observations are confirmed in the cloud.

### Module wiring summary

- **Physical location of Room artifacts**: `ObservationEntity`, `ObservationDao`, and `Migration21to22` live in **`core:smart:database`** (Room requires entities, DAOs, and the `@Database`/`ScenarioDatabase` declaration co-located in one module). `ClickDatabase` gains the entity in its `@Database(entities = [...])` list; `ScenarioDatabase` (the abstract base `ClickDatabase` extends) gains `abstract fun observationDao(): ObservationDao`.
- **Physical location of `core:capture`**: the `Observation` domain model, `ObservationValueType`/`ObservationSyncState`, `ObservationRepository`/`ObservationRepositoryImpl`, `ObservationCaptureListener`, and `ObservationCropCapturer` live in `core:capture`.
- **Dependencies**: `core:capture` depends on `core:smart:database` (for the `ObservationDao` it consumes and the entity/migration it relies on), `core:common:bitmaps` (`BitmapRepository`), `core:smart:processing` (`SmartProcessingListener`, `ProcessedConditionResult.Screen`, `ScreenConditionScalingInfo`/`ScalingManager` access), `core:smart:domain` (`ScreenCondition`), and `core:common:base` (`Identifier`, `@Dispatcher(IO)`).
- **DI**: `core/capture/.../di/Hilt.kt` provides `ObservationRepository` (`@Binds @Singleton` → `ObservationRepositoryImpl`) and the `ObservationCaptureListener` factory; the impl injects `ObservationDao` (from the database module), `BitmapRepository`, and `@Dispatcher(IO)`.
- **Flavor gating**: the listener only participates when a tracking session runs, which only happens in the `cloud` flavor. `core:capture` itself is flavor-neutral (pure persistence), but its wiring into the running pipeline is driven by `core:scheduling` + `feature:cloud`, both linked only in `cloud` per the contract's flavor strategy — so `fDroid`/`playStore` `local` builds never instantiate it.

---

## Vision: OCR match -> value extraction

Today the on-device OCR pipeline answers a yes/no question: "does the text in this region match the condition's expected string (or satisfy a numeric comparison)?" For TraxIntel we need it to answer a *what* question: "what string/number is currently in this region, and how confident are we?" This section specifies the changes — Kotlin, the JNI bridge, and the C++ result plumbing — required to promote OCR/number detection from a match-gate to a value-extraction primitive, and defines how that extracted value is carried up to the `onScreenConditionProcessingCompleted` capture hook where an Observation is minted.

The MVP scope contract names this explicitly: *"A targeted JNI change to surface the recognized OCR text string to Kotlin (extend `toJniResult` marshalling beyond the current 7-element `DoubleArray`) so Text Observations carry the actual value."* This section is the concrete design for that line.

### What the pipeline returns today (ground truth)

The recognized string already exists in C++ but is discarded at the matching boundary, and — critically — **nothing extracted is forwarded to the capture hook today for either path**. The data path is:

1. `ImageDetector.detectText(conditionText, recognitionModelId, detectionArea, threshold): DetectionResult` and `ImageDetector.detectNumber(detectionArea, threshold): DetectionResult` (in `core/smart/detection/.../ImageDetector.kt`).
2. These call the JNI externals `detectTextNative(...)` / `detectNumberNative(...)` in `NativeDetector.kt`, both declared `: DoubleArray?`.
3. Native side, `TextMatcher::matchText` / `matchNumber` run OCR, populate a `TextRecognizerResult { cv::Rect boundingBox; std::string text; float confidence; }`, fuzzy-match `text` against `conditionText` (`bestSubstringSimilarity`), and produce a `TextMatchingResult` (subclass of `DetectionResult`). For numbers, `text` is parsed via `stringToDouble()` into `recognizedNumber`.
4. `toJniResult(JNIEnv*, DetectionResult*)` in `jni_detection_result.cpp` marshals a fixed **7-element** `jdoubleArray`:
   `[detected(0|1), centerX, centerY, width, height, confidence, recognizedNumber]`.
   The number lands in slot `[6]`. The C++ sentinel for "not a number" is `std::numeric_limits<double>::lowest()` (`jni_detection_result.cpp` line 26; `text_matching_result.hpp` line 28) — the *most-negative* finite double, ~`-1.8e308`.
5. `DoubleArray?.toDetectionResult()` in `DetectionResult.kt` (line 38) reconstructs the typed result, mapping slot `[6]` to `numberDetected: Double?`.

Two facts must be stated precisely, because the rest of the design depends on them.

**(a) The recognized string is never marshalled.** `TextRecognizerResult.text` — the actual recognized string — is never written into the result array. Only the post-`stringToDouble` numeric value survives, and only for the number path. For Text conditions, the OCR string is consumed by `bestSubstringSimilarity` and dropped.

**(b) The parsed number is *not* surfaced at the hook today, either.** In `verifyNumberCondition` (`ConditionsVerifier.kt` lines 189–230) the local `detectionResult.numberDetected` is used **only** for the comparison that decides `isFulfilled`. `ProcessedConditionResult.Screen` has no number field, so the parsed value is consumed inside the verify method and is *not* present on the `Screen` object delivered to `onScreenConditionProcessingCompleted`. So today: `detectText` yields a match boolean + confidence + position; `detectNumber` yields the same plus an internally-used `Double` that the listener never sees. Neither path delivers the raw recognized string or any extracted value to the hook.

**(c) There is a latent sentinel bug we must not propagate.** The current Kotlin converter (`DetectionResult.kt` line 47) compares `numberDetected == Double.MIN_VALUE`. On the JVM, `Double.MIN_VALUE` is the smallest *positive* double (~`4.9e-324`), which never equals the C++ `lowest()` sentinel (~`-1.8e308`). So the "not a number" check is effectively dead today; the bug is masked only because `verifyNumberCondition` independently gates on `detectionResult.isDetected`. For value-extraction, where a genuine "OCR ran but produced no parseable number" reading is a first-class outcome, we must not copy this comparison. The fix below sidesteps slot `[6]` entirely for the typed string result.

That is exactly what value-extraction needs to fix.

### Step 1 — Surface the recognized string across the JNI boundary (UTF-8 safe)

A `jdoubleArray` cannot carry a string, so the 7-element contract must change shape for the two text-bearing natives. We choose the option with the smallest blast radius on the existing `detectImage`/`detectColor` callers.

**Chosen approach: change the JNI return type from `jdoubleArray` to a small `jobject` result wrapper, but only for the two text-bearing natives.** `detectColorNative` and `detectImageNative` keep returning `jdoubleArray` (they have no string to carry), so the legacy `DoubleArray?.toDetectionResult()` stays valid for them. We introduce a parallel marshaller for the text path.

**UTF-8 safety is a correctness blocker, not a detail.** The whole point of supporting CYRILLIC/ARABIC/CJK alphabets is that `TextRecognizerResult.text` is raw UTF-8 from the OCR recognizer. `JNIEnv::NewStringUTF` expects *modified* UTF-8, not standard UTF-8: 4-byte sequences (supplementary-plane CJK) and embedded NUL bytes are mishandled or truncate the string, and malformed input can abort the VM. We therefore **never** pass the OCR string through `NewStringUTF`. Instead we return the bytes as a `jbyteArray` and decode them on the Kotlin side with an explicit `Charsets.UTF_8` decoder, which tolerates the full Unicode range.

Define a JNI-visible Kotlin holder in the detection module. Note the `metrics: DoubleArray` member: Kotlin generates structural `equals`/`hashCode` that compare `DoubleArray` by reference for a `data class`, which the compiler warns about. Because this type is a transient, single-use JNI carrier that is converted to `DetectionResult` immediately and never compared or used as a map key, the default identity-based equality is acceptable; document this so the warning is understood, not silenced blindly.

```kotlin
// core/smart/detection/.../NativeTextResult.kt
/**
 * Transient JNI carrier produced by detectTextNative / detectNumberNative.
 * Equality is intentionally the default (identity) — this object is converted
 * to DetectionResult immediately and never compared or used as a key.
 * The DoubleArray member triggers a benign data-class equals/hashCode warning.
 */
internal data class NativeTextResult(
    val metrics: DoubleArray,        // same 7-slot layout as today
    val recognizedTextUtf8: ByteArray?, // raw OCR bytes (UTF-8), null when nothing recognized
)
```

New native signatures in `NativeDetector.kt`:

```kotlin
private external fun detectTextNative(
    conditionText: String, recognitionModelId: String,
    x: Int, y: Int, width: Int, height: Int, threshold: Int,
): NativeTextResult?

private external fun detectNumberNative(
    x: Int, y: Int, width: Int, height: Int, threshold: Int,
): NativeTextResult?
```

C++ side, add a string-aware marshaller alongside the existing `toJniResult`, leaving the latter untouched for color/image:

```cpp
// jni_detection_result.cpp
jobject toJniTextResult(JNIEnv *env, DetectionResult* result) {
    if (result == nullptr) return nullptr;

    jdoubleArray metrics = toJniResult(env, result); // reuse the 7-slot packer (slot[6] still the legacy number)

    jbyteArray recognizedBytes = nullptr;
    auto* textResult = dynamic_cast<TextMatchingResult*>(result);
    if (textResult != nullptr && textResult->hasRecognizedText()) {
        const std::string& s = textResult->getRecognizedText();
        recognizedBytes = env->NewByteArray(static_cast<jsize>(s.size()));
        env->SetByteArrayRegion(
            recognizedBytes, 0, static_cast<jsize>(s.size()),
            reinterpret_cast<const jbyte*>(s.data())); // raw bytes, no UTF-8 re-encoding
    }

    // gNativeTextResultClass / gNativeTextResultCtor are cached globals (see below)
    return env->NewObject(gNativeTextResultClass, gNativeTextResultCtor, metrics, recognizedBytes);
}
```

The Kotlin converter sidesteps the slot-`[6]` sentinel bug entirely by parsing from the raw string (see Step 6); the JVM-side number from slot `[6]` is treated as advisory only and never compared against `Double.MIN_VALUE`:

```kotlin
// DetectionResult.kt
data class DetectionResult(
    val isDetected: Boolean = false,
    val confidenceRate: Double = 0.0,
    val position: Point = Point(),
    val size: Point = Point(),
    val numberDetected: Double? = null, // legacy comparison input; populated by normalizeNumeric, see Step 6
    val textDetected: String? = null,   // NEW — raw OCR string, UTF-8-decoded
)

internal fun NativeTextResult?.toDetectionResult(): DetectionResult {
    if (this == null || metrics.size < 7) return DetectionResult()
    val text = recognizedTextUtf8?.toString(Charsets.UTF_8)
    return DetectionResult(
        isDetected = metrics[0] > 0.5,
        position = Point(metrics[1].toInt(), metrics[2].toInt()),
        size = Point(metrics[3].toInt(), metrics[4].toInt()),
        confidenceRate = metrics[5],
        numberDetected = normalizeNumeric(text), // Kotlin parse, NOT slot[6]; see Step 6
        textDetected = text,
    )
}
```

This requires the C++ `TextMatchingResult` (`text_matching_result.hpp`) to retain the raw recognized string, reconciled with its existing mutation path. Today the result is populated only via `updateResults(detectionArea, boundingBox, confidence, numberRecognized)` (lines ~38–42) and cleared via `reset()`. A free-standing setter that `reset()` does not clear would let a stale string from a previous frame leak into a later result in the polling loop. We therefore (i) extend `updateResults` to take the recognized string as its single source of truth, and (ii) clear the flag in `reset()`:

```cpp
// text_matching_result.hpp additions
private:
    std::string recognizedText;
    bool recognizedTextSet = false;
public:
    // extend the EXISTING population path so text is set atomically with the rest
    void updateResults(const cv::Rect& area, const cv::Rect& box, double conf,
                       double numberRecognized, const std::string& text) {
        // ... existing assignments ...
        recognizedText = text;
        recognizedTextSet = true;
    }
    void reset() {
        // ... existing resets ...
        recognizedText.clear();
        recognizedTextSet = false;     // prevents cross-frame leakage
    }
    const std::string& getRecognizedText() const { return recognizedText; }
    bool hasRecognizedText() const { return recognizedTextSet; }
```

In `text_matcher.cpp`, both `matchText` (which already has `recognizerResult.text` for `bestSubstringSimilarity`) and `matchNumber` (which feeds `recognizerResult.text` to `stringToDouble`) pass `recognizerResult.text` into the extended `updateResults`. For `matchNumber`, this preserves the **raw, pre-parse** string, which becomes the single source of truth for "value was read but is not parseable": `getRecognizedNumber()` returns the C++ `lowest()` sentinel (not null) when `stringToDouble` fails, so the raw string is the only reliable signal that OCR produced characters at all. `isFulfilled` derivation for that case is specified in Step 2.

Two practical JNI notes: cache the `NativeTextResult` `jclass` and constructor `jmethodID` as globals (`NewGlobalRef` at `init()`) to avoid a `FindClass` per frame in the polling loop; and pass `nullptr` (not an empty `jbyteArray`) when nothing is recognized so the Kotlin side gets a clean `null` rather than `""`. The contract's out-of-scope list keeps number detection on the first-loaded recognition model, so `matchNumber`'s use of `defaultRecognitionModelId` (`text_matcher.hpp`) is unchanged.

### Step 2 — Carry the extracted value up the processing pipeline

`ProcessedConditionResult` is a **`sealed class`**, and `Screen` is a `data class Screen(...) : ProcessedConditionResult()` (note the parentheses — it extends the sealed class, it does *not* implement an interface; see `ProcessedConditionResult.kt` lines 24, 37, 44). Today it carries no extracted value:

```kotlin
sealed class ProcessedConditionResult {
    abstract val isFulfilled: Boolean

    data class Screen(
        override val isFulfilled: Boolean,
        val haveBeenDetected: Boolean,
        val condition: ScreenCondition,
        val confidenceRate: Double,
        val position: Point?,
        val size: Point?,
    ) : ProcessedConditionResult()
}
```

Extend it with an optional, type-tagged extracted value. The carrier is a sealed *interface* (that part of the original design is fine); the enclosing `Screen` still extends the sealed *class* with parentheses:

```kotlin
sealed interface ExtractedValue {
    data class Num(val value: Double) : ExtractedValue
    data class Txt(val value: String) : ExtractedValue
    data class ColorState(val present: Boolean) : ExtractedValue
}

data class Screen(
    override val isFulfilled: Boolean,
    val haveBeenDetected: Boolean,
    val condition: ScreenCondition,
    val confidenceRate: Double,
    val position: Point?,
    val size: Point?,
    val extracted: ExtractedValue? = null,   // NEW
) : ProcessedConditionResult()   // sealed class, parens — NOT an interface
```

The doc-comment block above `Screen` must also gain a `@param size` line and an `@param extracted` line; it currently documents `haveBeenDetected`, `condition`, `confidenceRate`, and `position` only.

The default `null` keeps all legacy auto-clicker call sites (Color/Image) compiling unchanged, including `toInvalidConditionResult()`.

**The reviewer's core control-flow point:** simply adding `extracted = ...` to the success branch is not enough. `verifyNumberCondition` currently *early-returns* `condition.toInvalidConditionResult()` whenever `!detectionResult.isDetected || numberDetected == null` (lines 203, 259–267). That invalid path produces `haveBeenDetected=false`, `confidenceRate=0.0`, `position=null`, and no extracted value — so a failed or low-confidence number read would carry neither confidence nor the raw OCR string, defeating acceptance criterion #3 (*"failed reads still record an Observation with `isFulfilled=false` and a confidence value"*). We must **restructure** the branch so the failure case still emits a populated `Screen`, not the invalid sentinel. The same principle applies to Text and Color: the value (and confidence) is populated **regardless of `isFulfilled`**, because for a tracking scenario a "non-match" is still a valid reading.

Restructured `verifyNumberCondition` (the scaling-info lookup at the top may still legitimately return the invalid result, since a missing scaling region is a true setup error, not a failed read):

```kotlin
private fun verifyNumberCondition(condition: ScreenCondition.Number): ProcessedConditionResult.Screen {
    progressListener?.onScreenConditionProcessingStarted()

    val conditionScalingInfo = scalingManager
        .getScreenConditionScalingInfo(condition) as? ScreenConditionScalingInfo.Number
        ?: return condition.toInvalidConditionResult()   // genuine setup error: keep invalid path

    val detectionResult = imageDetector.detectNumber(
        detectionArea = conditionScalingInfo.detectionArea,
        threshold = condition.threshold,
    )

    val number = detectionResult.numberDetected   // parsed in Kotlin (Step 6), null if unparseable
    val comparison = condition.comparisonOperation // nullable now; null => extract-only (Step 3)

    val isFulfilled = when {
        comparison == null -> number != null       // extract-only: "value was recognized"
        !detectionResult.isDetected || number == null -> false
        else -> compare(number, comparison, resolveOperand(condition))
    }

    // ALWAYS emit a populated Screen — confidence + raw value survive even on a failed read.
    val result = ProcessedConditionResult.Screen(
        isFulfilled = isFulfilled,
        haveBeenDetected = detectionResult.isDetected,
        condition = condition,
        confidenceRate = detectionResult.confidenceRate,
        position = detectionResult.position.takeIf { detectionResult.isDetected }
            ?.let(scalingManager::scaleUpDetectionResult),
        size = detectionResult.size.takeIf { detectionResult.isDetected }
            ?.let(scalingManager::scaleUpDetectionResult),
        extracted = number?.let(ExtractedValue::Num),   // null when OCR produced no parseable number
    )

    progressListener?.onScreenConditionProcessingCompleted(result)
    return result
}
```

(`compare`/`resolveOperand` factor out the existing `when (comparisonOperation)` and `CounterOperationValue` resolution at lines 205–216.) The "text read but `stringToDouble`/`normalizeNumeric` failed" case is handled naturally: `number == null` but `detectionResult.textDetected` is non-null, so for a Number tracking read `isFulfilled=false` while the raw string still travels up via the Text-less Number path — the Observation builder can persist the raw string for debugging even though no numeric value was charted.

`verifyTextCondition` (line 232) sets `extracted = detectionResult.textDetected?.let(ExtractedValue::Txt)` and keeps emitting a `Screen` for empty reads (it already does not early-return). `verifyColorCondition` (line 127) sets `extracted = ExtractedValue.ColorState(detectionResult.isDetected)`. In all three, `extracted` is populated on every emitted `Screen`.

**Delivering the timestamp to the Observation builder.** The hook signature today is `onScreenConditionProcessingCompleted(result: ProcessedConditionResult.Screen)`. The verification timestamp lives in `ConditionsVerifier.currentVerificationTsMs` (line 53), which is a **private** field and is *not* passed to the listener — so there is currently no mechanism to deliver `deviceCapturedAt`. We close this by extending the hook to pass the timestamp explicitly:

```kotlin
// SmartProcessingListener
fun onScreenConditionProcessingCompleted(
    result: ProcessedConditionResult.Screen,
    deviceCapturedAtMs: Long,   // NEW — sourced from currentVerificationTsMs
)
```

Each call site (lines 149, 185, 228, 255) passes `currentVerificationTsMs ?: System.currentTimeMillis()`. The Observation builder in `core:observation` then has the full tuple: `value` from `extracted`, `valueType` from the `ExtractedValue` variant, `confidence` from `confidenceRate`, `isFulfilled` from the flag, and `deviceCapturedAt` from the new parameter. `scaleUpDetectionResult()` (`ScalingManager`) still applies to `position`/`size`; the extracted *value* itself needs no scaling.

### Step 3 — The tracking condition: extend Text/Number rather than fork

The contract specifies reusing `ScreenCondition.Number/Text/Color` and `ConditionsVerifier` for the read-only mode, with read types Number, Text, Color-State only. Rather than a fully new condition subtype (which would churn `ConditionType`, `ConditionEntity`, the mappers, and `Condition.copyWithNewId()`), value extraction becomes a property of how an existing condition is processed.

Add one additive marker to `Text` and make `Number`'s comparison optional:

```kotlin
// ScreenCondition.Text — add:
val extractOnly: Boolean = false             // true => surface value, ignore text-match gating
// ScreenCondition.Number — make comparison nullable for extract-only:
val comparisonOperation: ComparisonOperation? = null
```

`verifyTextCondition`/`verifyNumberCondition` branch on `extractOnly`/null-comparison to decide `isFulfilled`, but always populate `extracted` (Step 2). This honors the out-of-scope constraints (one `detectionArea` per condition; per-Text-condition alphabet).

**Domain ↔ DB ↔ backup round-trip (the gap the reviewer flagged).** Per the codebase recipe, *any* `ScreenCondition` field change touches three more places beyond the entity columns; omitting them fails to compile or silently drops the fields on persistence:

1. **`ConditionEntity`** (`core/smart/database/.../entity/ConditionEntity.kt`): add two nullable `@ColumnInfo` columns — `extract_only: Boolean?` and (since `comparisonOperation` is now optional) the existing comparison column must already be nullable or be made so. No new `ConditionType` value.
2. **`ConditionMapper`** (entity ↔ domain): map `extractOnly` both directions, defaulting `entity.extractOnly ?: false` on read; map the now-nullable `comparisonOperation` through without forcing a non-null default (a `null` column means extract-only).
3. **`Condition.copyWithNewId()`**: its `when(...)` branch for `ScreenCondition.Text` and `.Number` must copy the new/changed fields, or duplication/clone will drop them.
4. **Backup compatibility.** Making `comparisonOperation` nullable is **not** purely additive for existing serialized `ScenarioBackup` payloads: older backups serialized a non-null comparison, and the domain field changing nullability affects deserialization. `ScenarioBackup` is versioned (`version: Int`) and `ScenarioSerializer.deserialize()` dispatches through `DeserializerFactory.create(version)`. The plan: bump the backup payload `version`, and in the deserializer for the *new* version treat a missing `comparisonOperation` as `null` (extract-only) while the deserializer for *older* versions keeps populating the previously-required value. `extractOnly` defaults to `false` for any payload lacking it. This keeps old backups importable and new backups round-trip-safe.

**Database migration (decided, not hand-waved).** `DATABASE_VERSION` is currently **21** (`DatabaseInfo.kt`) and must be bumped to **22**, registered in `ClickDatabase`. Two changes ship together at 21 → 22: (a) the additive nullable columns on `ConditionEntity`, and (b) the new Observation entity (specified in its own section). Per the recipe, *adding nullable columns* alone could be an `AutoMigration`, but *introducing a new entity that must also be created (and indexed)* is a normal manual `Migration(21, 22)`. Bundling both into a single auto-migration is not viable because the Observation table creation and its foreign-key/index setup exceed what `AutoMigration` infers reliably alongside an unrelated table's column adds. **Decision: ship 21 → 22 as a manual `Migration(21, 22)`** (pattern of `Migration19to20`), doing `alterTableAddColumn` for `extract_only`/`comparison_operation` on `CONDITION_TABLE` and `CREATE TABLE` + indices for the Observation entity in one `migrate()`; it is therefore **not** added to the `autoMigrations` list.

### Step 4 — Detection-area selection UX hooks

The region a user marks is a screen-space `Rect`, and the pipeline already has the coordinate machinery: `ScalingManager.getScreenConditionScalingInfo(condition)` returns a `ScreenConditionScalingInfo` whose `detectionArea` is the condition's region mapped into the `ImageDetector` input space, and `scaleUpDetectionResult()` maps detected positions back to screen space. Value extraction reuses the *same* `detectionArea: Rect` field that Color/Image/Number/Text conditions already expose — no new geometry.

UX hooks for marking the region:
- **On-device authoring** reuses the existing overlay region-selector that already produces a `Rect` for Color/Image conditions; for tracking, that `Rect` is written into `ScreenCondition.Text/Number.detectionArea`.
- **Dashboard authoring** (TrackingScenario) ships a `detectionArea` Rect in *device screen-space*; because `ScalingManager` recomputes the scaling ratio from `detectionQuality` vs. the live display size at `startScaling()`, a region authored on one device resolution still maps correctly on another. Large aspect-ratio differences should surface the same `screenCompatWarning` pattern the backup engine uses for dimension mismatch.
- A **tight region is the single biggest accuracy lever**: OCR confidence and correctness degrade sharply when the crop includes adjacent glyphs, borders, or partial characters. The authoring UI should encourage a snug box around just the value. The optional crop is saved via `BitmapRepository.saveImageConditionBitmap(bitmap, "Observation_")`, letting the operator visually confirm the captured pixels.

### Step 5 — Confidence thresholds

Two distinct confidences exist and must not be conflated:
- **OCR/recognition confidence** — `TextRecognizerResult.confidence`, surfaced as `DetectionResult.confidenceRate` (slot `[5]`), 0–100. "How sure is the model that it read these glyphs correctly."
- **Match confidence** — the fuzzy-similarity score against `conditionText`. For extract-only tracking this is irrelevant; for gated Text reads it drives `isFulfilled` via `threshold`.

For Observations we record the **recognition** confidence as `Observation.confidence`. The contract requires low-confidence points be *distinguishable* on the dashboard, not dropped — so the on-device pipeline must **not** hard-filter by confidence. Every poll produces an Observation; a low-confidence read is recorded with its true (low) confidence and `isFulfilled=false`, and the dashboard renders it differently. The condition's `threshold` continues to gate `isFulfilled` for gated Text reads that specify an expected string, but never suppresses value capture. A sensible default surfaced in authoring (e.g. flag readings below ~60/100) is a dashboard concern, not a capture-time drop.

### Step 6 — Accuracy and edge cases: fonts, locales, number normalization

The recognized string is only as good as the OCR model. Because we now carry the raw, pre-parse UTF-8 string to Kotlin (`textDetected`), normalization moves to a dedicated Kotlin step owned by the Observation builder.

- **Fonts & rendering.** Stylized game fonts, thin/condensed numerals, and anti-aliased glyphs on busy backgrounds are the dominant error source. There is no model knob in MVP (number reads use the first-loaded recognition model, per contract); the mitigation is region tightness (Step 4) plus surfacing confidence so bad reads are visible rather than silently wrong.
- **Number normalization — down-scoped for MVP.** The previously-sketched `normalizeNumeric()` was buggy: a regex like `[,.](?=\d{3}\b)` strips a decimal point before a 3-digit fraction (so `1.234` meaning *one point two-three-four* corrupts to `1234`), and a blanket `replace(',', '.')` mishandles US `1,234.56`. Locale of US vs EU grouping/decimal cannot be disambiguated from digits alone. For MVP we therefore **do not** attempt full locale-aware parsing in the capture path. Instead:
  - **Always preserve the raw string** in the Observation (`value` for Text reads, and a raw-string field retained for Number reads), so nothing is ever silently corrupted and mis-parses are debuggable after the fact (append-only Observations, no back-fill).
  - **Parse conservatively**, integer-first, stripping only unambiguous decorations:

    ```kotlin
    /** MVP-conservative parse. Returns null when ambiguous; raw string is always retained. */
    fun normalizeNumeric(raw: String?): Double? {
        if (raw == null) return null
        val cleaned = raw.trim()
        val multiplier = when (cleaned.lastOrNull()?.uppercaseChar()) {
            'K' -> 1_000.0; 'M' -> 1_000_000.0; 'B' -> 1_000_000_000.0; else -> 1.0
        }
        // strip a trailing magnitude/percent suffix and ASCII grouping spaces only
        val body = cleaned
            .trimEnd('K', 'k', 'M', 'm', 'B', 'b', '%', ' ')
            .replace(" ", "")   // thin-space grouping (U+202F/U+2009)
        // Accept only an unambiguous single-separator form; reject mixed ','/'.' to avoid US/EU corruption.
        val hasComma = ',' in body
        val hasDot = '.' in body
        val canonical = when {
            hasComma && hasDot -> return null          // ambiguous: keep raw string, no numeric value
            hasComma -> body.replace(",", "")          // treat lone comma as grouping (integer-first)
            else -> body                                // lone dot kept as decimal point
        }
        return canonical.toDoubleOrNull()?.times(multiplier)
    }
    ```

    This deliberately returns `null` (recording a failed numeric read while keeping the raw string) rather than guessing on ambiguous `1,234.56`/`1.234,56` inputs. Locale-aware parsing — gated on an explicit per-condition locale/alphabet hint — is a documented post-MVP follow-up.
- **Alphabet/script.** `OCRAlphabet` selection (LATIN, CYRILLIC, ARABIC, CJK, etc.) is per-Text-condition and ships in the TrackingScenario; the wrong alphabet yields garbage. Authoring must require an explicit alphabet for Text reads. The UTF-8-safe `jbyteArray` marshalling (Step 1) is what makes non-Latin strings survive the JNI boundary at all. Number reads deliberately ignore alphabet (first-loaded model), acceptable for shared Latin-derived digit glyphs — but RTL/Arabic-Indic digits are a known weak spot to flag in authoring.
- **Whitespace, leading zeros, and unit suffixes** in Text reads are preserved verbatim in `value`; trimming/normalization for the Text read type is intentionally *not* applied — fidelity beats cleanliness.
- **Empty/failed recognition.** When OCR finds nothing, `textDetected` is `null` and `numberDetected` is `null`; thanks to the Step 2 restructuring the verifier still emits a `Screen` with `isFulfilled=false`, the true (near-0) confidence, and `extracted=null`. The Observation builder records this as a failed read per acceptance criterion #3.

### Summary of changes

- **C++**: `text_matching_result.hpp` gains `recognizedText` storage + accessors, populated by **extending the existing `updateResults(...)`** (single source of truth) and **cleared in `reset()`** (no cross-frame leak); `text_matcher.cpp` passes `recognizerResult.text` through `updateResults` in both `matchText` and `matchNumber`; `jni_detection_result.cpp` gains `toJniTextResult` returning a `NativeTextResult` jobject carrying a **`jbyteArray` (raw UTF-8)**, not `NewStringUTF` (legacy `toJniResult` untouched for color/image).
- **JNI/Kotlin bridge**: `detectTextNative`/`detectNumberNative` return `NativeTextResult?`; new `NativeTextResult` holder (transient; default identity equality, documented); `DetectionResult` gains `textDetected: String?`; the new `NativeTextResult?.toDetectionResult()` **decodes bytes with `Charsets.UTF_8` and parses numbers in Kotlin (Step 6), never comparing against the broken `Double.MIN_VALUE` sentinel**. Cache the `jclass`/ctor as globals at `init()`.
- **Processing**: `ProcessedConditionResult` is a **`sealed class`**; `Screen` extends `ProcessedConditionResult()` (parens) and gains `extracted: ExtractedValue?` plus doc-comment lines for `size` and `extracted`. `verifyNumberCondition` is **restructured** so failed/`!isDetected`/null reads still emit a populated `Screen` (confidence + raw value) instead of `toInvalidConditionResult()`; `verifyText`/`verifyColor` aligned. `onScreenConditionProcessingCompleted` is **extended with a `deviceCapturedAtMs: Long` parameter** sourced from `currentVerificationTsMs`.
- **Domain/DB**: additive `extractOnly: Boolean = false` on `ScreenCondition.Text` and nullable `comparisonOperation` on `.Number`, mirrored as nullable `@ColumnInfo` columns on `ConditionEntity`, **with matching `ConditionMapper` (both directions, defaulting) and `Condition.copyWithNewId()` branch updates**, and a **versioned `ScenarioBackup`/`DeserializerFactory` default** for the now-nullable comparison. `DATABASE_VERSION` bumped **21 → 22** as a **manual `Migration(21, 22)`** (column adds + Observation table/indices in one `migrate()`); no new `ConditionType`.
- **Normalization**: raw UTF-8 OCR string preserved end-to-end; numeric normalization is a **conservative, integer-first Kotlin step** owned by the Observation builder that returns `null` on ambiguous mixed-separator input (raw string retained), with locale-aware parsing deferred post-MVP. Confidence preserved for low-quality reads; no capture-time filtering.

---

## core:network — connectivity, sync & remote control

`core:network` is the on-device gateway to **Trax Cloud** (`/v1`, OpenAPI-described). It owns the HTTP stack, device enrollment + auth, the offline-tolerant Observation uploader, scenario pull/apply, and the remote start/stop command path. It is a brand-new module with **no analogue in the current codebase** — the repo today has no Retrofit, no OkHttp, and (per the build facts) **no WorkManager dependency at all**. Everything here is additive and must be invisible to the GPLv3 LOCAL builds.

> **Hard prerequisites this section establishes.** Three things this module depends on do **not exist in the codebase today** and are therefore *defined here*, not assumed:
> 1. **The Observation persistence layer** — there is no `core:observation` module, no `ObservationEntity`, no `observation_table`, no `ObservationDao`, and no `ObservationSyncState`. A repo-wide search confirms zero hits. This section defines all of them (schema + DAO + enum + a CREATE-table migration) below, placing them in the existing `core:smart:database` module (`core/smart/database/.../entity`, `.../dao`, `.../migrations`) alongside `ScenarioEntity`/`ConditionEntity`, because that is the only Room database (`ClickDatabase`, version 21 per `DatabaseInfo.kt`) and it already owns the entity/DAO/migration recipe. `core:network` then *depends on* this layer; it does not own it.
> 2. **Recognized OCR text** — text-condition observations cannot carry a recognized-string `value` in the MVP. `ProcessedConditionResult.Screen` has **no text field** (`data class Screen(isFulfilled, haveBeenDetected, condition, confidenceRate, position, size)`), and the OCR string is never marshalled across JNI: `toJniResult()` returns a 7-element `jdoubleArray` `[detected, centerX, centerY, width, height, confidence, numberDetected]` and `TextRecognizerResult.text` is discarded in C++ after fuzzy matching. This is a **hard blocking dependency**, scoped explicitly below — not a parenthetical.
> 3. **`feature:cloud`** — the enrollment/sync-status UI referenced for the User Stories is specified in a separate section. This section bounds the dependency: `core:network` exposes repository interfaces (`DeviceEnrollmentRepository`, `SyncStatusRepository`) and a `pendingCountFlow()`; `feature:cloud` consumes them. If `feature:cloud` is not present, `core:network` still compiles and links — it has no compile-time dependency on the UI.

The module follows the established module recipe: `core/network/build.gradle.kts` with `alias(libs.plugins.buzbuz.androidLibrary)` + `alias(libs.plugins.buzbuz.hilt)` + `alias(libs.plugins.buzbuz.flavour)`, `namespace = "com.buzbuz.smartautoclicker.core.network"`, and a `di/Hilt.kt` exposing `@Module @InstallIn(SingletonComponent::class)` providers — exactly like `core/dumb/.../di/Hilt.kt` and `core/common/settings/.../di/Hilt.kt`. It depends on `core:smart:database` (the Observation entity + DAO defined below), `core:common:base` (the `@Dispatcher(IO)` qualifier from `HiltDispatchers.kt`), `core:common:settings`, and `core:common:bitmaps` (`BitmapRepository`, for crop upload).

### Prerequisite: the Observation persistence layer (defined here, lives in `core:smart:database`)

Because nothing observation-related exists, this section defines the schema as the **exact dependency contract** the uploader and state machine rely on. The entity is a new `@Entity @Serializable` data class following the documented entity recipe (`@PrimaryKey(autoGenerate = true)`, `@ColumnInfo`, `@Serializable`, registered in `ClickDatabase @Database(entities = [...])`). A new `OBSERVATION_TABLE = "observation_table"` constant is added to `DatabaseInfo.kt`.

```kotlin
enum class ObservationSyncState { PENDING, UPLOADING, SYNCED, FAILED }

@Entity(tableName = OBSERVATION_TABLE)
@Serializable
data class ObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "idempotency_key") val idempotencyKey: String, // client-generated, unique
    @ColumnInfo(name = "scenario_id")     val scenarioId: Long,        // local Scenario id
    @ColumnInfo(name = "cloud_scenario_id") val cloudScenarioId: String?, // set for pulled scenarios
    @ColumnInfo(name = "device_captured_at") val deviceCapturedAt: Long, // epoch ms (System.currentTimeMillis)
    @ColumnInfo(name = "value")        val value: String?,             // null for text (see JNI blocker)
    @ColumnInfo(name = "value_type")   val valueType: ObservationValueType, // NUMBER | COLOR | IMAGE | TEXT
    @ColumnInfo(name = "confidence")   val confidence: Double,         // 0–100, from confidenceRate
    @ColumnInfo(name = "is_fulfilled") val isFulfilled: Boolean,
    @ColumnInfo(name = "crop_path")    val cropPath: String?,          // local PNG path or null
    @ColumnInfo(name = "sync_state")   val syncState: ObservationSyncState = ObservationSyncState.PENDING,
)
```

The row is **append-only** (contract: no edits/back-fill); only `syncState` is ever mutated after insert. Both enums are stored as `TEXT` via a Room `@TypeConverter` registered on `ClickDatabase` (the codebase already converts enums to strings — e.g. `ConditionType`), encoding each enum to its `name`. This converter is what makes the string-literal queries in `pendingCountFlow()` below valid (`'PENDING'`/`'FAILED'` are the converter's exact encodings); all other DAO methods take an enum parameter and rely on the same converter for binding.

**Migration 21→22** (`Migration21to22.kt`, `object Migration21to22 : Migration(21, 22)`, manual — `CREATE TABLE`, not an `ALTER`, because the table does not exist yet) and `DATABASE_VERSION` is bumped from `21` to `22` in `DatabaseInfo.kt`. Per the migration recipe this manual migration is **not** added to the `autoMigrations` list; `ClickDatabase` loads it from the migrations package:

```kotlin
object Migration21to22 : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `observation_table` (
              `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
              `idempotency_key` TEXT NOT NULL,
              `scenario_id` INTEGER NOT NULL,
              `cloud_scenario_id` TEXT,
              `device_captured_at` INTEGER NOT NULL,
              `value` TEXT,
              `value_type` TEXT NOT NULL,
              `confidence` REAL NOT NULL,
              `is_fulfilled` INTEGER NOT NULL,
              `crop_path` TEXT,
              `sync_state` TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_observation_idempotency_key` ON `observation_table` (`idempotency_key`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_observation_sync_state` ON `observation_table` (`sync_state`)")
    }
}
```

The new `ObservationDao` follows the documented DAO patterns (`@Dao`, `@Query`, `@Insert(onConflict = IGNORE)`, `@Update`):

```kotlin
@Dao interface ObservationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(observation: ObservationEntity): Long  // IGNORE on duplicate idempotency_key

    @Query("SELECT * FROM observation_table WHERE sync_state = :state ORDER BY id LIMIT :limit")
    suspend fun getBySyncState(state: ObservationSyncState, limit: Int): List<ObservationEntity>

    @Query("UPDATE observation_table SET sync_state = :to WHERE id IN (:ids)")
    suspend fun setSyncState(ids: List<Long>, to: ObservationSyncState)

    @Query("SELECT COUNT(*) FROM observation_table WHERE sync_state IN ('PENDING','FAILED')")
    fun pendingCountFlow(): Flow<Int>   // backs on-device "sync state visible" UI
}
```

`pendingCountFlow()` feeds the `feature:cloud` sync-status indicator (User Story 5: "on-device sync state visible"). Because the LOCAL flavor never links `core:network` and never schedules any worker, `observation_table` simply stays empty on LOCAL builds — adding the table to `ClickDatabase` does **not** pull any network code into LOCAL; only the (flavor-neutral) schema exists there.

### HTTP stack & libs.versions.toml additions

Following the documented dependency-addition pattern in `gradle/libs.versions.toml` (add `[versions]`, add `[libraries]`, reference via `libs.getLibrary(...)`), we add Retrofit + OkHttp + the kotlinx.serialization converter. kotlinx.serialization is already the project's serialization story — every Room entity (`ScenarioEntity`, `ConditionEntity`, the new `ObservationEntity`, …) and backup payload (`ScenarioBackup`) is `@Serializable` — so reusing it for the wire format keeps one serializer across disk, backup, and network.

> **Converter coordinate note.** As of Retrofit **2.11.0**, the kotlinx.serialization converter is published by Square itself as `com.squareup.retrofit2:converter-kotlinx-serialization` (the old `com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter` is the pre-2.11 community artifact). We use the Square-owned coordinate, versioned with the Retrofit BOM/version to avoid a stale pairing.

```toml
[versions]
retrofit = "2.11.0"
okhttp = "4.12.0"
androidxWork = "2.9.1"
androidxHiltWork = "1.2.0"
androidxSecurityCrypto = "1.1.0-alpha06"
firebaseMessaging = "24.0.3"

[libraries]
squareup-retrofit = { group = "com.squareup.retrofit2", name = "retrofit", version.ref = "retrofit" }
squareup-retrofit-kotlinx = { group = "com.squareup.retrofit2", name = "converter-kotlinx-serialization", version.ref = "retrofit" }
squareup-okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
squareup-okhttp-logging = { group = "com.squareup.okhttp3", name = "logging-interceptor", version.ref = "okhttp" }
androidx-work-runtime = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "androidxWork" }
androidx-hilt-work = { group = "androidx.hilt", name = "hilt-work", version.ref = "androidxHiltWork" }
androidx-hilt-work-compiler = { group = "androidx.hilt", name = "hilt-compiler", version.ref = "androidxHiltWork" }
androidx-security-crypto = { group = "androidx.security", name = "security-crypto", version.ref = "androidxSecurityCrypto" }
google-firebase-messaging = { group = "com.google.firebase", name = "firebase-messaging-ktx", version.ref = "firebaseMessaging" }
```

The converter is wired into Retrofit at construction. The base URL and an OkHttp `Interceptor` that injects the device token are provided through Hilt:

```kotlin
private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Provides @Singleton
fun providesRetrofit(authInterceptor: AuthInterceptor): Retrofit {
    val contentType = "application/json".toMediaType()
    val client = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .build()
    return Retrofit.Builder()
        .baseUrl("https://api.traxintel.cloud/v1/")
        .client(client)
        .addConverterFactory(json.asConverterFactory(contentType))
        .build()
}

@Provides @Singleton
fun providesTraxApi(retrofit: Retrofit): TraxCloudApi = retrofit.create(TraxCloudApi::class.java)
```

### The Retrofit API interface

A single `TraxCloudApi` interface covers all four surfaces. Request/response DTOs are `@Serializable` data classes; their **canonical shapes are owned by the "Data contracts & schemas" section**, so here I restate only the fields the client needs. The contract's Observation noun (`idempotencyKey`, `tenantId`, `deviceId`, `scenarioId`, `deviceCapturedAt`, `serverReceivedAt`, `value`, `valueType`, `confidence`, `isFulfilled`, optional `cropUrl`) maps directly onto the `ObservationEntity` columns defined above, via an `ObservationDtoMapper`.

```kotlin
interface TraxCloudApi {

    // --- Enrollment (no auth header; pairing code is the credential) ---
    @POST("devices/enroll")
    suspend fun enroll(@Body body: EnrollRequest): Response<EnrollResponse>
    // EnrollRequest(pairingCode, model, osVersion, sdkInt)
    // EnrollResponse(deviceId, tenantId, deviceToken, fcmTopic)

    // --- Observation batch upload (device token authorizes only its own writes) ---
    @POST("observations:batch")
    suspend fun uploadObservations(@Body body: ObservationBatchRequest): Response<ObservationBatchResponse>
    // ObservationBatchRequest(observations: List<ObservationDto>)
    // ObservationBatchResponse(accepted: List<String /*idempotencyKey*/>,
    //                          cropUploadUrls: Map<String, String /*idempotencyKey -> presigned PUT URL*/>)

    @PUT
    suspend fun uploadCrop(@Url presignedUrl: String,
                           @Body png: RequestBody): Response<Unit>

    // --- Scenario pull (only scenarios assigned to this device) ---
    @GET("scenarios/assigned")
    suspend fun getAssignedScenarios(@Query("since") sinceCursor: String?): Response<ScenarioPullResponse>
    // TrackingScenarioDto(scenarioId, readType, detectionArea: RectDto, alphabet, pollIntervalMs, cropCaptureEnabled, revision)

    // --- Remote command pull / ack (polling fallback + post-FCM fetch) ---
    @GET("commands/pending")
    suspend fun getPendingCommands(): Response<CommandPullResponse>
    // CommandDto(commandId, type: START|STOP, scenarioId?, expiresAt)

    @POST("commands/{id}/ack")
    suspend fun ackCommand(@Path("id") commandId: String,
                           @Body body: CommandAckRequest): Response<Unit>
}
```

`RectDto` exists because `ScreenCondition.Number/Text/Color` all carry a `detectionArea: Rect` (per the domain facts); the cloud must round-trip that rect in device screen-space so the pulled scenario reconstructs a real `ScreenCondition`.

### Device enrollment, encrypted credential storage, and non-secret cloud settings

Enrollment is the only call that runs without a device token: the short-lived pairing code is itself the credential (User Story 1). On success the server returns a `deviceToken` (device-scoped — it authorizes only that device's Observations and assigned scenarios, satisfying the tenant-isolation gate) and an `fcmTopic`.

The token is a long-lived bearer credential, so it must **not** live in plaintext DataStore. The codebase's settings layer (`SettingsDataSource`) uses `androidx.datastore.preferences` for non-secret booleans; the EXPECTED COVERAGE asks for "token storage in DataStore/encrypted." We satisfy this with **one** mechanism — an **encrypted Preferences DataStore** — rather than conflating DataStore and `EncryptedSharedPreferences`. Concretely: a standard Preferences DataStore whose backing file holds values encrypted with a `MasterKey` (AES-256-GCM) via Tink, isolated in `core:network` so the LOCAL flavor never links the crypto dependency:

```kotlin
@Singleton
class DeviceCredentialsDataSource @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(IO) private val ioDispatcher: CoroutineDispatcher,
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()

    // Encrypted Preferences DataStore: a DataStore<Preferences> whose serializer
    // encrypts/decrypts the bytes with masterKey-derived AEAD before disk I/O.
    private val dataStore: DataStore<Preferences> = context.encryptedPreferencesDataStore(
        name = "trax_credentials", masterKey = masterKey,
    )

    suspend fun store(token: String, deviceId: String, tenantId: String) = withContext(ioDispatcher) {
        dataStore.edit {
            it[KEY_TOKEN] = token; it[KEY_DEVICE_ID] = deviceId; it[KEY_TENANT_ID] = tenantId
        }
    }

    val deviceIdFlow: Flow<String?> = dataStore.data.map { it[KEY_DEVICE_ID] }
    val isEnrolledFlow: Flow<Boolean> = dataStore.data.map { it[KEY_TOKEN] != null }

    // Synchronous read for the OkHttp interceptor (runBlocking on IO; called off the main thread).
    fun tokenBlocking(): String? = runBlocking(ioDispatcher) { dataStore.data.map { it[KEY_TOKEN] }.first() }
}
```

> **Deprecation/fallback note.** `androidx.security:security-crypto` (the `EncryptedSharedPreferences`/`EncryptedFile` APIs) is in maintenance/deprecation; we use only its `MasterKey` + Tink AEAD primitives to wrap a DataStore serializer, not `EncryptedSharedPreferences` itself, so the migration surface is small. If the artifact is dropped, the fallback is to derive the AEAD directly via Tink (`com.google.crypto.tink`) behind the same `DeviceCredentialsDataSource` interface — no caller changes. The token is replaceable by re-enrollment, so a key-loss fallback is "clear store, force re-pair," not data recovery.

Non-secret cloud state — the contract's **Settings (DataStore)** entries for *account-binding state*, *sync-enabled toggle*, and *crop-capture toggle* — follow the exact `SettingsDataSource` pattern documented in the facts: add `booleanPreferencesKey(...)`/`stringPreferencesKey(...)` keys, expose `Flow<T>` getters via `dataStore.data.map { ... }`, `suspend` setters via `dataStore.edit { ... }`, and wrap them as `StateFlow` in a repository (`stateIn(scope, Eagerly, default)`). These live in a `CloudSettingsDataSource` in `core:network` (kept out of `core:common:settings` so LOCAL never even sees the keys).

The `AuthInterceptor` reads the token and attaches the bearer to every authenticated call. When no token is present (device not enrolled) it proceeds without the header; the **worker layer**, not the interceptor, is responsible for not running before enrollment (see scheduling below):

```kotlin
class AuthInterceptor @Inject constructor(
    private val credentials: DeviceCredentialsDataSource,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val token = credentials.tokenBlocking() ?: return chain.proceed(req)
        return chain.proceed(req.newBuilder().header("Authorization", "Bearer $token").build())
    }
}
```

`DeviceEnrollmentRepository` orchestrates the pairing flow; the UI lives in `feature:cloud`:

```kotlin
@Singleton
class DeviceEnrollmentRepository @Inject constructor(
    private val api: TraxCloudApi,
    private val credentials: DeviceCredentialsDataSource,
    private val syncScheduler: SyncScheduler,
) {
    val isEnrolled: Flow<Boolean> get() = credentials.isEnrolledFlow

    suspend fun enroll(pairingCode: String): EnrollResult {
        val resp = api.enroll(EnrollRequest(
            pairingCode = pairingCode,
            model = Build.MODEL,
            osVersion = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
        ))
        val body = resp.body()
        return when {
            resp.code() == 410 || resp.code() == 404 -> EnrollResult.InvalidOrExpiredCode
            resp.isSuccessful && body != null -> {
                credentials.store(body.deviceToken, body.deviceId, body.tenantId)
                syncScheduler.schedulePeriodic()   // scheduling kicks off ONLY after successful enrollment
                EnrollResult.Success(body.fcmTopic)
            }
            else -> EnrollResult.NetworkError
        }
    }
}
```

`EnrollResult.InvalidOrExpiredCode` exists so the UI can satisfy the acceptance criterion "invalid/expired codes rejected clearly." Crucially, **scheduling is gated on enrollment**: `schedulePeriodic()` is called only on enrollment success, and every worker first checks `isEnrolledFlow.first()` and returns `Result.success()` (a no-op, not `retry()`) if unenrolled. This prevents the 401→`Result.retry()` infinite-backoff storm a freshly-installed-but-unenrolled CLOUD build would otherwise suffer.

### The Observation sync state machine

The contract requires offline queuing, batched upload on reconnect, and **zero server-side duplicates** via idempotency keys. The mechanism is the **`sync_state` column on `ObservationEntity`** defined above. The column is the queue:

State transitions:

- **PENDING** — written at capture time. Per the processing facts, the capture point is `SmartProcessingListener.onScreenConditionProcessingCompleted(result: ProcessedConditionResult.Screen)`, which carries `isFulfilled`, `confidenceRate` (0–100), `position`, `size`, plus `haveBeenDetected`. The `idempotencyKey` is generated **client-side at this moment** (`UUID.randomUUID().toString()`) and stored on the row; the unique index + `INSERT … OnConflictStrategy.IGNORE` make re-insertion a no-op. This key is the duplicate guard: a retried batch re-sends the same key, and the server dedupes.
  - **`value` population by type:** For **Number** conditions the value is `DetectionResult.numberDetected` (a `Double`, marshalled in `toJniResult()` array index 6). For **Color**/**Image** conditions the value is a structured detector outcome (position/match), not free text. For **Text** conditions the recognized string is **not available** — see the blocker below — so `value` is `null` and `valueType = TEXT` records that a value is expected once the JNI path exists. `confidence`, `position`, `size`, and `isFulfilled` are available for all four types today.

  > **BLOCKING DEPENDENCY (text value upload is descoped from MVP).** Surfacing the recognized OCR string to Kotlin requires a JNI change: `ProcessedConditionResult.Screen` has no text field, and `toJniResult()` returns a 7-element `jdoubleArray` that never includes `TextRecognizerResult.text`. Until that marshalling change ships (a new JNI result object or an 8th string element, per the detection facts), **text-condition observations upload with `value = null`**; the rest of the row (fulfillment, confidence, position, crop) uploads normally. This is an explicit, accepted MVP limitation, not a silent gap.

- **PENDING → UPLOADING** — the worker reads a batch with `getBySyncState(PENDING, BATCH_SIZE)` and then marks those exact ids `UPLOADING` with `setSyncState`. This is a read-then-write, **not** an atomic claim; we deliberately do **not** use `UPDATE … LIMIT` (that requires the `SQLITE_ENABLE_UPDATE_DELETE_LIMIT` compile flag, which is not guaranteed on Android). Concurrency is instead guaranteed structurally: the uploader is a **single unique periodic work** (`enqueueUniquePeriodicWork(..., KEEP)`) plus a unique one-time drain (`enqueueUniqueWork(..., KEEP)`), so WorkManager never runs two upload instances concurrently — there is no double-claim window.
- **UPLOADING → SYNCED** — the server returns the `idempotencyKey` in `accepted`. If the row has a local `cropPath` and the response carries a presigned URL, the worker also `PUT`s the PNG (loaded via `BitmapRepository.getImageConditionBitmap(path, w, h)`) before marking SYNCED. Observation crops are saved through `BitmapRepository.saveImageConditionBitmap(bitmap, prefix = "Observation_")`; note the existing public constants are `CONDITION_FILE_PREFIX = "Condition_"` and `TUTORIAL_CONDITION_FILE_PREFIX = "Tutorial_Condition_"`, so a new `OBSERVATION_FILE_PREFIX = "Observation_"` constant is added next to them.
- **UPLOADING → FAILED → PENDING** — a transient failure (timeout, 5xx) re-queues for the next run; a 4xx other than dedup-accept leaves the row FAILED for inspection. Because the row is append-only, we never mutate `value`/`confidence` — only `sync_state`.

### WorkManager uploader, wired with Hilt

WorkManager is introduced by this module. Because the facts confirm **no existing WorkManager/Hilt-work integration**, we set up the full chain. The uploader is a `@HiltWorker CoroutineWorker` so it survives process death, batches, retries with backoff, and only runs when connected:

```kotlin
@HiltWorker
class ObservationUploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val dao: ObservationDao,
    private val api: TraxCloudApi,
    private val bitmaps: BitmapRepository,
    private val mapper: ObservationDtoMapper,
    private val credentials: DeviceCredentialsDataSource,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!credentials.isEnrolledFlow.first()) return Result.success() // no-op when unenrolled (no retry storm)
        val batch = dao.getBySyncState(ObservationSyncState.PENDING, limit = BATCH_SIZE)
        if (batch.isEmpty()) return Result.success()
        dao.setSyncState(batch.map { it.id }, ObservationSyncState.UPLOADING)
        return try {
            val resp = api.uploadObservations(ObservationBatchRequest(batch.map(mapper::toDto)))
            val body = resp.body()
            if (!resp.isSuccessful || body == null) {
                dao.setSyncState(batch.map { it.id }, ObservationSyncState.PENDING)
                return Result.retry()
            }
            val acceptedKeys = body.accepted.toSet()
            val (done, notDone) = batch.partition { it.idempotencyKey in acceptedKeys }
            uploadCrops(done, body.cropUploadUrls)
            dao.setSyncState(done.map { it.id }, ObservationSyncState.SYNCED)
            if (notDone.isNotEmpty()) dao.setSyncState(notDone.map { it.id }, ObservationSyncState.PENDING)
            if (dao.getBySyncState(ObservationSyncState.PENDING, 1).isNotEmpty()) Result.retry() else Result.success()
        } catch (e: IOException) {
            dao.setSyncState(batch.map { it.id }, ObservationSyncState.PENDING)
            Result.retry()
        }
    }
    companion object { const val BATCH_SIZE = 100 }
}
```

Scheduling uses a periodic `UniqueWork` with a `NetworkType.CONNECTED` constraint and exponential backoff — this is what makes it offline-tolerant: rows accumulate as PENDING with no network, and the constraint releases the worker on reconnect, draining the queue (acceptance criterion: "Offline-captured Observations upload on reconnect with zero server-side duplicates"). `schedulePeriodic()` is invoked **only from `DeviceEnrollmentRepository.enroll(...)` success** (and re-asserted at app start if already enrolled), so an unenrolled CLOUD install never schedules anything:

```kotlin
@Singleton
class SyncScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun schedulePeriodic() {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = PeriodicWorkRequestBuilder<ObservationUploadWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "trax-observation-upload", ExistingPeriodicWorkPolicy.KEEP, request)
    }
    fun syncNow() {  // expedited drain, e.g. on reconnect or app foreground
        val req = OneTimeWorkRequestBuilder<ObservationUploadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "trax-observation-upload-now", ExistingWorkPolicy.KEEP, req)
    }
}
```

`WorkManager.getInstance(context)` requires the cloud-only `Configuration.Provider` to be in effect, which is the wiring problem solved next; `SyncScheduler` is itself only linked in CLOUD variants, so calling `getInstance` is safe there.

#### Hilt-work KSP wiring (the `@HiltWorker` factory must be generated on the cloud KSP path)

`@HiltWorker` needs `androidx.hilt:hilt-compiler` on the **KSP** classpath to generate its factory entry. The existing `HiltConventionPlugin` already applies KSP and adds Dagger-Hilt's own compiler, but it does **not** add the AndroidX hilt-work compiler, and `NetworkConventionPlugin` (below) only adds `implementation`-style deps. A plain `cloudImplementation(hilt-work-compiler)` would put it on the wrong configuration. We therefore add a **`cloudKsp(...)` helper** mirroring `cloudImplementation`, and use it for the compiler:

```kotlin
// DependencyHandlerScopeExt.kt
internal fun DependencyHandlerScope.cloudKsp(dependency: Provider<MinimalExternalModuleDependency>) =
    add("kspCloud", dependency)   // KSP's per-flavor configuration is "ksp<Flavour>"
```

Without this, `@HiltWorker` factory generation fails at build time. (KSP names its variant-scoped configurations `ksp<Flavour>`, e.g. `kspCloud`, so the `add(...)` target is `"kspCloud"`, paralleling how `cloudImplementation` targets `"cloudImplementation"`.)

#### Flavor-gating the `Configuration.Provider` (the critical GPLv3/no-network gate)

`SmartAutoClickerApplication` lives in **`smartautoclicker/src/main/`** (per the facts), so it **cannot** conditionally implement `Configuration.Provider` or `@Inject HiltWorkerFactory` per-flavor — doing so in `src/main` would pull WorkManager and `HiltWorkerFactory` into **every** variant, including LOCAL, breaking the gate. A class declared in `src/main` cannot implement an interface "only in some flavors."

The concrete mechanism is **per-flavor Application classes via source sets**, exploiting the new `CONNECTIVITY` dimension:

1. **Move the existing Application out of `src/main`.** Extract the current `SmartAutoClickerApplication` body into an open base class `BaseSmartAutoClickerApplication` in `src/main` (still `@HiltAndroidApp`? no — `@HiltAndroidApp` must annotate the concrete, manifest-registered class). Keep `BaseSmartAutoClickerApplication` un-annotated in `src/main` with all the existing `AppComponentsManager` logic.
2. **`src/local/…/LocalApplication.kt`** — `@HiltAndroidApp class LocalApplication : BaseSmartAutoClickerApplication()`. No WorkManager, no `Configuration.Provider`, no `HiltWorkerFactory`. This is what GPLv3 `fDroidLocal*`/`playStoreLocal*` builds ship.
3. **`src/cloud/…/CloudApplication.kt`** — the CLOUD-only Application that adds WorkManager:

```kotlin
// smartautoclicker/src/cloud/java/.../CloudApplication.kt
@HiltAndroidApp
class CloudApplication : BaseSmartAutoClickerApplication(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
```

4. **Per-flavor manifests register the right class.** `src/local/AndroidManifest.xml` sets `android:name=".application.LocalApplication"`; `src/cloud/AndroidManifest.xml` sets `android:name=".application.CloudApplication"`. The base `src/main` manifest leaves `android:name` unset (or set to the local class as default) so each flavor overrides it.
5. **Remove WorkManager's default initializer — but only in the CLOUD manifest.** For `Configuration.Provider` to take effect, WorkManager's `androidx.startup` `InitializationProvider` node for `WorkManagerInitializer` must be removed. This node-removal lives **only** in `src/cloud/AndroidManifest.xml`, so LOCAL never declares (and never needs to remove) it:

```xml
<!-- smartautoclicker/src/cloud/AndroidManifest.xml -->
<provider
    android:name="androidx.startup.InitializationProvider"
    android:authorities="${applicationId}.androidx-startup"
    android:exported="false"
    tools:node="merge">
    <meta-data
        android:name="androidx.work.WorkManagerInitializer"
        android:value="androidx.startup"
        tools:node="remove" />
</provider>
```

Because LOCAL ships `LocalApplication` (no WorkManager dependency on the classpath at all, since `core:network` and `androidx.work` are `cloudImplementation`-scoped) and never removes any startup node, the LOCAL build neither links nor initializes WorkManager. The CLOUD build ships `CloudApplication`, provides the `HiltWorkerFactory`, and removes the default initializer so the factory is used. This is what actually achieves the gate; the prior "no-op binding in main" hand-wave is dropped.

### Pulling and applying cloud scenarios

A second worker, `ScenarioPullWorker` (also enrollment-gated and unique), calls `getAssignedScenarios(sinceCursor)` and reconstructs local `Scenario`/`ScreenCondition` rows. Each `TrackingScenarioDto` maps to a `ScreenCondition` subtype keyed by `readType`: `Number → ScreenCondition.Number`, `Text → ScreenCondition.Text` (with `alphabet: OCRAlphabet` — the enum already exists with `LATIN`, `ARABIC`, etc.), `State → ScreenCondition.Color`. The `detectionArea` (`RectDto`) becomes the condition's `detectionArea: Rect`. Applying is an idempotent upsert keyed by `cloudScenarioId` + `revision`: if the local revision matches, skip; otherwise replace. This satisfies "Author once, push to many" — the same cloud `scenarioId` lands on N devices, and each device emits Observations tagged with its own `deviceId` (from `DeviceCredentialsDataSource`) under the shared cloud scenario id (stored as `cloudScenarioId` on the Observation row). Scheduling the resulting headless detection loop (min 5s interval) is `core:scheduling`'s job, wrapping `SmartProcessingRepositoryImpl.startDetection(...)` with `autoStopDuration` per the processing facts — `core:network` only delivers the scenario definition and never touches the detection engine itself.

### Remote start/stop: FCM (playStore) + polling fallback (fDroid/no-GMS), with the consent reality

Remote commands must act "within one poll cycle." Two delivery paths converge on the same handler so behavior is identical regardless of transport.

> **CONSENT & FOREGROUND-SERVICE BLOCKER for remote START — must be designed around, not assumed away.** The START path drives an `AccessibilityService` plus a **MediaProjection** foreground service (`foregroundServiceType="mediaProjection"`, declared in the manifest). On modern Android (12+) you **cannot start a `mediaProjection`-typed foreground service from the background**, and MediaProjection consent is **per-capture-session** — the user must grant the screen-capture dialog, and that grant does not persist across sessions. A background `CommandPullWorker` therefore **cannot silently start screen capture**. The MVP reflects this:
> - **STOP works fully unattended.** A remote STOP routes to `LocalServiceProvider.getLocalService { it?.stop() }` and tears down detection/projection; no consent is needed to stop. This satisfies the remote-STOP acceptance gate end-to-end.
> - **START is a consented "arm + notify" flow, not a silent launch.** When the AccessibilityService is connected and a MediaProjection session is already active (the operator has the app foregrounded / projection granted), a remote START routes through `LocalServiceProvider` to begin the assigned scenario immediately — this is the "within one poll cycle" case. When projection is **not** active, the worker cannot start it from the background; instead it posts a high-priority notification ("Tap to start remote tracking for <scenario>") that, on tap, brings the app forward and triggers the standard MediaProjection consent dialog before starting. The dashboard shows the device as "pending operator consent" until then. This is the honest behavior; "remote start launches screen capture silently" is **not** achievable and is not promised.

**FCM path (playStore + CLOUD, has GMS).** The server pushes a data message to the device's `fcmTopic`. A `FirebaseMessagingService` receives it and, rather than trusting the push payload, triggers an immediate authenticated `getPendingCommands()` fetch — push as a wake signal, REST as the source of truth (avoids spoofed commands and respects tenant isolation via the bearer token):

```kotlin
class TraxFcmService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        CommandSyncScheduler.fetchNow(applicationContext) // enqueues CommandPullWorker
    }
    override fun onNewToken(token: String) { /* PATCH device fcm token */ }
}
```

`firebase-messaging-ktx` and `TraxFcmService` are declared **only in the `playStoreCloud` source set** (a flavor-combination source set, e.g. `smartautoclicker/src/playStoreCloud/`) so they never enter the fDroid GPLv3 build — mirroring how Crashlytics/GMS are scoped today via `playStoreImplementation`.

**Polling fallback (fDroid/CLOUD, no GMS).** Without Play Services there is no FCM, so a periodic, enrollment-gated `CommandPullWorker` polls `getPendingCommands()` on a tight interval (the "poll cycle"), using the same `Constraints(CONNECTED)` and `ExistingPeriodicWorkPolicy.KEEP` as the uploader.

Both paths run `CommandPullWorker`, which applies each `CommandDto` through the consent-aware seam:

```kotlin
@HiltWorker
class CommandPullWorker @AssistedInject constructor(
    @Assisted ctx: Context, @Assisted params: WorkerParameters,
    private val api: TraxCloudApi,
    private val credentials: DeviceCredentialsDataSource,
    private val trackingController: TrackingController, // bridges to core:scheduling / LocalServiceProvider
) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (!credentials.isEnrolledFlow.first()) return Result.success()
        val resp = api.getPendingCommands()
        val body = resp.body() ?: return Result.retry()
        val now = System.currentTimeMillis()
        for (cmd in body.commands) {
            if (cmd.expiresAt < now) { api.ackCommand(cmd.commandId, CommandAckRequest("EXPIRED")); continue }
            val outcome = when (cmd.type) {
                CommandType.START -> trackingController.requestStart(cmd.scenarioId!!) // immediate if projection active, else notify-to-consent
                CommandType.STOP  -> trackingController.stop(cmd.scenarioId)            // always unattended
            }
            api.ackCommand(cmd.commandId, CommandAckRequest(outcome.ackReason)) // APPLIED | PENDING_CONSENT | NOOP
        }
        return Result.success()
    }
}
```

Commands are **idempotent and expiring** (contract): the worker checks `expiresAt`, acks every command (so the server stops resending), and START/STOP are no-ops if already in that state. `TrackingController` is the thin seam that `core:scheduling` implements over `SmartProcessingRepositoryImpl.startDetection`/`stopDetection` and `LocalServiceProvider`; `core:network` depends only on its interface. After applying STOP (or after consent-gated START completes), the device's next status heartbeat reports the new `tracking` state, which the dashboard reflects (acceptance gate). The `PENDING_CONSENT` ack lets the dashboard distinguish "device received START but is awaiting operator consent" from "applied."

### Flavor gating: the `CONNECTIVITY` dimension and `cloudImplementation`/`cloudKsp`

The whole module must be inert in LOCAL builds (GPLv3 preserved, no network linked). We mirror the documented Crashlytics gating exactly. The facts show `playStoreImplementation` is just `add("playStoreImplementation", dependency)` in `DependencyHandlerScopeExt.kt`, consumed inside `CrashlyticsConventionPlugin`'s `dependencies { }`. The flavor it scopes to comes from `KlickrFlavour` in `KlickrVariants.kt`, applied by `FlavourConventionPlugin`.

Step 1 — add the `CONNECTIVITY` dimension and `LOCAL`/`CLOUD` flavors in `KlickrVariants.kt` (the file currently has only `KlickrDimension.VERSION` and the `F_DROID`/`PLAY_STORE` flavors):

```kotlin
enum class KlickrDimension(val flavourDimensionName: String) {
    VERSION("version"),
    CONNECTIVITY("connectivity");
}
enum class KlickrFlavour(val flavourName: String, val dimension: KlickrDimension) {
    F_DROID("fDroid", KlickrDimension.VERSION),
    PLAY_STORE("playStore", KlickrDimension.VERSION),
    LOCAL("local", KlickrDimension.CONNECTIVITY),   // default, no network
    CLOUD("cloud", KlickrDimension.CONNECTIVITY);
}
```

`FlavourConventionPlugin` already iterates `KlickrDimension.entries` for `flavorDimensions` and `KlickrFlavour.entries` for `productFlavors`, so adding the dimension automatically produces the `playStoreCloudRelease` / `fDroidLocalRelease` / … matrix with **no plugin change**.

> **Caveat to verify (not a blocker):** `KlickrFlavour.dimension` is a single field and `isBuildForVariant(flavour, buildType)`/`getVariantName` compose from a single flavour. With two dimensions, a full variant name now has two flavour segments (e.g. `playStoreCloud`). Any caller that assumes a one-flavour variant name (`isBuildForVariant` callers, obfuscation's `getVariantName`) must be re-checked so it still selects correctly; the auto-generation of the matrix itself is sound.

Step 2 — add the configurations in `DependencyHandlerScopeExt.kt`, mirroring `playStoreImplementation` (note **both** the implementation and the KSP helper from the hilt-work section):

```kotlin
internal fun DependencyHandlerScope.cloudImplementation(dependency: Provider<MinimalExternalModuleDependency>) =
    add("cloudImplementation", dependency)
internal fun DependencyHandlerScope.cloudKsp(dependency: Provider<MinimalExternalModuleDependency>) =
    add("kspCloud", dependency)
```

Step 3 — a `NetworkConventionPlugin` (registered in `build-logic/convention/build.gradle.kts` `gradlePlugin { register("network") { … } }` and added to `libs.versions.toml [plugins]`) scopes the network deps to the `cloud` flavor, exactly as `CrashlyticsConventionPlugin` scopes Firebase to `playStore` — including the hilt-work **compiler on the cloud KSP path**:

```kotlin
class NetworkConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        val libs = getLibs()
        dependencies {
            cloudImplementation(libs.getLibrary("squareup.retrofit"))
            cloudImplementation(libs.getLibrary("squareup.retrofit.kotlinx"))
            cloudImplementation(libs.getLibrary("squareup.okhttp"))
            cloudImplementation(libs.getLibrary("androidx.work.runtime"))
            cloudImplementation(libs.getLibrary("androidx.hilt.work"))
            cloudImplementation(libs.getLibrary("androidx.security.crypto"))
            cloudKsp(libs.getLibrary("androidx.hilt.work.compiler"))   // generates @HiltWorker factory on cloud KSP path
        }
    }
}
```

Firebase Messaging is added via the existing `playStoreImplementation` (it requires GMS, the playStore-only path), so FCM links **only** in `playStoreCloud*` variants; `fDroidCloud*` compiles without it and relies on `CommandPullWorker` polling. The app module references `core:network` and `feature:cloud` via `cloudImplementation(project(":core:network"))` / `cloudImplementation(project(":feature:cloud"))` so those modules are linked only in CLOUD variants — the LOCAL build never sees Retrofit, OkHttp, WorkManager, the encrypted credentials store, or the `feature:cloud` UI, satisfying the gate "LOCAL builds compile and run with no network module linked (GPLv3 preserved)."

### DI summary

`core/network/.../di/Hilt.kt` (`@Module @InstallIn(SingletonComponent::class)`, the universal pattern) provides: `Json`, the `AuthInterceptor`, `OkHttpClient`, `Retrofit`, `TraxCloudApi`, `DeviceCredentialsDataSource`, `CloudSettingsDataSource`, `SyncScheduler`, the `ObservationDtoMapper`, and `@Binds` the repository interfaces (`DeviceEnrollmentRepository`, `SyncStatusRepository`). The new `ObservationDao` is provided from `core:smart:database`'s existing `SmartDatabaseModule` (same pattern as the other DAOs). Workers are `@HiltWorker` and resolved by the cloud-only `HiltWorkerFactory` (wired in `CloudApplication`, not in `src/main`); the `@Dispatcher(IO)` qualifier from `core:common:base`'s `HiltDispatchers.kt` is reused for all disk/crypto work. Hilt auto-discovers the module by classpath scanning — but because the module only links in CLOUD variants, the entire graph is absent from LOCAL builds, which is precisely the isolation the MVP contract demands.

---

## Tracking scenarios, scheduling & headless execution

A TraxIntel *tracking scenario* is not a new execution engine — it is a thin projection onto the auto-clicker's existing `Scenario` → `Event` → `Condition` → `Action` graph and the `DetectorEngine`/`ScenarioProcessor`/`ConditionsVerifier` pipeline that already drives it. The discipline of this section is to reuse that machinery, add a scheduler around it, and add an observer that captures readings — while deliberately never wiring an `Action` into the loop. This keeps us inside the MVP's read-only contract and avoids touching the gesture/`ActionExecutor` path.

Three load-bearing realities of the existing engine shape everything below, and the original draft got each of them wrong. They are stated up front because the rest of the design is built around them:

1. **The observation callbacks only fire when `liveDebugging` or `generateReport` is true.** In `DetectorEngine.startDetection` the processor is constructed with `progressListener = if (liveDebugging || generateReport) debuggingListener else null` (`DetectorEngine.kt` line 258). With both flags false, `ScenarioProcessor` receives a *null* listener and `ConditionsVerifier` never invokes `onScreenConditionProcessingCompleted`. **Tracking must therefore run with `liveDebugging = true`** (see "Wiring the observer" below).
2. **There is exactly one listener slot, injected by Hilt.** `DetectorEngine` takes a single `debuggingListener: SmartProcessingListener` via constructor injection (`DetectorEngine.kt` line 83). It is the only listener the engine will ever call. We cannot "attach a second listener" — we must make that one slot fan out (see below).
3. **The public repository API pulls the graph from the database by id — it does not accept an in-memory graph.** `SmartProcessingRepository.startDetection(context, liveDebugging, generateReport, autoStopDuration)` takes no scenario/events. The impl reads them with `scenarioRepository.getScenario(id)` / `getScreenEvents(id)` using `scenarioId.value.databaseId` and returns early if the scenario is null (`SmartProcessingRepositoryImpl.kt` lines 156–161). The internal `DetectorEngine.startDetection(context, scenario, screenEvents, …)` *does* accept an in-memory graph, but it is `internal` to the processing module and not reachable from `core:scheduling`. A temporary/negative `Identifier` will not resolve in `getScenario`, `getScreenEvents`, or `shouldKeepScreenOn` (line 109). **The "materialize in memory and never persist" approach is not achievable through the existing API** and is abandoned below in favor of a persisted-but-hidden row.

### Mapping a TrackingScenario onto Scenario/Event/Condition/Action

The cloud-authored `TrackingScenario` resolves on-device into a one-to-one local graph the existing engine can run unmodified. Its declared parameter set is:

- `cloudScenarioId: String` — the cloud's stable id (never invented on-device).
- `readType: [Number | Text | State]`.
- `detectionArea: Rect` in device screen-space.
- `alphabet: OCRAlphabet` — meaningful for `Text` only.
- `threshold: Int` — the detection threshold passed to `ScreenCondition.threshold`.
- `stateColor: Int?` — `@ColorInt` source color, required for `State` reads, null otherwise.
- `intervalSeconds: Int` — polling cadence, min 5s.
- `cropCaptureEnabled: Boolean`.

`stateColor` and `threshold` are first-class fields of the type (the original draft referenced them in code without declaring them). The mapping:

- **One `Scenario`** — `Scenario(id, name, detectionQuality, randomize, keepScreenOn, computeRate, eventCount, …)` (`scenario/Scenario.kt`). A tracking scenario maps to one `Scenario` with `eventCount = 1`, `randomize = false` (no humanization for reads), and `detectionQuality`/`computeRate` from a tracking default. `keepScreenOn = true` — we depend on the dim wake-lock to hold the display on during bursts (see "Screen-off behavior"). The earlier draft set `keepScreenOn = false` in code while the prose argued for `true`; the value is `true`.
- **One `ScreenEvent`** — the scenario owns a single `ScreenEvent`. `enabledOnStart = true` so `canStartDetection` (`SmartProcessingRepositoryImpl.kt` lines 115–126, true only if some event is `enabledOnStart`) passes. `conditionOperator` is `AND` (irrelevant with one condition; `AND` is the `@ConditionOperator Int` constant). Per `Event.kt` lines 76–87, `ScreenEvent` **requires** non-optional `keepDetecting: Boolean` and `cooldownMs: Long` — both are supplied (`keepDetecting = true`, `cooldownMs = 0L`); the original sketch omitted them and would not compile.
- **One `ScreenCondition`** — the *extract*, reusing the sealed subtypes in `condition/ScreenCondition.kt`:
  - **Number** → `ScreenCondition.Number(detectionArea, comparisonOperation, counterValue, …)`. For tracking the comparison gate is irrelevant — we want the *value*. `shouldBeDetected = true` with a permissive `comparisonOperation`/`counterValue` so `verifyNumberCondition` (lines 189–229) always proceeds. The recognized number is produced into the lower-level `DetectionResult.numberDetected` (a `Double?`), **not** onto the listener payload — see "Surfacing extracted values."
  - **Text** → `ScreenCondition.Text(text, detectionArea, alphabet)`. `verifyTextCondition` (lines 232–256) fuzzy-matches the OCR string against `condition.text` and returns only a boolean; the recognized string is discarded in C++. Tracking needs the recognized string itself, which requires the targeted JNI change in "Surfacing extracted values." `text` becomes a no-op filter (empty/wildcard).
  - **State** → `ScreenCondition.Color(color, threshold, detectionArea, …)` verified by `verifyColorCondition` (lines 127–150). `color = stateColor` (required for `State`). The State read records fulfillment (color present/absent) plus position/confidence.
  - **Image is excluded** by contract — template matching stays in the legacy auto-clicker. This exclusion also conveniently avoids the bitmap-template push problem: there are no condition PNGs to fan out to devices, only declarative fields, which strengthens the read-only/templating story.

- **Zero `Action`s** — the event has an empty action list. `ScenarioProcessor.processScreenEvents` (lines 165–178) only calls `actionExecutor.executeActions(screenEvent, results)` after `results.fulfilled`; with no actions configured this is a no-op even when fulfilled. The gesture engine is never on the critical path.

#### The persisted-but-hidden graph (replacing the ephemeral approach)

Because the public `startDetection` loads the graph from the smart DB by `scenarioId.databaseId` (fact 3 above), the local `Scenario`/`ScreenEvent`/`ScreenCondition` **must be persisted into the existing `ClickDatabase`** with real, resolvable ids — an in-memory negative `Identifier` cannot be used. The chosen design:

- On `TrackingScenarioRepository.upsert(template)`, materialize the graph and write it into the smart DB via the normal scenario/event/condition DAOs, obtaining a **real autogenerated `Scenario.id`**. Persist the mapping `cloudScenarioId → localScenarioId` in the new `core:observation` schema (migration 21 → 22 against `ClickDatabase`, `DatabaseInfo.DATABASE_VERSION` is currently 21).
- Mark the row as a tracking scenario so it is filtered out of the normal scenario list and excluded from legacy backups. The cleanest mechanism is a new boolean column on `scenario_table` (e.g. `is_tracking`, defaulted false via a simple `AutoMigration`), with the scenario-list query and `BackupEngine` skipping `is_tracking = true` rows. Legacy-scenario migration of tracking rows is explicitly out of scope.
- A poll then becomes: `setScenarioId(localScenarioId, markAsUsed = false)` → `startDetection(liveDebugging = true, …)`, which now resolves `getScenario`/`getScreenEvents` correctly because the id is a real `databaseId`. `shouldKeepScreenOn` (line 109) also resolves, so the wake-lock logic works.
- The local row is durable across process death (it lives in SQLite), so the worker re-attaches to it by `cloudScenarioId → localScenarioId` lookup on every invocation rather than rebuilding it.

A sketch of the materializer (now compiling against the real `ScreenEvent` signature):

```kotlin
// core:observation — builds the domain graph that will be persisted into ClickDatabase
internal fun TrackingScenario.toRunnableScenario(localScenarioId: Identifier): RunnableTracking {
    val eventId = Identifier(databaseId = DATABASE_ID_INSERTION) // real id assigned on insert
    val conditionId = Identifier(databaseId = DATABASE_ID_INSERTION)
    val condition: ScreenCondition = when (readType) {
        ReadType.NUMBER -> ScreenCondition.Number(
            id = conditionId, eventId = eventId, name = "track_num",
            threshold = threshold, shouldBeDetected = true, priority = 0,
            detectionArea = detectionArea,
            comparisonOperation = ComparisonOperation.EQUALS,  // permissive; value is what we want
            counterValue = CounterOperationValue.Number(0),
        )
        ReadType.TEXT -> ScreenCondition.Text(
            id = conditionId, eventId = eventId, name = "track_text",
            threshold = threshold, shouldBeDetected = true, priority = 0,
            text = "", detectionArea = detectionArea, alphabet = alphabet,
        )
        ReadType.STATE -> ScreenCondition.Color(
            id = conditionId, eventId = eventId, name = "track_state",
            threshold = threshold, shouldBeDetected = true, priority = 0,
            color = requireNotNull(stateColor), detectionArea = detectionArea,
        )
    }
    val event = ScreenEvent(
        id = eventId, scenarioId = localScenarioId, name = "track",
        conditionOperator = AND, enabledOnStart = true,
        conditions = listOf(condition), actions = emptyList(),
        priority = 0, keepDetecting = true, cooldownMs = 0L,   // both are REQUIRED on ScreenEvent
    )
    return RunnableTracking(
        scenario = Scenario(
            id = localScenarioId, name = "track:$cloudScenarioId",
            detectionQuality = DEFAULT_TRACKING_QUALITY,
            keepScreenOn = true,                                // unattended tracking needs the wake-lock
        ),
        screenEvents = listOf(event),
    )
}
```

### Wiring the observer onto the single listener slot

Since `DetectorEngine` calls exactly one Hilt-injected `debuggingListener` and only when `liveDebugging`/`generateReport` is set, the observation layer is integrated by two changes:

1. **Force `liveDebugging = true` for tracking polls.** This is the only switch that makes `ConditionsVerifier` fire `onScreenConditionProcessingCompleted` (lines 149/185/228/255, "called even if the condition is not fulfilled"). It also triggers `onSessionStarted` (line 238). The "report" flag stays false so no report file is produced.
2. **Make the single injected `SmartProcessingListener` a fan-out multiplexer.** Replace the direct binding of `debuggingListener` with a composite `SmartProcessingListener` provided by Hilt that delegates each callback to an ordered list of registered child listeners — the existing debugging/report listener *and* a new `core:observation` listener. The composite is `@Singleton`; the observation child is enabled only while a tracking session is active (keyed by the current `scenarioId`). This is the load-bearing integration point the original draft hand-waved: there is no second listener parameter on the engine, so multiplexing the one slot is mandatory.

The observation child implements `onScreenConditionProcessingCompleted(result: ProcessedConditionResult.Screen)` and constructs one `Observation` per fire. `ProcessedConditionResult.Screen` (`ProcessedConditionResult.kt`) exposes `isFulfilled`, `haveBeenDetected`, `condition`, `confidenceRate` (0–100), `position`, and `size`. The timestamp is taken at the listener (`System.currentTimeMillis()`; the verifier captures `currentVerificationTsMs` internally at line 57). The optional crop is saved via `BitmapRepository.saveImageConditionBitmap(crop, "Observation_")`. (The exact `Observation` shape and `value`/`valueType` encoding are owned by the Data contracts section.)

### Surfacing extracted values — the payload does NOT carry them today

This is a gap the original draft understated. `ProcessedConditionResult.Screen` carries **only** `isFulfilled / haveBeenDetected / condition / confidenceRate / position / size`. It does **not** carry `numberDetected`, and it does not carry any recognized text. The extracted values live one layer down, on the detection-level `DetectionResult` (`numberDetected: Double?` for `detectNumber`; no text at all because the OCR string is consumed for fuzzy matching in C++ and discarded). Concretely, *all three* read types need plumbing the listener does not currently expose:

- **Number** — `verifyNumberCondition` already receives `detectionResult.numberDetected`. The change is to add a `numberDetected: Double?` field to `ProcessedConditionResult.Screen` and populate it when constructing the result at line ~222, so the observation listener can read the value. No native change needed.
- **Text** — the recognized string never crosses JNI. `jni_detection_result.cpp` marshals a 7-element `jdoubleArray` (`[detected, centerX, centerY, width, height, confidence, numberDetected]`); `TextRecognizerResult.text` stays in C++ (`text_matcher.cpp`). Surfacing it requires the targeted JNI change: extend the result marshalling to return the UTF string (e.g. change `detectTextNative` to return a small result object carrying both the double array and the recognized text, or add a parallel string out-param), thread it through `DetectionResult`, and add a `recognizedText: String?` field to `ProcessedConditionResult.Screen`.
- **State** — no extracted scalar; the `Observation` value is fulfillment (color present/absent) plus `position`/`confidenceRate`, all already on the payload.

So the minimum engine surface change for MVP is: add `numberDetected` (and later `recognizedText`) to `ProcessedConditionResult.Screen`, and populate it in `ConditionsVerifier`. Without this even the Number path cannot produce a value.

### Fixed-interval scheduling via WorkManager

The codebase has **no WorkManager today** (no `androidx.work` dependency, no workers). We introduce it in the new `core:scheduling` module solely to fire a polling cycle every N seconds (min 5s per contract) and to wrap one short detection burst per cycle.

The natural fit is the existing auto-stop primitive. `startDetection(…, autoStopDuration: Duration?)` (lines 156–180) launches an `autoStopJob` that `delay(duration)` then calls `stopDetection()` (lines 173–179). So one *poll* is: ensure the live foreground service + projection are up → `startDetection(liveDebugging = true, autoStopDuration = burst)` → the engine grabs frames in `processScreenImages()` (lines 374–394) → `ConditionsVerifier` fires `onScreenConditionProcessingCompleted` → the multiplexed observation listener records the Observation → auto-stop fires. We do **not** keep the loop spinning between polls. The scheduler owns the *interval*; `autoStopDuration` owns the *burst length*.

**Burst length must guarantee at least one processed frame.** The frame loop has a minimum-processing-duration throttle (`DEFAULT_MIN_PROCESSING_DURATION_NS`) and a ~20ms no-image delay when `acquireLatestBitmap()` returns null. A fixed 400ms burst may capture only a handful of frames and occasionally zero. The scheduler therefore uses **retry-until-one-frame semantics**: the observation listener signals when it has recorded its first Observation for the session, and the scheduler stops the burst on that signal (or on a hard timeout, recording an unfulfilled Observation if no frame was processed). A floor of ~500ms is a reasonable default, but correctness comes from the "at least one frame or explicit failure" rule, not the duration.

WorkManager's minimum periodic interval is 15 minutes, far coarser than our 5s floor, so `PeriodicWorkRequest` cannot drive the cadence. Two layers cooperate:

- **The active cadence is a coroutine loop inside the live foreground service.** While a tracking session is active and the `mediaProjection` foreground service is alive, a coroutine `delay(interval)`s between bursts. A foreground `mediaProjection` service is exempt from most Doze throttling while running, so sub-minute polling is achievable as long as the projection session and service stay up.
- **WorkManager is the durability/restart layer**, not the sub-minute clock. A self-rescheduling `OneTimeWorkRequest` (chained with `setInitialDelay(interval, SECONDS)`) and `enqueueUniqueWork(cloudScenarioId, REPLACE, …)` re-enqueues if the process is killed and survives reboot via the persisted queue.

```kotlin
// core:scheduling
@HiltWorker
class PollTrackingWorker @AssistedInject constructor(
    @Assisted ctx: Context, @Assisted params: WorkerParameters,
    private val smartProcessingRepository: SmartProcessingRepository,
    private val trackingRepository: TrackingScenarioRepository,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val cloudScenarioId = inputData.getString(KEY_SCENARIO_ID) ?: return Result.failure()
        val tracking = trackingRepository.getActive(cloudScenarioId) ?: return Result.success()

        // The engine is a process-singleton whose RECORDING state lives only in the running
        // foreground service. A worker in a fresh process cannot itself (re-)acquire projection.
        if (!smartProcessingRepository.isRunning() && !trackingRepository.isProjectionLive()) {
            // Projection/service is down (e.g. process was killed). We cannot silently re-grant.
            trackingRepository.recordUnfulfilled(cloudScenarioId, reason = "no_projection")
            trackingRepository.requestRegrant(cloudScenarioId) // surfaced to feature:cloud dashboard
        } else {
            val local = trackingRepository.localScenarioId(cloudScenarioId)
            smartProcessingRepository.setScenarioId(local, markAsUsed = false)
            smartProcessingRepository.startDetection(
                context = applicationContext,
                liveDebugging = true,    // REQUIRED: otherwise no observation callbacks fire
                generateReport = false,
                autoStopDuration = tracking.burstDuration, // retry-until-one-frame governed
            )
        }

        scheduleNext(cloudScenarioId, tracking.intervalSeconds) // self-reschedule
        return Result.success()
    }
}
```

**WorkManager + Hilt setup.** A `@HiltWorker` worker plus a `HiltWorkerFactory`. `SmartAutoClickerApplication` is already `@HiltAndroidApp`; it currently initializes an `AppComponentsManager` in `onCreate` for obfuscated component names. To add on-demand WorkManager initialization the application implements `Configuration.Provider` and we **disable the default `WorkManagerInitializer`** in the manifest (remove it from the `androidx.startup` provider) so the custom `Configuration` with the `HiltWorkerFactory` is used. The existing obfuscation/component init in `onCreate` is independent of this and is left intact; the only interaction to verify is that the manifest's `WorkManagerInitializer` removal does not collide with the obfuscation plugin's manifest-placeholder rewrites (the plugin randomizes component class names, not the `androidx.startup` provider, so they are orthogonal).

The scheduler's public surface is small: `start(cloudScenarioId, intervalSeconds)` (`enqueueUniqueWork(cloudScenarioId, REPLACE, …)`) and `stop(cloudScenarioId)` (`cancelUniqueWork`). Remote fleet start/stop (Story 7) maps directly onto these when `core:network` pulls a command. A *unique* work name per scenario gives idempotent start/stop — a duplicate start `REPLACE`s rather than stacks.

#### Interlock with the legacy detection path

`DetectorEngine` is a `@Singleton` with a single `DETECTING` state and records one screen at a time. Two contention cases must be handled:

- **Tracking vs. tracking.** If multiple tracking scenarios are assigned, the scheduler serializes their bursts behind a single-permit mutex around the start/auto-stop window and round-robins them. With 5s+ intervals and sub-second bursts there is ample headroom.
- **Tracking vs. a user-run auto-clicker scenario.** A normal scenario started from `LocalService` and a tracking poll would contend for the same engine and the same `scenarioId`. The scheduler must **not** start a burst while `smartProcessingRepository.isRunning()` reflects a foreground/legacy session it did not start. The interlock: before each burst, check the current `scenarioId` against the tracking row's `localScenarioId`; if the engine is busy with a different (legacy) scenario, **skip the poll** and record an unfulfilled Observation with `reason = "engine_busy"` rather than hijacking or stopping the user's session. Tracking yields to interactive use.

### Headless / background execution and the projection constraints

The capture stack is: `AccessibilityService` (`SmartAutoClickerService`, whose `onServiceConnected` instantiates `LocalService` via `LocalServiceProvider`) + a foreground service typed `mediaProjection` (manifest `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `android:foregroundServiceType="mediaProjection"`; started in `LocalService`) + `MediaProjection` (the grant `DisplayRecorder` reads frames from in `processScreenImages`). Each layer constrains background operation:

- **No projection without a one-time user grant.** `MediaProjection` requires the user to approve the system "Start recording / casting?" dialog. `SmartProcessingRepositoryImpl.startScreenRecord(resultCode, data)` (line 150) consumes the `Intent` returned from that dialog. There is **no API to silently acquire projection** — a freshly enrolled device cannot begin capturing purely from a cloud command; the operator must grant projection once per device. After that, capture can run unattended.

- **Projection lifecycle for headless workers (the crux).** The original draft asserted "WorkManager re-enqueues on process kill" restores capture; it does not, and here is the concrete design. The projection token and the `resultCode`/`Intent` from the consent dialog are only available from the Activity result. They are fed to `startScreenRecord` **once, inside the live `LocalService`** at grant time, which transitions the `@Singleton` `DetectorEngine` to `RECORDING`. Crucially: **`startScreenRecord` refuses unless the engine state is `CREATED`, and the engine's `RECORDING`/`DETECTING` state is in-process memory that is lost on process death.** Therefore:
  - While the foreground service stays alive, the engine remains in `RECORDING` and `PollTrackingWorker` only ever calls `startDetection` (cheap, reuses the live projection). The worker does **not** call `startScreenRecord` and does not own the projection `Intent`.
  - If the OS kills the service/process, the in-memory `RECORDING` state and the `MediaProjection` are gone. A worker in a fresh process **cannot** re-enter `RECORDING` because it has neither the engine in `CREATED` state in that process nor the original consent `Intent` (which is not persistable across a fresh projection on Android 14+). The worker detects this (`!isRunning() && !isProjectionLive()`), records an unfulfilled Observation, and surfaces a re-grant request — it does not pretend to restore capture.
  - The realistic durability story for a kiosk device is therefore: keep the foreground service alive (the device stays awake and the service is `mediaProjection`-typed, so it is rarely killed), and treat process death as a re-grant event, not a silently recoverable one.

- **Re-acquiring projection after loss.** Projection dies on certain configuration changes, on OS revocation, or if the foreground service is killed. The `MediaProjection.Callback.onStop()` path surfaces through `setProjectionErrorHandler` (line 141; invoked from the `startScreenRecord` error lambda, lines 151–153). When it fires, the scheduler **cannot silently re-grant** — it marks the scenario inactive locally, records an unfulfilled Observation, and signals `feature:cloud` to surface "projection lost — re-grant required." On Android 14+ a fresh projection requires fresh user consent each time, so re-acquisition is inherently user-present. Keeping a single projection session alive across the device's uptime (foreground service kept up) is the only way to avoid repeated prompts.

- **Screen-off behavior is the hard limit.** `MediaProjection` captures the display; with the screen off (locked/dozing) there is generally no rendered surface to read, and Doze throttling suspends background work. The mitigation is `Scenario.keepScreenOn` plus the `SCREEN_DIM_WAKE_LOCK` held in `SmartProcessingRepositoryImpl` (`wakeLock`, lines 87–93; toggled by `shouldKeepScreenOn`, lines 106–113, only while `DETECTING` and `scenario.keepScreenOn`). For unattended tracking the tracking `Scenario.keepScreenOn = true` so the dim wake-lock holds the display on during bursts. **True screen-off capture is not supported in MVP**: a device whose screen is fully off produces no Observations; the operator must keep the device awake (kiosk power settings, "stay awake while charging," or `keepScreenOn`). Failed polls during sleep are recorded as `isFulfilled = false` Observations (Story 3) so the dashboard shows gaps honestly.

- **Doze and WorkManager throttling.** Active polling runs in the coroutine loop inside the live foreground service; WorkManager is the durability/restart layer. A foreground `mediaProjection` service is exempt from most Doze throttling while running, so sub-minute polling is achievable *as long as the projection session and foreground service stay up*. If the OS kills the service, WorkManager re-enqueues `PollTrackingWorker`, which (per the lifecycle design above) detects the dead projection and surfaces the re-grant requirement rather than failing silently or pretending to restore capture.

The net contract for the fleet: **author once in the cloud, but each device pays a one-time, on-device projection grant and must stay awake.** Acceptable for dedicated farm/kiosk devices (the primary and secondary use cases), and stated plainly in enrollment UX so operators aren't surprised that a remote "start" on a never-granted device yields "awaiting projection grant" instead of Observations.

### Parameterization & templating for fleet push

Because the local graph is built from a `TrackingScenario` definition, the cloud pushes only the parameter set, not a serialized engine graph. The pushed template (pulled by `core:network`; canonical schema owned by the Data contracts section):

```json
{
  "scenarioId": "ts_9f3a...",
  "version": 1,
  "name": "Gold counter",
  "readType": "NUMBER",
  "detectionArea": { "left": 980, "top": 120, "right": 1180, "bottom": 180 },
  "authoredScreen": { "width": 1080, "height": 2340 },
  "alphabet": "LATIN",
  "stateColor": null,
  "threshold": 80,
  "pollIntervalSeconds": 10,
  "cropCaptureEnabled": true
}
```

The `detectionArea` is a well-formed `Rect` quartet (`left/top/right/bottom`) — the earlier draft's `top_`/duplicate-`top` shape was garbled and is removed. `stateColor` is an `@ColorInt`-encoded integer (or null) and is **required for `readType: "STATE"`**; for `NUMBER`/`TEXT` it is null. This makes State reads fully templatable rather than unimplementable.

Three templating concerns:

1. **Coordinate portability — net-new scaling code.** `detectionArea` is authored against one device's resolution (`authoredScreen`). When assigned to a fleet of mixed resolutions, each device scales the rect: `scaledRect = detectionArea * (deviceScreen / authoredScreen)`. This is a **new pre-step we must add**, not something the engine already does. `ScalingManager.getScreenConditionScalingInfo` maps a condition's `detectionArea` from screen-space into *detection-space* (quality-driven down-scaling); it does **not** perform cross-device resolution scaling. The new `authoredScreen → deviceScreen` scale runs *before* the condition is handed to the engine. The analogy to `ScenarioBackup.screenWidth`/`screenHeight`/`screenCompatWarning` (`feature/backup/.../ScenarioBackup.kt`) is about *warnings* on dimension mismatch — those are not auto-scalers; the proportional auto-scale is net-new. For MVP the fleet is "the same app on similar devices," so a proportional scale plus a recorded mismatch flag is sufficient; non-proportional aspect ratios surface a warning rather than silently misread.

2. **OCR alphabet vs. number reads.** Text reads carry `alphabet: OCRAlphabet` (`detection-models/.../OCRAlphabet.kt`); the device resolves the model path via `OCRModelsRepository.getRecognitionModelPath(alphabet)`, downloading if absent (`downloadRecognitionModel`). Number reads **ignore the alphabet** and use the first-loaded recognition model (`text_matcher.hpp` `defaultRecognitionModelId`, `matchNumber`). Note "first-loaded" is whatever recognition model the session actually loaded — for a tracking Number read you must still load *some* recognition model for number OCR to function (the detection model is always loaded; at least one recognition model must be present). The template treats `alphabet` as meaningful only for `TEXT`.

3. **Identity fan-out.** The same cloud `scenarioId` is assigned to N devices; each emits Observations tagged with its own `deviceId` under that shared `scenarioId` (Story 8). The device never invents a `scenarioId` — it persists the cloud's `scenarioId` in the `core:observation` table and stamps it on every Observation. The local `Scenario.id` (the real, autogenerated DB id of the hidden tracking row) is purely an engine detail and never leaves the device. Versioning the template (`version`, mirroring `ScenarioBackup.version`) lets the cloud evolve the schema and lets devices reject or migrate older shapes on pull.

### The `TrackingScenarioRepository` contract

The scheduler and worker depend on a `core:observation`-owned repository whose surface is defined here (the original draft called undefined methods):

- `suspend fun upsert(template: TrackingScenarioTemplate)` — persists the cloud template, materializes and writes the hidden `Scenario`/`ScreenEvent`/`ScreenCondition` graph into `ClickDatabase`, and records the `cloudScenarioId → localScenarioId` mapping.
- `suspend fun getActive(cloudScenarioId: String): TrackingScenario?` — the active definition (interval, burst duration), or null if stopped/deleted.
- `fun localScenarioId(cloudScenarioId: String): Identifier` — the resolvable DB-backed local id used by `setScenarioId`.
- `fun isProjectionLive(): Boolean` — proxies `smartProcessingRepository` state to tell whether the live foreground service still holds a projection.
- `suspend fun recordUnfulfilled(cloudScenarioId: String, reason: String)` — writes an `isFulfilled = false` Observation (used for `no_projection`, `engine_busy`, sleep gaps).
- `suspend fun requestRegrant(cloudScenarioId: String)` — flags the scenario for `feature:cloud` to surface "projection lost — re-grant required."

Because the template is small and declarative, `upsert(template)` on pull plus `scheduler.start(scenarioId, interval)` on a start command is all a device needs to begin contributing to a shared fleet time series — with no per-device authoring and no engine-graph serialization.

---

## TraxIntel Cloud — backend & dashboard

This section specifies **Trax Cloud**, the server-side counterpart to the on-device capture client. It is a clean-room, separately-licensed service: it shares **no GPLv3 code** with the Android app and lives in its own repository. The integration boundary is a **wire contract** (the REST/JSON API below), not shared source — nothing server-side imports the GPL app, and the device speaks to Trax Cloud only through that contract. This keeps the moat (fleet aggregation, cross-device time series, remote control) under a commercial license while the FOSS build stays pure GPLv3.

> **Current codebase state vs. what this plan adds.** Everything in this section that touches the Android app is a **new addition this plan introduces**, not existing infrastructure. To set expectations precisely against the repo as it stands today:
> - The product flavor system (`KlickrVariants.kt`) currently has **one dimension, `VERSION`**, and exactly **two flavors, `F_DROID("fDroid")` and `PLAY_STORE("playStore")`** — yielding variants `fDroidDebug` / `fDroidRelease` / `playStoreDebug` / `playStoreRelease`. There is **no `CONNECTIVITY` dimension and no `CLOUD`/`LOCAL` flavor**. This plan proposes **adding** a new connectivity dimension (or a new flavor) so the cloud networking can be fenced off from the FOSS build; that is net-new work in `KlickrVariants.kt` and `FlavourConventionPlugin.kt`.
> - Flavor-scoped dependencies today are gated through the **`playStoreImplementation`** extension in `DependencyHandlerScopeExt.kt` (used by `CrashlyticsConventionPlugin.kt`). There is **no `cloudImplementation` extension**; this plan **adds** one by copying that exact pattern (new `DependencyHandlerScope.cloudImplementation` function + a convention plugin that consumes it), which is the established way the codebase gates Crashlytics to `playStore` only.
> - There is **no networking module** in the repo. `core/` contains `common`, `dumb`, and `smart` only. This plan **introduces** a new module — referred to below as **`core:network` (new)** — that owns the offline queue, the wire DTOs, and the HTTP client. The shared DTOs are net-new Kotlin types; they do **not** exist yet (see "DTOs are new" below).
> - There is **no WorkManager dependency** anywhere (`libs.versions.toml` has no `androidx.work` entry; the codebase uses a static `LocalServiceProvider` + `AccessibilityService` foreground lifecycle, not background workers). Any periodic scheduling this plan needs is **net-new**: it requires adding `androidx.work` + `androidx.hilt:hilt-work` to the version catalog and a new module — referred to below as **`core:scheduling` (new)** — or, alternatively, driving polling from the existing `SmartProcessingRepositoryImpl` coroutine loop (which already supports an `autoStopDuration`). Neither `core:scheduling` nor a WorkManager scheduler exists today.

This is where the product moat lives: any single device can OCR a number, but only the cloud turns a fleet of observation streams into one chart, one assignable tracking scenario, and one remote stop button.

### Stack choice & justification

**Language/runtime: Kotlin + Ktor on the JVM.** The wire DTOs (`Observation`, `TrackingScenario`, a detection-area `Rect`, the OCR alphabet, the read-type enum) will be defined as **new** Kotlin/`kotlinx.serialization` types.

> **DTOs are new, not existing.** The app does **not** currently contain `Observation` or `TrackingScenario` types — those are cloud-side concepts this plan creates. What *does* exist and informs their shape: Room entities (`ScenarioEntity`, `ConditionEntity`, etc., all `@Serializable` per `ActionEntity.kt`/`ScenarioEntity.kt`) and runtime domain models (`Scenario`, `ScreenCondition.Number`/`.Text`/`.Color`/`.Image`, `OCRAlphabet`, `ConditionType`). The cloud DTOs are modeled *after* these — e.g. the device-side `ScreenCondition.Number.detectionArea` is an Android `Rect`, and `ScreenCondition.Text` carries an `OCRAlphabet` — but they are deliberately distinct, GPL-free types so the contract artifact carries no GPL domain code. The codebase already uses `kotlinx.serialization` everywhere (`@Serializable` on every entity), so the new DTOs follow the same convention.

Sharing the **DTO definitions** as a tiny standalone `traxintel-contract` artifact between the new `core:network` module and the server eliminates an entire class of client/server drift bugs and keeps one OpenAPI spec authoritative. Ktor is lightweight, coroutine-native (matches the app's `suspend`/Flow idioms), and trivial to containerize.

**Primary store: PostgreSQL 16 with the TimescaleDB extension.** Relational tables (`tenants`, `accounts`, `devices`, `tracking_scenarios`, `scenario_assignments`, `device_commands`) need real foreign keys and transactional integrity for tenant isolation; `observations` is an append-only, time-ordered, high-volume stream — exactly a Timescale hypertable. One database engine, two access patterns, no second datastore to operate for the MVP fleet size (3–20 devices, observations every ≥5s). If Timescale is unavailable, native Postgres declarative partitioning by `server_received_at` is the fallback; the schema does not change.

**Blob store: S3-compatible object storage** for optional observation crops. The device already produces PNGs through `BitmapRepository.saveImageConditionBitmap(bitmap, prefix)` (this plan reuses the convention with an `"Observation_"` prefix); we never put image bytes in Postgres. Crops upload via presigned PUT and are read via short-lived presigned GET. Note the device's local file path is **not** the S3 key: `ConditionBitmapsDataSource` writes `{prefix}{bitmapPixelHashCode}.png` under `context.filesDir`, so the device uploads *that file's bytes* to the presigned PUT URL; the S3 object key is server-assigned (see the schema's `crop_object_key`).

**Deployment: a single containerized Ktor app + managed Postgres/Timescale + an S3 bucket**, fronted by TLS. One service binary keeps MVP ops trivial; the four "services" below are logical modules inside it, cleanly separable later.

### Logical services

All four are modules inside the one Ktor deployment, sharing the connection pool and auth middleware.

- **Auth/Tenancy service** — handles tenant + first-account **bootstrap** (registration), issues and validates two credential types (account session JWTs for the dashboard, opaque device tokens for capture clients), owns enrollment-code minting/redemption, and enforces that every request resolves to exactly one `tenant_id`. This is the gatekeeper every other service calls.
- **Device Registry service** — tracks `devices` rows: enrollment, heartbeat/online status, reported `model`/`os`, device disable/revocation, and the authoritative per-device `tracking_enabled` state that the dashboard's start/stop toggles mutate (via the command queue).
- **Scenario Library service** — CRUD for `tracking_scenarios` plus `scenario_assignments` (which scenario is pushed to which device). This is the "author once, push to many" engine: a device's scenario-pull returns the union of scenarios assigned to it.
- **Ingest service** — the hot path. Accepts batched observation uploads, deduplicates by client idempotency key inside a transaction, writes to the `observations` hypertable, stamps `server_received_at`, and returns per-row accept/duplicate status plus crop-upload URLs. Must be idempotent and cheap.

### Data model

Postgres DDL. UUIDs everywhere for opaque, non-enumerable IDs (matters for the device-token authorization checks). `tenant_id` is denormalized onto `observations` so the hot ingest/query path never needs a join to enforce isolation.

```sql
CREATE TABLE tenants (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name         TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- one owner Account per Tenant in MVP (no RBAC; see Out of Scope).
-- Created at bootstrap via POST /v1/auth/register (see Auth section).
CREATE TABLE accounts (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    email          CITEXT NOT NULL UNIQUE,
    password_hash  TEXT NOT NULL,        -- argon2id, computed server-side at registration
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE devices (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name               TEXT,
    model              TEXT,             -- Build.MODEL reported on enroll
    os_version         TEXT,             -- e.g. "Android 14 (SDK 34)"
    token_hash         TEXT,             -- sha256 of opaque device token; NULL once revoked
    enabled            BOOLEAN NOT NULL DEFAULT true,   -- false disables/revokes the device
    tracking_enabled   BOOLEAN NOT NULL DEFAULT false,  -- authoritative fleet state
    last_seen_at       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- composite key target so child tables can enforce same-tenant linkage
    UNIQUE (tenant_id, id)
);
CREATE INDEX idx_devices_tenant ON devices(tenant_id);

-- short-lived pairing codes (User Story 1)
CREATE TABLE enrollment_codes (
    code         TEXT PRIMARY KEY,       -- short, human-typable e.g. "WQ7-3KD"
    tenant_id    UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    expires_at   TIMESTAMPTZ NOT NULL,
    redeemed_by  UUID REFERENCES devices(id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE tracking_scenarios (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name                  TEXT NOT NULL,
    read_type             TEXT NOT NULL CHECK (read_type IN ('NUMBER','TEXT','STATE')),
    -- detection area Rect in device screen-space (left/top/right/bottom),
    -- modeled on ScreenCondition.*.detectionArea (android.graphics.Rect)
    area_left             INT NOT NULL,
    area_top              INT NOT NULL,
    area_right            INT NOT NULL,
    area_bottom           INT NOT NULL,
    ocr_alphabet          TEXT NOT NULL DEFAULT 'LATIN', -- matches OCRAlphabet enum values
    match_text            TEXT,          -- for STATE: the ScreenCondition.Text target string
    poll_interval_ms      BIGINT NOT NULL CHECK (poll_interval_ms >= 5000),
    crop_capture_enabled  BOOLEAN NOT NULL DEFAULT false,
    revision              INT NOT NULL DEFAULT 1,  -- bumped on edit; device caches per-scenario
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- composite key target so assignments can enforce same-tenant linkage
    UNIQUE (tenant_id, id)
);
CREATE INDEX idx_scenarios_tenant ON tracking_scenarios(tenant_id);

-- which scenario is pushed to which device.
-- tenant_id is carried on the row and BOTH FKs reference the composite
-- (tenant_id, id) keys above, so the DB cannot link a scenario and a device
-- belonging to different tenants — schema-level cross-tenant guard.
CREATE TABLE scenario_assignments (
    tenant_id     UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    scenario_id   UUID NOT NULL,
    device_id     UUID NOT NULL,
    assigned_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (scenario_id, device_id),
    FOREIGN KEY (tenant_id, scenario_id)
        REFERENCES tracking_scenarios(tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, device_id)
        REFERENCES devices(tenant_id, id) ON DELETE CASCADE
);

-- remote start/stop queue (User Story 7).
-- Same composite-FK guard: a command cannot target a device in another tenant.
CREATE TABLE device_commands (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    device_id     UUID NOT NULL,
    command       TEXT NOT NULL CHECK (command IN ('START_TRACKING','STOP_TRACKING')),
    expires_at    TIMESTAMPTZ NOT NULL,   -- commands expire if device never comes online
    acked_at      TIMESTAMPTZ,            -- set when device confirms
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, device_id)
        REFERENCES devices(tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX idx_commands_pending ON device_commands(device_id)
    WHERE acked_at IS NULL;

-- append-only observation time-series
CREATE TABLE observations (
    id                UUID NOT NULL DEFAULT gen_random_uuid(),
    idempotency_key   TEXT NOT NULL,      -- client-generated, unique per reading
    tenant_id         UUID NOT NULL,
    device_id         UUID NOT NULL,
    scenario_id       UUID NOT NULL,
    device_captured_at TIMESTAMPTZ NOT NULL,
    server_received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    value             TEXT,               -- numeric serialized as text; raw OCR string for TEXT
    value_type        TEXT NOT NULL CHECK (value_type IN ('NUMBER','TEXT','STATE')),
    confidence        NUMERIC(5,2) NOT NULL CHECK (confidence BETWEEN 0 AND 100),
    is_fulfilled      BOOLEAN NOT NULL,
    crop_requested    BOOLEAN NOT NULL DEFAULT false, -- client asked to attach a crop
    crop_object_key   TEXT,               -- S3 key, null until the crop PUT is confirmed
    PRIMARY KEY (tenant_id, idempotency_key)   -- enforces idempotency per tenant
);
SELECT create_hypertable('observations', 'server_received_at');
CREATE INDEX idx_obs_series ON observations
    (tenant_id, scenario_id, device_id, device_captured_at DESC);
-- needed so the crop-by-id endpoint (GET /v1/observations/{id}/crop) is an
-- indexed, tenant-scoped, unique lookup despite id not being the PK.
CREATE UNIQUE INDEX idx_obs_id ON observations (tenant_id, id);
```

Two constraints carry most of the weight:

1. The composite primary key `(tenant_id, idempotency_key)` makes **duplicate suppression a database invariant**, not application logic that can be skipped.
2. The composite foreign keys on `scenario_assignments` and `device_commands` (referencing `(tenant_id, id)` on both `tracking_scenarios`/`devices`) make **cross-tenant linkage impossible at the schema level** — a bug in an assignment or command endpoint cannot cross-link Tenant A's scenario to Tenant B's device, because the row's single `tenant_id` must match both parents.

**Value and confidence storage.** The `value` column is `TEXT` deliberately: a NUMBER read stores its `numberDetected` Double as its string form; a TEXT read stores the recognized string; a STATE read stores the matched/unmatched outcome of a `ScreenCondition.Text` comparison. **`confidence` is `NUMERIC(5,2)`, not an integer** — this corrects a lossy mismatch: `ProcessedConditionResult.Screen.confidenceRate` is a **`Double`** (0–100), so an integer column would silently truncate the fractional part. `NUMERIC(5,2)` preserves two decimal places; the device sends the Double as-is and the wire DTO types it as a number.

**TEXT support is gated on non-trivial native work (call-out).** NUMBER reads already work end-to-end: `verifyNumberCondition` → `detectNumber` returns `numberDetected` as `array[6]` of the 7-element `DoubleArray` that `toJniResult` marshals in `jni_detection_result.cpp`. **TEXT does not** — the recognized string in `TextRecognizerResult.text` lives only in C++ and is discarded after fuzzy matching; the current JNI result is a `DoubleArray` with no string slot. Surfacing the recognized string therefore requires a **native marshalling change** (changing `detectTextNative`'s return type from `jdoubleArray` to a `jobject` carrying both the numeric array and a `jstring`, or adding a parallel string-returning JNI call), not a one-element array extension. **TEXT-type scenarios are blocked until that native change lands**; the schema and API accept `valueType: "TEXT"` so no migration is needed when it does, but the MVP can ship NUMBER (and STATE, which only needs the existing match boolean) first.

### REST API contract (`/v1`, OpenAPI-described)

All bodies are JSON. All non-bootstrap, non-enrollment endpoints require `Authorization: Bearer <token>`. Two token audiences: **account JWTs** (dashboard) and **device tokens** (capture clients). Every handler resolves the bearer to a `tenant_id` and scopes all SQL by it. Cross-tenant access returns `404` (not `403`) to avoid leaking existence.

#### 0. Tenant + first-account bootstrap — `POST /v1/auth/register`

Unauthenticated. This is **day-one onboarding**: it creates the very first `tenant` row and its single owner `account` in one transaction, so someone can actually get in before any login exists.

Request:
```json
{ "tenantName": "Acme Farms", "email": "owner@example.com", "password": "..." }
```
Response `201`:
```json
{ "accessToken": "<jwt>", "tenantId": "a09b...77", "expiresIn": 3600 }
```
The server computes `password_hash` with **argon2id** (server-side; the plaintext is never stored), inserts `tenants` then `accounts` in a single transaction, and returns a session JWT so registration flows straight into a logged-in dashboard. Duplicate email → `409 Conflict`. There is one owner account per tenant in the MVP (no invite/RBAC; see Out of Scope), so this endpoint is the only account-creation path.

#### 1. Device enrollment — `POST /v1/devices/enroll`

Unauthenticated; authorized by the pairing code itself (User Story 1). The device redeems the code; the server mints a device token bound to the code's tenant.

Request:
```json
{
  "pairingCode": "WQ7-3KD",
  "model": "Pixel 8",
  "osVersion": "Android 14 (SDK 34)",
  "deviceName": "Farm account #3"
}
```
Response `201`:
```json
{
  "deviceId": "8f1c...e2",
  "tenantId": "a09b...77",
  "deviceToken": "dvt_live_3f9a...",   // shown once; device persists it in DataStore
  "trackingEnabled": false
}
```
Errors: `410 Gone` for expired code, `404` for unknown/already-redeemed code — "invalid/expired codes rejected clearly" per acceptance. The server redeems atomically: `UPDATE enrollment_codes SET redeemed_by=$1 WHERE code=$2 AND redeemed_by IS NULL AND expires_at > now()`; zero rows updated ⇒ reject.

Codes are minted by the dashboard via `POST /v1/devices/enrollment-codes` (account-authed) returning `{ "code": "WQ7-3KD", "expiresAt": "..." }`.

#### 2. Heartbeat — `POST /v1/devices/heartbeat`

Device-authed. Updates `last_seen_at` (drives the dashboard's online dot) and returns the authoritative tracking state plus pending-command count so the client can self-correct.

**Cadence (defined).** The device sends a heartbeat on a fixed **30-second interval** while the app is running, independent of the per-scenario `poll_interval_ms` (which governs observation capture, ≥5s). Because observation batch uploads and command pulls also refresh `last_seen_at` server-side, a device that is actively uploading is always "fresh"; the dedicated 30s heartbeat exists so an *enrolled-but-idle* device (tracking off) still reports liveness. The dashboard's online rule is `last_seen_at > now() - 90s` (3× the heartbeat interval, tolerating one missed beat plus network jitter).

```json
// request
{ "trackingActive": true }
// response 200
{ "trackingEnabled": true, "pendingCommands": 1 }
```

#### 3. Observation batch upload — `POST /v1/observations:batch`

Device-authed. The core ingest path. Offline-queued observations from the new `core:network` module's offline queue batch-upload on reconnect (User Story 5). Each item carries the client-generated `idempotencyKey`.

Request:
```json
{
  "observations": [
    {
      "idempotencyKey": "obs_7b3f-0001",
      "scenarioId": "c4d2...aa",
      "deviceCapturedAt": "2026-06-29T10:00:00.000Z",
      "value": "10432",
      "valueType": "NUMBER",
      "confidence": 91.5,
      "isFulfilled": true,
      "hasCrop": false
    },
    {
      "idempotencyKey": "obs_7b3f-0002",
      "scenarioId": "c4d2...aa",
      "deviceCapturedAt": "2026-06-29T10:00:10.000Z",
      "value": null,
      "valueType": "NUMBER",
      "confidence": 12.0,
      "isFulfilled": false,
      "hasCrop": true
    }
  ]
}
```

The second item demonstrates User Story 3's contract: a **failed read still records an observation** with `isFulfilled=false` and a confidence value. The device builds these at the `SmartProcessingListener.onScreenConditionProcessingCompleted(result: ProcessedConditionResult.Screen)` hook, reading `result.isFulfilled`, `result.haveBeenDetected`, and `result.confidenceRate` (a **Double**, 0–100), augmented by the recognized number from the detection result (and the recognized string once the TEXT JNI change lands).

Response `200` — per-row status so partial batches are unambiguous, and a crop-upload URL whenever the row needs one:
```json
{
  "results": [
    { "idempotencyKey": "obs_7b3f-0001", "status": "ACCEPTED", "observationId": "..." },
    { "idempotencyKey": "obs_7b3f-0002", "status": "ACCEPTED", "observationId": "...",
      "cropUploadUrl": "https://s3.../presigned-put?..." }
  ]
}
```

Server-side insert:
```sql
INSERT INTO observations (idempotency_key, tenant_id, device_id, scenario_id,
    device_captured_at, value, value_type, confidence, is_fulfilled, crop_requested)
VALUES (...)
ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
RETURNING id;
```
Empty `RETURNING` ⇒ the row already existed (a replay). This yields **zero server-side duplicates** even if the device retries a half-acked batch after a network drop (acceptance gate). `scenario_id` is verified to be assigned to the calling device; an observation for an unassigned scenario is rejected `409`. The device token authorizes writing **only its own** `device_id` — `device_id` is taken from the token, never from the request body (User Story 9).

**Crop handling that survives a dropped ACK (idempotency fix).** A crop must still upload even when the original `ACCEPTED` response (carrying the presigned PUT URL) was lost to a network drop and the retry now returns a duplicate. The rule, evaluated **per result row regardless of ACCEPTED vs. DUPLICATE**:

- A `cropUploadUrl` is returned for any row where `hasCrop=true`, the scenario's `crop_capture_enabled` is set, **and `crop_object_key IS NULL`** (the crop is not yet stored). This is true on first insert *and* on a replayed duplicate whose crop never landed.
- So a retried batch after a drop returns `status: "DUPLICATE"` **with** a fresh `cropUploadUrl`, and the device completes the upload it lost. Presigned URLs are short-lived; a stale one simply yields another on the next retry.
- Once the device confirms the PUT, it calls `POST /v1/observations/{id}/crop:confirm` (or the server confirms via an S3 event), which sets `crop_object_key`. After that, the row is "complete" and no further upload URL is issued.

If the device finds it still holds an un-uploaded crop for an already-accepted observation (e.g. app restart), it can directly request `POST /v1/observations/{id}/crop:presign` to re-obtain a PUT URL without re-sending the whole batch. This closes the exact failure mode idempotency is meant to handle.

#### 4. Scenario pull — `GET /v1/scenarios`

Device-authed. Returns scenarios assigned to the calling device (the join through `scenario_assignments`), implementing "author once, push to many."

**Scenario-id lifecycle (the device authors nothing).** The full chain is: the dashboard **creates** a scenario (`POST /v1/scenarios`, server mints the UUID) → **assigns** it to devices (`POST /v1/scenarios/{id}/assignments`) → the device **pulls** this endpoint and learns the server-generated `scenario_id` UUIDs → the device **tags every observation** it uploads with that exact UUID. The device never invents a scenario id; an observation whose `scenarioId` is not currently assigned to the device is rejected `409`. A freshly enrolled device therefore performs a scenario pull **before** its first observation upload.

**Revision semantics (per-scenario).** Each scenario carries its own independent `revision`. The response returns the current `revision` per scenario, and the device caches scenarios keyed by `(id, revision)`. To avoid the ambiguity of a single global `since_revision` against independently-counting scenarios, the device sends the revisions it already holds and the server returns only changed/new/removed entries:
```json
// request
{ "knownRevisions": { "c4d2...aa": 2, "e7f1...bb": 5 } }
// response 200
{
  "scenarios": [
    {
      "id": "c4d2...aa",
      "name": "Gold counter",
      "readType": "NUMBER",
      "detectionArea": { "left": 120, "top": 40, "right": 260, "bottom": 96 },
      "ocrAlphabet": "LATIN",
      "matchText": null,
      "pollIntervalMs": 10000,
      "cropCaptureEnabled": false,
      "revision": 3
    }
  ],
  "removedScenarioIds": ["e7f1...bb"]
}
```
A scenario whose cached revision equals the server's is omitted (unchanged); a bumped revision is returned in full; an unassigned/deleted scenario appears in `removedScenarioIds`. `detectionArea` maps directly onto the device's `ScreenCondition.*.detectionArea` (`Rect`); `ocrAlphabet` maps to the `OCRAlphabet` enum; `pollIntervalMs` feeds the device's periodic capture scheduler (the new `core:scheduling` module or the existing `SmartProcessingRepositoryImpl.startDetection` coroutine loop with `autoStopDuration`).

#### 5. Remote command pull/ack — `GET /v1/commands` and `POST /v1/commands/{id}:ack`

Device-authed. The device pulls pending commands on each poll cycle (or piggybacked on heartbeat's `pendingCommands` count), acts within one cycle (User Story 7), then acks.
```json
// GET /v1/commands -> 200
{ "commands": [ { "id": "cmd_91...", "command": "STOP_TRACKING" } ] }
```
Ack flips device state authoritatively (in one transaction):
```sql
UPDATE device_commands SET acked_at = now() WHERE id = $1 AND device_id = $2;
UPDATE devices SET tracking_enabled = $newState WHERE id = $2;
```
Commands carry `expires_at`; a device offline past expiry never sees a stale stop, and the dashboard reconciles the toggle when a command expires unacked (see dashboard). The dashboard enqueues commands via `POST /v1/devices/{id}/commands` (account-authed) with `{ "command": "STOP_TRACKING" }`; enqueuing is idempotent in the sense that a redundant STOP on an already-stopped device is a no-op the device acks immediately.

#### 6. Account login — `POST /v1/auth/login`
```json
// request
{ "email": "owner@example.com", "password": "..." }
// response 200
{ "accessToken": "<jwt>", "tenantId": "a09b...77", "expiresIn": 3600 }
```
The server verifies the password against the stored **argon2id** hash. Dashboard read endpoints (account-authed, all tenant-scoped): `GET /v1/devices`, `GET /v1/scenarios` (full tenant list, distinct from the device-scoped variant via token audience), `GET /v1/observations?scenarioId=&deviceIds=&from=&to=`, `GET /v1/observations/latest?scenarioId=` (latest-per-device), and `GET /v1/observations/{id}/crop` (returns a presigned GET URL; the lookup is `WHERE tenant_id = :jwtTenant AND id = :id`, served by the unique index `idx_obs_id`).

#### 7. Device lifecycle management — disable / revoke

Account-authed. Because the dashboard lists devices, the MVP needs a way to retire one:
- `DELETE /v1/devices/{id}` (or `POST /v1/devices/{id}:disable`) sets `devices.enabled = false` and clears `token_hash`. The device's token immediately stops authorizing any device-authed endpoint (auth middleware rejects a token whose device is disabled or whose `token_hash` is null) — this is the revoke path for a lost/compromised device. The row is retained (with its observations) for historical charts; a hard delete cascades.
- Re-enrollment: a disabled device simply redeems a fresh pairing code, producing a new `devices` row (or, optionally, the dashboard can issue a new code that re-points to the existing row).

### Auth & multi-tenancy mechanics

- **Device tokens** are opaque random strings (`dvt_live_…`); only their SHA-256 hash is stored (`devices.token_hash`). A device token resolves to exactly one `(tenant_id, device_id)`. Authorization rules: a device may write observations only for its own `device_id`, may read only scenarios assigned to it, and may read only its own commands. None of these accept a device or tenant id from the request body — they are derived from the token, closing the cross-tenant write hole (acceptance gate: "device tokens write only their own observations"). **Revocation**: clearing `token_hash` / setting `enabled = false` instantly invalidates the token (no token TTL needed for the MVP).
- **Account JWTs** carry `tenant_id` as a claim, signed server-side with a symmetric key held only by the server. Every dashboard query appends `WHERE tenant_id = :jwtTenant`. There is one owner account per tenant (no RBAC), so authorization collapses to "is this row's tenant the caller's tenant." **Key rotation**: the JWT signing key is configurable and supports a `kid` (key id) header so a new key can be introduced while the previous one still validates outstanding tokens until they expire (`expiresIn` 3600s); compromised-key rotation is a config push that retires the old `kid`.
- **Isolation is enforced at the query layer plus the schema layer.** Composite foreign keys keep `scenario_assignments` and `device_commands` within a single tenant (a row's `tenant_id` must match both parents). `tenant_id` is on every table including the hypertable so even the hot path filters by tenant without a join. As defense-in-depth, the DB role used by request handlers can run under Postgres Row-Level Security with a `current_setting('app.tenant_id')` policy set per transaction — guaranteeing a query that forgets its `WHERE` still returns nothing cross-tenant. This satisfies "Tenant A cannot touch Tenant B's data."

### Idempotent ingest — end to end

The device assigns each reading a stable `idempotencyKey` at capture time (e.g. `"obs_" + deviceId.short + "_" + monotonicSeq`) and persists it in the offline queue **before** any upload attempt. On reconnect it replays the queue; the server's `ON CONFLICT … DO NOTHING` makes replays harmless. A row is only dequeued once the device has received a terminal acknowledgement for the key (`ACCEPTED` or duplicate) **and**, if a crop was requested, the crop has been uploaded (or the server confirmed it was already stored). This gives at-least-once delivery from the client collapsing to exactly-once storage on the server — including the crop, because a dropped ACK on a crop-bearing row still yields a re-issued `cropUploadUrl` on retry (see Section 3).

### MVP web dashboard

A single-page app (React + a charting lib such as Recharts/visx) talking only to the account-authed `/v1` endpoints. Six views, matching the contract's dashboard scope:

- **Register / Login** — `POST /v1/auth/register` bootstraps a tenant + owner account on day one; `POST /v1/auth/login` returns a JWT thereafter, stored in memory + an httpOnly refresh cookie. Establishes the tenant context for every subsequent call.
- **Devices view** — table from `GET /v1/devices`: name, model, OS, an online dot derived from `last_seen_at` (green if `> now() - 90s`), and a per-device tracking toggle. Toggling enqueues `START_TRACKING`/`STOP_TRACKING` via `POST /v1/devices/{id}/commands` and optimistically reflects state; the row reconciles to `tracking_enabled` once the device acks (User Story 7). **If a command expires unacked** (the device never came online before `expires_at`), the optimistic toggle **visibly reverts and shows a "command expired — device offline" badge** rather than silently staying optimistic. A "Pair device" button mints an enrollment code and displays it for typing into the app; a per-row "Disable" action calls the revoke path.
- **Scenario view** — authoring/editing a tracking scenario (read type, detection area, alphabet, poll interval ≥5s, crop toggle) and an assignment panel (multi-select devices → writes `scenario_assignments`). This is the "author once, push to many" surface. Editing bumps that scenario's `revision` so devices re-pull. (TEXT read type is surfaced but flagged "requires text recognition update" until the native JNI change ships.)
- **Observation time-series chart** — the centerpiece. `GET /v1/observations?scenarioId=…&deviceIds=…&from=…&to=…` feeds a line/scatter chart of `value` over `device_captured_at`, **one series per device** (the canonical "gold across a fleet on one chart"). Filters: device multi-select and time range. **Low-confidence points are visually distinguished** — points below a confidence threshold render hollow/desaturated (acceptance gate). NUMBER values plot numerically; TEXT/STATE render as a stepped categorical track. A latest-value summary table (`GET /v1/observations/latest`) sits beside the chart showing the most recent value + confidence + age per device.
- **Crop viewer** — clicking an observation point opens its cropped PNG via the presigned `GET /v1/observations/{id}/crop` URL (indexed, tenant-scoped lookup), so the operator can eyeball what the OCR actually saw when confidence was low.

There is deliberately **no alerting, no thresholds, no live screen mirroring, and no CSV export** in the MVP dashboard — those are out of scope. The dashboard observes and controls; it does not capture and does not act on values.

### How this realizes the moat

The on-device app, in its FOSS form, is a competent single-screen reader. Trax Cloud is what no local app can replicate: it fans one cloud-authored tracking scenario out to N devices, collapses N independent capture streams into one tenant-isolated time series keyed by `(scenario_id, device_id)`, and pushes start/stop down to the whole fleet within a poll cycle — all behind a commercial license cleanly severed from the GPLv3 capture client by the **new** connectivity flavor boundary this plan introduces. The idempotent, offline-tolerant ingest contract (crops included) is what makes the fleet data trustworthy enough to be a product rather than a demo.

---

## Rebrand, build, flavors & release engineering

This section turns the "Klick'r -> TraxIntel" rename and the "ship a connected build alongside the GPLv3 builds" requirement into a concrete, file-by-file work plan. It is grounded in the existing obfuscation/flavor machinery so that the rebrand costs no architectural churn and the cloud build slots cleanly into the established convention-plugin system. Line numbers below are pinned to the current `smartautoclicker/build.gradle.kts` (versionCode 87 / versionName `4.0.0-beta02`); treat them as anchors, not guarantees, since that file evolves.

### Rebrand scope: what actually changes vs. what does not

The critical insight from the codebase is that **"Klick'r" is almost entirely a presentation-layer string, not a code identifier.** The brand surfaces in exactly three kinds of place:

1. The single source-of-truth resource `app_name` in `smartautoclicker/src/main/res/values/strings.xml:20`:
   `<string name="app_name" translatable="false">Klick\'r</string>`. This is the value the launcher label resolves to, because `AndroidManifest.xml` declares `android:label="${appName}"` and `smartautoclicker/build.gradle.kts:43` wires `appNameResId = "@string/app_name"` into the obfuscation plugin's `setup()` call. The obfuscation plugin then registers the `appName` manifest placeholder from that resource (`ObfuscationPlugin.kt` lines 142-146), or substitutes a random 10-char string when randomization is on.
2. Hardcoded user-visible literals in Kotlin and XML — the "~17 refs." A scan for the brand literal (excluding the build-system enum identifiers, which we keep — see below) finds the load-bearing ones:
   - `core/smart/processing/.../domain/SmartProcessingRepositoryImpl.kt:90` — wake-lock tag `"Klickr::Detection"`.
   - `core/smart/processing/.../data/processor/ActionExecutor.kt:270` — notification fallback title `notification.name ?: "Klick'r"`.
   - `smartautoclicker/src/main/res/values/strings.xml:89` and `:143` — copy embedding the brand ("Klick\'r won\'t be able to run...", "Klick\'r images format have changed...").
   - The remaining occurrences are the same `Klick\'r` literal repeated across the translated `strings.xml` files (see item 3) and copy strings in `core/common/*` modules' `values/strings.xml`. Each must be retargeted to the new brand; the wake-lock tag at line 90 is internal-only but should still be renamed for log hygiene (`"TraxIntel::Detection"`).
3. The **11 translated `strings.xml`** copies under `smartautoclicker/src/main/res/` (`values-ru`, `values-zh-rTW`, `values-it`, `values-zh-rCN`, `values-ja`, `values-pt-rBR`, `values-es`, `values-fr`, `values-uk`, `values-ar`, plus the default `values`). Because `app_name` is `translatable="false"`, the brand token itself is identical across locales, but the *copy strings* that embed "Klick'r" (the analogues of lines 89/143) are translated and must each be updated. The same applies to the parallel `strings.xml` sets in `core/common/ui`, `core/common/permissions`, `core/common/actions`, `core/common/quality`, `core/common/overlays`, and `feature/notifications`.

What we explicitly **do not** rename (per the MVP Scope Contract "Naming, Identifiers & Build Strategy"): the Gradle build identifiers `KlickrDimension`, `KlickrFlavour`, `KlickrBuildType` in `build-logic/convention/src/main/kotlin/com/buzbuz/gradle/convention/model/KlickrVariants.kt`, and `Project.isBuildForVariant()` in `ProjectExt.kt`. These are internal build-DSL names with no user or runtime visibility. Renaming them would touch `smartautoclicker/build.gradle.kts` (imports at lines 18-19; uses at lines 44-45, 64, 73, 94, 113, 136), `FlavourConventionPlugin.kt`, and every module's build file for zero product benefit. They stay as `Klickr*` — a harmless historical artifact, exactly like the package namespace.

### Icon assets

The launcher icon is referenced from `AndroidManifest.xml` as `@mipmap/ic_smart_auto_clicker` (square) and `@mipmap/ic_smart_auto_clicker_round`. The on-disk asset set is the "~12 icon assets":

- 6 for the square icon: `mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_smart_auto_clicker.png` (5 densities) plus the adaptive vector `mipmap-anydpi-v26/ic_smart_auto_clicker.xml`.
- 6 for the round icon: the same 5 densities `..._round.png` plus `mipmap-anydpi-v26/ic_smart_auto_clicker_round.xml`.
- Two debug overrides: `smartautoclicker/src/debug/res/mipmap-anydpi-v26/ic_smart_auto_clicker{,_round}.xml`.

The `anydpi-v26` adaptive icon decomposes into three drawables (`ic_smart_auto_clicker.xml`):
```xml
<adaptive-icon ...>
    <background android:drawable="@color/primary"/>
    <foreground android:drawable="@drawable/ic_smart_auto_clicker_foreground"/>
    <monochrome android:drawable="@drawable/ic_smart_auto_clicker_monochrome"/>
</adaptive-icon>
```
So the real art swap is: replace the `@drawable/ic_smart_auto_clicker_foreground` and `@drawable/ic_smart_auto_clicker_monochrome` vectors, re-tint `@color/primary`, and re-render the 10 raster PNGs from the new TraxIntel mark at the 5 densities. **Recommendation: keep the resource *names* (`ic_smart_auto_clicker*`) and only swap pixel content.** Renaming the resources would force edits to both manifest lines and the two debug XMLs for no benefit, mirroring the namespace-stability principle. The new artwork should ship a fresh foreground/monochrome pair plus updated `@color/primary` if the brand color changes.

### applicationId vs. namespace decision

The build separates these two concepts deliberately (`smartautoclicker/build.gradle.kts`):
- `namespace = "com.buzbuz.smartautoclicker"` (line 50) — fixed; determines the generated `R` class package and the manifest package.
- `applicationId = getExtraActualApplicationId()` (line 58) — dynamic, sourced from the obfuscation plugin's `setup(applicationId = "com.buzbuz.smartautoclicker", ...)` at lines 41-42, optionally randomized when `shouldRandomize` is true. Note the existing `shouldRandomize` expression (lines 44-45) is `buildParameters.randomizeAppId.typedValue && project.isBuildForVariant(KlickrFlavour.F_DROID)` — i.e. randomization is *already gated to F-Droid only*, which is convenient for us (the cloud flavor never randomizes by construction).

**Decision: change the `applicationId`, keep the `namespace`.** Rationale:

- The Play Store identity of an app is its `applicationId`. A genuinely new product (TraxIntel, a connected fleet tool) should not collide with or masquerade as the existing Klick'r listing, so the connected build needs a distinct package on the store — e.g. `com.traxintel.app` (or `com.buzbuz.traxintel` to retain the existing publisher org).
- The `namespace` must stay `com.buzbuz.smartautoclicker` because changing it would relocate the generated `R` class and every `import com.buzbuz.smartautoclicker.R` across dozens of modules, and would churn the ProGuard/obfuscation keep rules and the build-time `ComponentConfig` generation (`ConfigFileContentBuilder.kt`, which emits `ORIGINAL_APP_ID` and the obfuscated `ComponentName`s consumed by `AppComponentsManager`). The Scope Contract is explicit: "TraxIntel is a brand/product rename, not a package rename, to avoid churning the R file and the obfuscation pipeline."
- These are orthogonal in Gradle: `applicationId` is the install/store identity; `namespace` is the compile-time package. AGP fully supports `applicationId != namespace`, and this codebase already exploits the split (the obfuscation plugin can randomize `applicationId` to a string like `abc.def.ghi` while `namespace` stays constant; the F-Droid debug build even appends `applicationIdSuffix = ".debug"` at line 67).

Because the cloud build is a new flavor (below), the cleanest expression is a **per-flavor `applicationId`**: leave the base `com.buzbuz.smartautoclicker` for the `LOCAL` flavor (preserving the existing F-Droid/Play listings) and override `applicationId` in the `cloud` flavor's `productFlavor` block to the TraxIntel package. Because the existing `shouldRandomize` gate already excludes anything but F-Droid, the cloud release build will not randomize its `applicationId`; the store identity stays stable and matches what Firebase/Play expect. (If a future cloud variant ever combined with F-Droid, `shouldRandomize` would need to additionally exclude `KlickrFlavour.CLOUD` — worth a guard.)

**versionCode / versionName.** `defaultConfig` sets a single `versionCode = 87` / `versionName = "4.0.0-beta02"` (lines 60-61) shared across all flavors (the F-Droid path further multiplexes this per-ABI in `androidComponents.onVariants`, lines 113-130). This shared plumbing is fine for the rebrand: `playStoreLocalRelease` (old Klick'r `applicationId`) and `playStoreCloudRelease` (new TraxIntel `applicationId`) are **separate Play listings keyed by distinct `applicationId`s**, so there is no versionCode collision between them — versionCode uniqueness is enforced per package, not globally. No versionCode change is required for the rebrand itself.

### Firebase / FCM project setup

Today, Firebase is already a distribution-scoped concern. `CrashlyticsConventionPlugin.kt` injects the Firebase BOM, Crashlytics-ktx and Crashlytics-ndk only via `playStoreImplementation { ... }` (lines 51-55), and the `google-services.json` config file lives only at `smartautoclicker/src/playStore/google-services.json` — there is no F-Droid Firebase footprint (GPLv3 compliance). Two subtleties to carry forward, because the section's plan mirrors this pattern:

- The `CrashlyticsConventionPlugin` itself is **not** self-guarded on a variant — it unconditionally applies `google.crashlytics` + `google.gms` and adds the `playStoreImplementation` deps whenever it is applied (lines 36-55). The variant gating is done one level up, in the *app* build file: `smartautoclicker/build.gradle.kts:136-138` conditionally `apply(plugin = libs.plugins.buzbuz.crashlytics...)` only when `isBuildForVariant(KlickrFlavour.PLAY_STORE, KlickrBuildType.RELEASE)`. We will reuse this two-level shape for cloud.
- `isBuildForVariant` is **task-name-substring matching, not real variant resolution** (`ProjectExt.kt:46-56` + the `KlickrFlavour`/`KlickrBuildType` name builder): it normalizes the variant name with `uppercaseFirstChar()` and returns true iff some `gradle.startParameter.taskRequests` arg `contains` that substring. Two consequences the cloud plan must respect: **(a)** during Android Studio sync (and any non-task Gradle invocation) there are no task requests, so the guard returns false and the gms plugin is *not* applied — exactly as Crashlytics behaves today; cloud Firebase config therefore only materializes during explicit `assemble*Cloud*` (or `bundle*Cloud*`) task runs, which is a known footgun of this pattern, not a bug. **(b)** Substring matching means `assemblePlayStoreCloudRelease` matches `"Cloud"`, but it would also match any future flavor whose name *contains* `"Cloud"`; with the `LOCAL`/`CLOUD` pair this is currently unambiguous, but new connectivity values must avoid substring overlap.

FCM is a natural fit for User Story 7 ("command acted on within one poll cycle of the device coming online"): the FCM message is a wake-up nudge, after which the device polls the command queue.

Setup steps:
1. **Create a new Firebase project** "TraxIntel" in the Firebase console (separate from any existing Klick'r project) and register an Android app with the **TraxIntel `applicationId`** chosen above (`com.traxintel.app`). The package registered in Firebase must equal the `applicationId`, not the `namespace`.
2. **Download `google-services.json`** and place it under the **connectivity-flavor** source set `smartautoclicker/src/cloud/google-services.json` (not the two-dimension combination `playStoreCloud`). Rationale: the google-services plugin searches a documented set of source-set directories including `src/<flavor>/`, and the FCM config is logically *connectivity-scoped*, not distribution-scoped. Placing it under `src/cloud/` lets **every** `…Cloud…` build resolve config — both `playStoreCloud` and the buildable-but-unpublished `fDroidCloud`. If it were placed under `src/playStoreCloud/` only, a `fDroidCloud*` build would fail to find `google-services.json`, contradicting the "fDroidCloud is buildable" claim below. The existing `src/playStore/google-services.json` stays untouched so the current Crashlytics-only Play build still resolves its config. (If a dedicated cloud *debug* Firebase project is ever used, add `smartautoclicker/src/cloudDebug/google-services.json`.)
3. **Enable FCM and Firebase Auth (optional)** in the new project. FCM is the transport nudge; the device-scoped token from enrollment (Scope Contract "Device — device-scoped token") authorizes the actual REST calls.
4. **Add the FCM dependency, scoped to the cloud flavor**, using the Gradle-auto-generated `cloudImplementation` configuration (next subsection) so the `firebase-messaging` artifact links only into `cloud` variants. Be precise about *why this is GPLv3-safe*: `cloudImplementation` is a **connectivity-dimension flavor configuration**, so it links into **both** `fDroidCloud*` and `playStoreCloud*` — it does *not* by itself exclude Firebase from F-Droid. The GPLv3 guarantee comes from **distribution policy: F-Droid only ever builds `fDroidLocal*` and never `fDroidCloud*`** (and `fDroidLocal*` links no network/Firebase deps at all). The acceptance gate is "the *published* F-Droid artifact is `fDroidLocalRelease`," not "the cloud configuration is unreachable."
5. **Apply the google-services Gradle plugin conditionally.** The plugin alias already exists in the catalog (`gradle/libs.versions.toml:152`: `googleGms = { id = "com.google.gms.google-services", version.ref = "googleServices" }`). It must only be applied for the cloud variant so it does not demand a `google-services.json` for `local`/`fDroid` builds. This is done in the new `CloudConventionPlugin` (below), gated either inside the plugin via `project.isBuildForVariant(KlickrFlavour.CLOUD)` or — mirroring the Crashlytics shape exactly — via a conditional `apply(...)` in `smartautoclicker/build.gradle.kts` next to line 136. Either way the same `isBuildForVariant` caveats from above apply (no application during IDE sync).

### Flavor strategy: second dimension, not a new flavor on the old axis

Today there is one dimension. `KlickrDimension` has a single entry `VERSION("version")` and `KlickrFlavour` has `F_DROID("fDroid", VERSION)` and `PLAY_STORE("playStore", VERSION)` (`KlickrVariants.kt`). `FlavourConventionPlugin.kt` iterates `KlickrDimension.entries` to declare `flavorDimensions` and `KlickrFlavour.entries` to declare `productFlavors`, and — critically — applies this **identically to both the `androidApp` block (lines 31-43) and the `androidLib` block (lines 45-56)**.

**Decision: add a second, orthogonal dimension `CONNECTIVITY` rather than adding a third value on `VERSION`.** Connectivity (local vs. cloud) is independent of distribution channel (fDroid vs. playStore). A single-axis approach would force impossible/meaningless flavors and could not express "Play-Store-distributed connected build." A second dimension produces the clean 2×2 *channel × connectivity* matrix the contract requires: `playStoreCloudRelease`, `fDroidLocalRelease`, etc.

Add to `KlickrVariants.kt`:
```kotlin
enum class KlickrDimension(val flavourDimensionName: String) {
    VERSION("version"),
    CONNECTIVITY("connectivity");
}

enum class KlickrFlavour(val flavourName: String, val dimension: KlickrDimension) {
    F_DROID("fDroid", KlickrDimension.VERSION),
    PLAY_STORE("playStore", KlickrDimension.VERSION),
    LOCAL("local", KlickrDimension.CONNECTIVITY),   // default: no network
    CLOUD("cloud", KlickrDimension.CONNECTIVITY);   // links core:network + feature:cloud
}
```

#### The dual-dimension library-module impact (must address before this compiles)

This is the single biggest build-correctness risk and it is **not** confined to the app module. Because `FlavourConventionPlugin` mirrors its logic into the `androidLib` block, adding `CONNECTIVITY` declares a new dimension and `local`/`cloud` flavors on **every library module that applies `buzbuz.flavour`** — roughly 30 modules. That has two effects that must be designed for:

1. **Variant count doubles for every library module** (each now has `local`/`cloud` × existing build types). That is acceptable cost-wise but means every inter-module dependency must resolve the connectivity dimension.
2. **App→library dependency resolution must pick a connectivity value.** When the app requests `playStoreCloudRelease`, AGP needs each library dependency to expose a matching `cloud` variant. Conversely, a plain `implementation(project(":core:common:base"))` from the app's `cloud` variant needs `base` to have a `cloud` variant to match — which it now does, so that resolves. The danger is *partial* dimension coverage.

**Resolution (required):** make `local` the de-facto connectivity default everywhere via `missingDimensionStrategy`, and understand it is a *different* mechanism from `isDefault`:

- `isDefault` (a.k.a. `getIsDefault().set(true)`) only chooses which flavor the IDE / `assembleDebug` selects when the user specifies none. It does **not** help cross-module resolution.
- `missingDimensionStrategy("connectivity", "local")` tells a consumer how to resolve a *producer* dependency that is missing (or ambiguous on) the connectivity dimension, picking `local` as the fallback. This is the mechanism that keeps inter-module resolution stable.

Concretely, in `FlavourConventionPlugin` add, inside both the `androidApp` and `androidLib` `defaultConfig` blocks:
```kotlin
defaultConfig {
    missingDimensionStrategy(
        KlickrDimension.CONNECTIVITY.flavourDimensionName,
        KlickrFlavour.LOCAL.flavourName,
    )
}
```
and mark `local` as the IDE default inside the `productFlavors` loop:
```kotlin
create(flavour.flavourName) {
    dimension = flavour.dimension.flavourDimensionName
    if (flavour == KlickrFlavour.LOCAL) isDefault = true
}
```
With this in place, `cloudImplementation(project(":core:network"))` resolves correctly: the app's `cloud` variant requests the `core:network` library's `cloud` variant on the connectivity dimension (both libraries carry it because the plugin applied it uniformly), and any module that legitimately has no cloud-specific source still resolves through the `local` fallback. The 2×2 *channel × connectivity* matrix at the app level is therefore really "2×2 at the app, 1×(per build type) connectivity expansion across ~30 library modules" — and that expansion is exactly what `missingDimensionStrategy` is for.

The cloud answer to "does cloud live in playStore flavor only?": **No.** Cloud is its own dimension. The connected build is `xCloud` where `x` is any `VERSION` flavor. In practice the shippable connected product is `playStoreCloudRelease`; `fDroidCloud*` is *buildable* (it can resolve `src/cloud/google-services.json`) but typically not published — the value of separating dimensions is that GPLv3 `fDroidLocalRelease` and `playStoreLocalRelease` keep compiling with **no network module linked at all** (a contract acceptance gate).

### Flavor-scoped dependency gating: external libs vs. project deps

The connected modules (`core:network`, `feature:cloud`) and their transitive network deps (Retrofit/OkHttp, kotlinx-serialization converter, `firebase-messaging`, WorkManager wiring) must link only into the `cloud` flavor. There are **two distinct mechanisms**, and the existing codebase only provides one of them:

1. **External libraries via a convention-plugin extension.** `DependencyHandlerScopeExt.kt` (lines 23-39) defines a small set of typed helpers, each taking a `Provider<MinimalExternalModuleDependency>` — including `playStoreImplementation` (line 26). **There is no `ProjectDependency` overload of `playStoreImplementation` anywhere in the file** (verified). So the existing extension is used *only* for external/catalog libraries, e.g. how `CrashlyticsConventionPlugin` adds the Firebase BOM. To gate external cloud libs, add **one** mirrored overload alongside the others:
   ```kotlin
   // DependencyHandlerScopeExt.kt — the ONLY mirror needed for external libs
   internal fun DependencyHandlerScope.cloudImplementation(dependency: Provider<MinimalExternalModuleDependency>) =
       add("cloudImplementation", dependency)
   ```
   This matches the existing `playStoreImplementation` signature exactly. No `ProjectDependency` overload is being "mirrored," because none exists to mirror.

2. **Project (`project(...)`) dependencies via Gradle's auto-generated configuration.** Once the `cloud` flavor exists, Gradle **auto-creates** a string-keyed `cloudImplementation` configuration. You wire project deps against it directly in the app's own `dependencies { }` block — the convention-plugin extension is **not involved** for project deps:
   ```kotlin
   // smartautoclicker/build.gradle.kts — resolves via the auto-generated configuration
   dependencies {
       "cloudImplementation"(project(":core:network"))
       "cloudImplementation"(project(":feature:cloud"))
       "cloudImplementation"(project(":core:observation"))  // network upload path is cloud-only
       // ...existing implementation(project(...)) entries unchanged
   }
   ```
   (The string-invocation form `"cloudImplementation"(project(...))` is the idiomatic Kotlin-DSL way to target an auto-generated configuration; if a typed helper is preferred, a *net-new* `ProjectDependency` overload could be added to `DependencyHandlerScopeExt.kt`, but that is genuinely new code, not a mirror of anything that exists.)

`core:scheduling` (the new WorkManager polling scheduler) is needed by *both* connectivity flavors if local interval capture is offered, so its WorkManager dependency is a plain `implementation` and the module is linked in local builds too. To make that compile, `core:scheduling`'s cloud-only *upload* coupling must sit behind an interface with a **no-op local binding** (the `cloud` flavor supplies the real network-backed implementation; the `local`/default flavor supplies a no-op). The precise local-vs-cloud split of `core:observation` and the sync interface is detailed in the "Data contracts & schemas" section; locally, observations are still captured via `SmartProcessingListener.onScreenConditionProcessingCompleted` and persisted through the migration-21→22 Room entity, and only the *sync/upload* path is `cloud`-gated.

### New convention plugin and version-catalog additions

Following the established "new convention plugin" recipe (`build-logic/convention/build.gradle.kts` `gradlePlugin { register(...) }` + a `[plugins]` alias in `libs.versions.toml`):

1. **`CloudConventionPlugin`** in `build-logic/convention/src/main/kotlin/com/buzbuz/gradle/convention/plugins/CloudConventionPlugin.kt`, modeled on `CrashlyticsConventionPlugin`. Because the Crashlytics plugin is itself unconditional and gated at the call site, you can do the same for cloud (gate at `build.gradle.kts`), or self-guard inside the plugin. If self-guarding, remember the `isBuildForVariant` caveats (no application during IDE sync). A self-guarding version:
   ```kotlin
   class CloudConventionPlugin : Plugin<Project> {
       override fun apply(target: Project) = with(target) {
           // Same task-substring semantics & IDE-sync caveat as build.gradle.kts:136.
           if (!isBuildForVariant(KlickrFlavour.CLOUD)) return@with
           val libs = getLibs()
           pluginManager.apply(libs.plugins.google.gms.get().pluginId)
           dependencies {
               cloudImplementation(platform(libs.getLibrary("google.firebase.bom")))
               cloudImplementation(libs.getLibrary("google.firebase.messaging"))
               cloudImplementation(libs.getLibrary("retrofit"))
               cloudImplementation(libs.getLibrary("okhttp"))
               cloudImplementation(libs.getLibrary("retrofit.kotlinx.serialization"))
               cloudImplementation(libs.getLibrary("androidx.work.runtime.ktx"))
               cloudImplementation(libs.getLibrary("androidx.hilt.work"))
           }
       }
   }
   ```
   Register it:
   ```kotlin
   // build-logic/convention/build.gradle.kts, gradlePlugin block
   register("cloud") {
       id = "com.buzbuz.gradle.cloud"
       implementationClass = "com.buzbuz.gradle.convention.plugins.CloudConventionPlugin"
   }
   ```
   ```toml
   # gradle/libs.versions.toml [plugins]
   buzbuz-cloud = { id = "com.buzbuz.gradle.cloud", version = "unspecified" }
   ```
   Applied in the app via `alias(libs.plugins.buzbuz.cloud)`, alongside the existing `buzbuz.flavour` alias.

2. **New library aliases** in `gradle/libs.versions.toml`. Follow the catalog's existing conventions: the Firebase BOM (line 126) and `firebase-crashlytics-ktx` (line 127) each carry an explicit `version.ref`, but the messaging artifact is intended to be **BOM-managed**, so declare it with **group + name only and no version** (this is how BOM-pinned artifacts are normally declared; confirm the `VersionCatalogWrapper` accessor tolerates a versionless entry — the existing catalog always supplies a version or `version.ref`, so this is the one entry that departs from the local pattern and should be smoke-tested):
   ```toml
   [versions]
   retrofit = "2.11.0"
   okhttp = "4.12.0"
   androidxWork = "2.9.1"
   androidxHiltWork = "1.2.0"

   [libraries]
   google-firebase-messaging = { group = "com.google.firebase", name = "firebase-messaging" } # version managed by firebase-bom
   retrofit = { group = "com.squareup.retrofit2", name = "retrofit", version.ref = "retrofit" }
   retrofit-kotlinx-serialization = { group = "com.jakewharton.retrofit", name = "retrofit2-kotlinx-serialization-converter", version = "1.0.0" }
   okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
   androidx-work-runtime-ktx = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "androidxWork" }
   androidx-hilt-work = { group = "androidx.hilt", name = "hilt-work", version.ref = "androidxHiltWork" }
   ```
   WorkManager and `hilt-work` are net-new to the project (the codebase confirms "WorkManager NOT PRESENT"), introduced for `core:scheduling`. They are listed under `cloudImplementation` here for connected sync; if local interval capture ships, move `work-runtime-ktx` to a plain `implementation` in `core:scheduling`'s own build file (with the no-op local sync binding described above).

### Step-by-step rebrand checklist

1. **String source of truth.** Edit `smartautoclicker/src/main/res/values/strings.xml:20` to `<string name="app_name" translatable="false">TraxIntel</string>`. Leave `translatable="false"` (the launcher label is brand, not localized).
2. **Embedded-brand copy.** Update `strings.xml:89` and `:143` (and their analogues in all 11 translated `values-*` files under `smartautoclicker/` and in `core/common/{ui,permissions,actions,quality,overlays}` and `feature/notifications`) to read "TraxIntel" instead of "Klick'r". Use a guarded find/replace on the literal `Klick\'r` within `<string>` bodies only — do **not** touch `Klickr*` build identifiers.
3. **Hardcoded Kotlin literals.** Change `ActionExecutor.kt:270` fallback title to `notification.name ?: "TraxIntel"`, and the wake-lock tag in `SmartProcessingRepositoryImpl.kt:90` to `"TraxIntel::Detection"`.
4. **Icons.** Replace pixel/vector content for `ic_smart_auto_clicker_foreground`, `ic_smart_auto_clicker_monochrome`, the 10 density PNGs (`ic_smart_auto_clicker{,_round}.png` across mdpi..xxxhdpi), and re-tint `@color/primary` if the brand color changes. Keep resource names and the two `mipmap-anydpi-v26` XMLs and two `src/debug/.../mipmap-anydpi-v26` overrides intact.
5. **applicationId.** In `smartautoclicker/build.gradle.kts`, override `applicationId` in the `cloud` `productFlavor` to the new TraxIntel package; keep `namespace = "com.buzbuz.smartautoclicker"` (line 50) and the `local`/base `applicationId` unchanged. Confirm the existing `shouldRandomize` gate (lines 44-45, F-Droid-only) keeps randomization **off** for any cloud variant. Leave `versionCode`/`versionName` (lines 60-61) shared — distinct `applicationId`s mean distinct Play listings, so no collision.
6. **`settings.gradle.kts`** `rootProject.name = "Klick'r"` — optionally update to `"TraxIntel"`. Cosmetic, but note it feeds Gradle's build-cache keys and output directory names, so changing it will invalidate existing local/CI Gradle caches (a one-time rebuild cost). Non-load-bearing otherwise.
7. **Flavors.** Add `CONNECTIVITY` dimension and `LOCAL`/`CLOUD` flavors to `KlickrVariants.kt`; in `FlavourConventionPlugin.kt` add `missingDimensionStrategy("connectivity","local")` and `isDefault = true` on `local` in **both** the `androidApp` and `androidLib` blocks (the plugin is intentionally duplicated). Verify a sample library module (e.g. `core:common:base`) still resolves from the app's `cloud` variant.
8. **Dependency gating.** Add the single `cloudImplementation(Provider<…>)` overload to `DependencyHandlerScopeExt.kt`; wire cloud *project* deps via the auto-generated `"cloudImplementation"(project(...))` configuration in the app build file; create and register `CloudConventionPlugin`; add catalog aliases.
9. **Firebase.** Create the TraxIntel Firebase project, register the cloud `applicationId`, drop `google-services.json` into `smartautoclicker/src/cloud/` (connectivity-scoped), enable FCM, apply the gms plugin only for cloud variants (via `CloudConventionPlugin` or a conditional `apply` next to line 136).
10. **Verify the GPLv3 gate.** Build `fDroidLocalRelease` and confirm no Retrofit/OkHttp/Firebase/WorkManager artifacts and no `feature:cloud`/`core:network` link in the APK; build `playStoreCloudRelease` and confirm they do. Remember the guarantee is enforced by *what F-Droid builds* (`fDroidLocal*` only), not by the configuration scoping — so the published-artifact target is the real acceptance gate.
11. **Store listing.** New Play listing under the TraxIntel `applicationId`, updated icon, screenshots, and description; the existing Klick'r listing is left as-is (the `local`/Play build can continue under the old `applicationId` if both products coexist).

### Release engineering notes

- The build-type plumbing already supports release signing: `signingConfigs` creates the `release` config (lines 84-91), `apply(plugin = libs.plugins.buzbuz.androidSigning…)` wires it (line 133), and lines 94 / 136 gate Play-Store-only and Play-Store-release-only setup. The cloud release rides this unchanged: `playStoreCloudRelease` inherits the Play signing config and adds the cloud module set.
- **F-Droid metadata is in-repo** at `fastlane/metadata/android/` (per-locale dirs `en-US`, `fr-FR`, …). F-Droid's build targets the `fDroid` `VERSION` flavor; once `CONNECTIVITY` lands, the build must select the fully-qualified `fDroidLocal` flavor (paired with `release`) so it yields `fDroidLocalRelease`, which by construction excludes all proprietary/network deps. Wherever the F-Droid build invocation names the flavor/variant (the project's F-Droid build recipe — in-repo CI/metadata under `fastlane/`, or the external `fdroiddata` recipe if publishing is done there), it must be updated from the single-flavor `fDroid` to the two-dimension `fDroidLocal`, or the F-Droid build will fail to resolve a single variant. Confirm which of the two locations actually drives the official F-Droid build before treating this as an in-repo edit.
- Do not enable obfuscation app-ID randomization for any published cloud variant: a randomized `applicationId` would not match the Firebase-registered package or the Play listing. The existing `shouldRandomize` gate already restricts randomization to F-Droid, so cloud variants are safe by default; keep it that way (and explicitly exclude `KlickrFlavour.CLOUD` if a cloud+F-Droid combination is ever built).

---

## Security, privacy, legal & compliance

TraxIntel inherits a hard constraint that most SaaS products never face: the entire Android capture client is, and must remain, a derivative work of a **GPLv3** codebase. The two security-critical declaration files have been spot-checked and both carry the GPLv3 "either version 3 of the License, or (at your option) any later version" header — `smartautoclicker/src/main/AndroidManifest.xml` and `smartautoclicker/src/main/res/xml/accessibilityservice.xml` — and the source tree generally follows this convention (e.g. `build-logic/convention/.../extensions/DependencyHandlerScopeExt.kt` also carries it). We do **not** assert that *every* file in the repo carries the header verbatim: build-time generated artifacts such as `ComponentConfig.kt` (emitted by `ConfigFileContentBuilder`) were not confirmed, and the legal argument does not depend on a universal claim — it depends only on the fact that the shipped APK is a combined work linking GPLv3 modules. At the same time the product's commercial value lives in **Trax Cloud** (the `/v1` backend) and the **web dashboard**, which we intend to keep proprietary. This section establishes the exact license boundary that makes that legal, the Google Play distribution strategy for an app that combines an accessibility service, `MediaProjection` screen capture, and cloud data collection, and the data-protection design grounded in what our capture pipeline actually records — including the build work that does not yet exist and must ship as GPLv3 source.

### GPLv3 obligations and the proprietary boundary

The copyleft scope of GPLv3 is the combined/linked program. Anything that is statically or dynamically linked into the APK, or compiled against GPLv3 sources, becomes part of the same "work based on the Program" and must ship as corresponding source under GPLv3. That sweeps in **all four new on-device modules** by construction: `core:observation`, `core:network`, `core:scheduling`, and `feature:cloud` are Gradle modules in the same `com.buzbuz.smartautoclicker.*` namespace, depend on GPLv3 modules (`core:observation` depends on `core:smart:database` and `core:common:bitmaps`; `core:scheduling` wraps `SmartProcessingRepositoryImpl.startDetection`; `feature:cloud` injects into the same `SingletonComponent` Hilt graph as `SmartAutoClickerService`), and are linked into the APK. There is no clever way to keep the Retrofit client or the Observation Room entity proprietary while shipping them inside the app — they link GPL code, so **they must ship as GPLv3 source.** The MVP contract already accepts this ("the cloud flavor produces the connected build while fDroid/playStore builds compile and run with no network module linked (GPLv3 preserved)"); the correct reading is not that the network code escapes GPL but that the *default* builds simply don't contain it.

The defensible boundary is the **network API surface**, not a module split. The framing that matters legally is GPLv3 §3/§6 "corresponding source for the binary you convey": our obligation attaches to the exact binary we distribute (`playStoreCloudRelease`), and *that binary's* complete corresponding source must be offered to recipients. Trax Cloud stays proprietary because:

1. **No GPL code is linked into the binary we convey, and the server is not part of it.** The server is a separate program running on our infrastructure; it is never conveyed to the user, so it is not "corresponding source" for the APK. It is written against an OpenAPI-described `/v1` contract and shares no compiled artifact, header, or class with the GPLv3 Android tree. Under both the FSF's own guidance and the established "arms-length over a network protocol" reading, two programs that communicate only by exchanging data over a socket are not a single combined work. The server is not a "system library" exception nor a mere "aggregate" on the same medium — it is a wholly independent program on different hardware, which is the cleanest case. The dashboard (a web app the user never receives as part of the GPLv3 APK) is likewise independent.
2. **The wire protocol is data, not a linked interface.** The Observation upload payload, the scenario-pull payload, and the command-pull payload are JSON over HTTPS. They are reused conceptually from the existing `@Serializable` backup envelope pattern (`ScenarioBackup(version, screenWidth, screenHeight, scenario)` in `feature/backup/.../ScenarioBackup.kt`), but a serialization format is a data contract, not GPL-linked code. Publishing the OpenAPI schema (which we want for the client anyway) does not obligate us to publish the server implementation — a schema is not "corresponding source" for the server, and the server is not corresponding source for the client.

What this means concretely for shipping:

- **Must ship as source (GPLv3):** the whole APK for any flavor, including `core:network`'s Retrofit/auth/**offline-queue** code, `core:observation`'s entity/DAO/migration `21 -> 22`, `core:scheduling`'s scheduling/worker code, `feature:cloud`'s enrollment/pairing/sync-status UI, the new JNI marshalling in `smartautoclicker.cpp`/`jni_detection_result.cpp` that surfaces recognized text (see below — this code does not yet exist), and the new cloud flavor's Gradle wiring. Source for the **exact** `playStoreCloudRelease` build offered to users must be made available to those users.
- **Must NOT link GPL code (stays proprietary):** the Trax Cloud server, its database schema/migrations, the dashboard frontend/backend, any server-side auth/tenant-isolation logic, and the device-token issuance service. None of these are compiled against or linked with `com.buzbuz.smartautoclicker.*`, and none are conveyed to the user.

Two GPLv3 hygiene items the MVP must respect. First, the app already advertises GPLv3 to users; the cloud flavor must keep a license/source-offer notice reachable from `feature:cloud` settings (a link to the source repo for that exact build satisfies the "written offer / accompanying source" obligation for binary distribution under §3/§6). Second, **trademark vs. copyright are separate**: the MVP keeps packages as `com.buzbuz.smartautoclicker.*` and treats "TraxIntel" as a brand layer. GPLv3 §7 lets us assert trademark rights on the "TraxIntel"/"Trax Cloud" marks even while the code is GPL — but we cannot use the mark to functionally restrict the freedoms (e.g. we can require rebranding of forks, not forbid forking). Practically: keep the brand strings in resources, do not bake brand-enforcement into license terms in a way that adds a non-GPL restriction.

A subtle risk worth flagging: the device-scoped token and enrollment secrets are *in the source* because the client is GPL. We must assume an attacker can read the client. Therefore **no server trust can derive from client secrecy** — pairing codes must be short-lived and single-use, device tokens must be server-issued and revocable, and tenant isolation must be enforced server-side (per acceptance gate "device tokens write only their own Observations"), never by client-side checks. This is a security consequence of the GPL obligation, not an independent choice, and it drives the concrete token-storage decision in the data-protection section below.

### Google Play policy strategy and rejection risk

The capture client is, from Play's risk model, a near-worst-case stack. The manifest (`smartautoclicker/src/main/AndroidManifest.xml`) declares an `AccessibilityService` (`android.permission.BIND_ACCESSIBILITY_SERVICE`, intent-filter `android.accessibilityservice.AccessibilityService`), `FOREGROUND_SERVICE_MEDIA_PROJECTION` with `android:foregroundServiceType="mediaProjection"`, and `SYSTEM_ALERT_WINDOW`. The accessibility config (`accessibilityservice.xml`) requests `canRetrieveWindowContent="true"`, `canPerformGestures="true"`, and `canRequestFilterKeyEvents="true"`. TraxIntel then adds something the original app did not: **it exfiltrates content read from other apps' screens to a cloud backend.** Each of these alone draws scrutiny; combined with off-device data collection, the realistic outcome is that `playStoreCloudRelease` **will be challenged or rejected** on first submission, and we should plan distribution around that probability rather than against it.

The specific policy exposures:

- **Accessibility API misuse.** Play restricts `AccessibilityService` to apps that primarily help users with disabilities, with narrow exceptions. TraxIntel's accessibility use is *not* for screen capture (that's `MediaProjection`) — it is the gesture/window-content plumbing the original auto-clicker needs. For the read-only MVP we should **shrink the accessibility footprint to the truth of what tracking mode needs.** Tracking captures via `MediaProjection` and OCR; it does not click. So for the cloud flavor we can credibly drop `canPerformGestures` from the tracking path and justify accessibility narrowly (overlay coordination / window-state awareness). The mechanism matters: `accessibilityservice.xml` lives in the base `smartautoclicker` module and is shared across flavors, so shrinking it *only* for the cloud flavor requires either a **per-flavor resource override** (a `src/playStoreCloud/res/xml/accessibilityservice.xml` that the cloud variant's resource merge picks up) or a runtime-narrowed `AccessibilityServiceInfo` set from the cloud flavor at `onServiceConnected`. Whichever path we pick, any IsAccessibilityTool / prominent-disclosure declaration must describe the *actual* runtime behavior of the variant we submit.
- **MediaProjection + foreground service.** `mediaProjection` foreground type is allowed but requires the system consent dialog every session and honest `foregroundServiceType` usage. We already start it correctly: `SmartAutoClickerService.onLocalServiceStarted()` calls `startForegroundMediaProjectionServiceCompat(...)` (the extension itself is defined in `core/common/base/.../extensions/Service.kt`; `LocalService` triggers the lifecycle but does not call the compat method). The added risk is that we now *transmit* captured pixels (crops) and *derived data* (OCR values) off-device, which must be disclosed.
- **Data collection & Data Safety.** TraxIntel collects, off-device, values OCR'd from arbitrary third-party app screens plus optional screenshot crops, tied to a `deviceId`, `tenantId`, and account. This must be declared accurately in the Play **Data Safety** form. The honest disclosure (detailed below) is itself a yellow flag to reviewers because "reads other apps' screen content and uploads it" reads like spyware unless the disclosure and in-app consent are airtight.

Strategy to maximize approval odds:

1. **Submit a narrow, honest listing.** Position TraxIntel as a fleet/kiosk/personal-metric *monitoring* tool (the MVP's own use cases), not a general "read any app" tool. Emphasize user-owned devices and operator-owned fleets.
2. **Prominent disclosure + runtime consent gate.** Before any capture-to-cloud, show a disclosure screen (in `feature:cloud`) naming what is captured, that it leaves the device, and where it goes; require explicit opt-in persisted via DataStore (see consent UX below). This is both a Play requirement and our legal basis.
3. **Minimize accessibility to read-only needs** for the cloud flavor as above (per-flavor override or runtime-narrowed config).
4. **Keep the two distribution channels structurally independent.** This is the key reason the flavor design protects us. The existing flavor system (`KlickrVariants.kt`) currently defines a single dimension, `KlickrDimension.VERSION`, with exactly two flavors — `F_DROID("fDroid")` and `PLAY_STORE("playStore")`. The variant names this blueprint uses (`playStoreCloudRelease`, `fDroidLocalRelease`, etc.) therefore **do not exist yet** and are a concrete build-system change: add a second dimension (e.g. `CONNECTIVITY` with `LOCAL`/`CLOUD` flavors) to `KlickrVariants.kt`, which `FlavourConventionPlugin` will then cross-multiply against `VERSION`. Dependency gating reuses the verified Crashlytics pattern: just as `CrashlyticsConventionPlugin` calls `playStoreImplementation(...)` (the extension `add("playStoreImplementation", dependency)` in `DependencyHandlerScopeExt.kt`), a new `cloudImplementation` extension links `core:network` + `feature:cloud` only into cloud builds. Because connectivity is a *separate dimension* from `VERSION`, we can produce `playStoreCloudRelease`, `playStoreLocalRelease`, `fDroidLocalRelease`, etc. independently. **A Play rejection of the cloud build does not touch `fDroidLocalRelease` or the existing `playStoreLocalRelease`** — the GPLv3 base app keeps shipping unchanged.

**Fallback distribution if Play rejects `playStoreCloudRelease`:**

- **Direct/sideload of the GPLv3 cloud APK.** Because the cloud flavor is GPLv3 and we ship its source anyway, distributing the signed cloud release variant directly is straightforward and consistent with the project's existing F-Droid-style direct distribution. Note the obfuscation pipeline (`ObfuscationPlugin`) randomizes the applicationId and component class names; for enrolled devices this must be handled carefully so the cloud variant references a **stable component identity** the server/MDM can target across updates (do not randomize the cloud flavor's component names if device enrollment binds to them).
- **Enterprise / MDM (managed Google Play / private app).** The primary use case is an operator owning a 3–20 device fleet — exactly the managed-device scenario. Publishing as a **private app to a managed Google Play organization** sidesteps public-listing review and is the natural channel for fleet/kiosk deployments. MDM also lets the operator pre-grant the accessibility and overlay permissions and pin the app, removing the friction that triggers consumer-policy review.
- **F-Droid is not a real fallback for the cloud build.** F-Droid would happily carry `fDroidLocalRelease` (no network), but it will not ship a build that phones home to a proprietary backend with non-free network dependencies. The cloud flavor's fallback is sideload + MDM, not F-Droid.

### Data protection

**What a reading actually contains — the minimization baseline.** A TrackingScenario defines a `detectionArea` Rect "in device screen-space" and a read type. Capture works by acquiring a full screen frame (`DisplayRecorder.acquireLatestBitmap()`, driven by `DetectorEngine.processScreenImages`), then OCR/color detection runs over the `detectionArea` via `ConditionsVerifier`. The structured Observation therefore carries only the derived `value` (a number, the recognized text string, or a color-state), `confidence`, timestamps, and IDs. The optional crop is the *bitmap of the detectionArea region*, saved via `BitmapRepository.saveImageConditionBitmap(bitmap, "Observation_")`.

**Prerequisite: surfacing the recognized text string is net-new JNI work that does not exist today.** This is load-bearing for the entire minimization story, so it must be called out as work-to-do, not described in the past tense. Currently `detectText()` does **not** return the recognized string to Kotlin: `DetectionResult` exposes only `isDetected`, `position`, `size`, `confidenceRate`, and `numberDetected`. The JNI marshaller `toJniResult()` in `jni_detection_result.cpp` returns a fixed **7-element `jdoubleArray`** `[detected, centerX, centerY, width, height, confidence, numberDetected]`; the OCR'd text (`TextRecognizerResult.text` in C++) is consumed internally for fuzzy matching and then discarded. To surface text as an Observation `value`, the MVP must **add a new JNI marshalling path** — either change `detectTextNative`'s return from `jdoubleArray` to a `jobject` result carrying both the numeric array and a `jstring`, or add a parallel call that returns the recognized string — touching `smartautoclicker.cpp`, `jni_detection_result.cpp`, `NativeDetector.kt`, and `DetectionResult.kt`. All of this is C++/Kotlin inside the GPLv3 APK and **must ship as GPLv3 source.** Until it is built, only numeric (`detectNumber`) and color/image readings have a usable derived `value`; the text-tracking use case depends on this change landing.

The crop is the highest-risk artifact. The detectionArea is operator-chosen and unconstrained, so a crop "may contain" whatever sits in that rectangle — including, if the operator marks the wrong region of a third-party app, names, balances, message text, or other PII the user never intended to ship. **Minimization rules the MVP must enforce:** (a) crop capture is **off by default** and per-scenario opt-in (`cropCaptureEnabled` on the scenario, plus a device-level crop-capture toggle in DataStore); (b) we store and upload **only the cropped `detectionArea` rectangle, never the full screen frame** — the full frame stays transient in the processing loop and is never persisted or uploaded; (c) Observations are append-only and the dashboard never offers a live full-screen view (explicitly out of scope: "live screen streaming/mirroring"). The principle: TraxIntel transmits *the smallest derived value that satisfies the use case*, and a screenshot only when the operator explicitly accepts the trade-off.

**Consent UX.** Consent is layered and persisted with the existing DataStore pattern (`SettingsDataSource`, `androidx.datastore.preferences`, keys in a companion object, `Flow<Boolean>` getters wrapped in `StateFlow` via `stateIn(Eagerly)` in `SettingsRepositoryImpl`):

```kotlin
// core:common:settings — new keys, same pattern as KEY_FORCE_ENTIRE_SCREEN
val KEY_CLOUD_SYNC_ENABLED   = booleanPreferencesKey("cloud_sync_enabled")
val KEY_CROP_CAPTURE_ENABLED = booleanPreferencesKey("crop_capture_enabled")
val KEY_CONSENT_ACK_VERSION  = intPreferencesKey("cloud_consent_ack_version")

val isCloudSyncEnabledFlow: Flow<Boolean> =
    dataStore.data.map { it[KEY_CLOUD_SYNC_ENABLED] ?: false }   // default OFF
```

Flow: (1) on first enrollment, `feature:cloud` shows a prominent disclosure — what is captured, that crops/values leave the device, which backend, retention, how to delete; (2) the user must affirmatively toggle `cloud_sync_enabled`; capture-to-cloud is inert until then (`SmartProcessingRepositoryImpl.startDetection` may still run locally, but `core:network` uploads nothing). (3) Crop capture is a second, independent opt-in. (4) Re-consent: bump `cloud_consent_ack_version` whenever the disclosed data practices change, and re-prompt if the stored ack is older. (5) The persistent `mediaProjection` foreground notification — managed by `ServiceNotificationController`, which is defined in `feature/notifications` and merely wired/consumed by `LocalService` — provides ongoing transparency that capture is active.

**Encryption in transit.** All `/v1` traffic over TLS 1.2+ via OkHttp/Retrofit in `core:network`. Note the **current** manifest state: `smartautoclicker/src/main/AndroidManifest.xml` declares **no** `android:networkSecurityConfig` and **no** `android:usesCleartextTraffic` attribute at all (cleartext is therefore disabled by platform default on modern targets, but only implicitly). The cloud flavor must **add a new Network Security Config XML** (a new resource file shipped as GPLv3 source) that explicitly sets `cleartextTrafficPermitted="false"` and scopes the upload/auth/scenario/command domains, and reference it via `android:networkSecurityConfig` on the cloud-flavor manifest. Given the client is GPL (source-visible), do **not** rely on certificate pinning as a security control against a determined operator on their own device — pinning helps against network MITM but is not a tenant-isolation mechanism. Idempotency keys on Observation upload (per the contract) are sent in-band and prevent replay-induced duplicates server-side.

**Offline queue, retry, and bounded retention are net-new and must be built.** The blueprint's retry/age-cap behavior assumes a queue that does not yet exist: **WorkManager is not present in this repo** (no `androidx.work` dependency, no `hilt-work`). The offline queue (a Room-backed table of unsynced Observations in `core:observation`), its bounded-retention/age-cap logic, and any background scheduling must be implemented from scratch and shipped as GPLv3 source under `core:network` / `core:scheduling`. If we adopt WorkManager for the upload worker, that adds `androidx.work` + `hilt-work` and a `HiltWorkerFactory` wired in `SmartAutoClickerApplication`; the existing foreground `LocalService` (via the static `LocalServiceProvider`) handles in-session state and would be *extended*, not replaced, by background scheduling.

**Encryption at rest, and where the device token lives.** Two on-device stores: the SQLite/Room database (`ClickDatabase`, current `DATABASE_VERSION = 21` per `DatabaseInfo.kt`; the new Observation entity bumps it to **22 via a manual `Migration(21, 22)`** — not an AutoMigration, because adding an entity with foreign keys plus the queue index is a non-trivial schema change, matching the `Migration19to20` precedent) and the on-disk crops under `context.filesDir` (`ConditionBitmapsDataSource`, PNG quality 100, `Observation_` prefix). The app already sets `android:allowBackup="false"` in the manifest, which prevents crops/DB from leaking into cloud/ADB backups — keep that. The queued-but-unsynced Observations and their crops are the sensitive local window; they rely on platform full-disk-encryption (default on modern Android).

On the **device token / enrollment secret we make a concrete decision rather than leaving it "optional"**: the existing DataStore files (`settings`, `scenario_sort`) are plaintext preferences, so storing the token in the same plaintext pattern would leave it readable on a rooted device. Because our own threat model assumes a fully-readable GPL client, we do **not** treat token confidentiality as a security control. The committed design: **the device token is server-issued, server-revocable, and scoped to write only its own Observations (server-enforced), and is therefore acceptable to persist in standard (plaintext) DataStore** — its compromise grants no more than that one device already has, and revocation is the mitigation. We do **not** ship an encrypted-DataStore dependency as a false reassurance. (If a future requirement adds a higher-value secret that is *not* revocable, that secret — and only it — would warrant Android Keystore-backed storage; the device token does not.) Server-side, crops and Observations are encrypted at rest in Trax Cloud storage (out of this repo's scope but a contract for the backend section).

**Retention & deletion.** Observations are **append-only** (contract) — there is no edit/back-fill. Deletion therefore operates at the Observation/scenario/tenant granularity, not by mutation:

- *Client retention:* the net-new offline queue bounds local persistence — once an Observation is acknowledged uploaded, its local row and crop are deleted (`BitmapRepository.deleteImageConditionBitmaps(paths)` already exists for exactly this). Failed/unsynced Observations are retried, not retained indefinitely; the queue caps depth and age (logic to be built).
- *Server retention:* Trax Cloud applies a tenant-level retention window (configurable; a sane MVP default such as N days for crops, longer for the numeric/text time series since crops are the heavy PII risk). The dashboard's time series can keep derived values after crops are purged.
- *Account deletion — with an explicit client/server handshake.* Server-side cascade (Account → Tenant → Devices/Scenarios/Observations/crops) is conceptually analogous to the codebase's `onDelete=CASCADE` Room discipline (e.g. `EventEntity` → `ScenarioEntity`), but those tables are **server-side and out of this repo**, so the client cannot perform the cascade — it must *trigger* it and reconcile local state. Concrete flow: (1) the client calls a dedicated `/v1` account-deletion endpoint (e.g. `DELETE /v1/account`) authenticated with the current device token; (2) the **client first stops the upload worker / drains the offline queue's in-flight requests so no upload races the deletion** — pending uploads are abandoned, not flushed, since their target tenant is being destroyed; (3) the server performs the cascade and revokes all device tokens for the tenant; (4) on success the client clears the device token, flips `cloud_sync_enabled` off, and purges queued Observations and `Observation_`-prefixed crops locally; (5) if the device is offline at deletion time, server-side token revocation means its next upload fails authentication, at which point the client self-cleans. Provide an in-dashboard "delete my data" path so deletion does not require contacting support.

**Privacy-policy outline (defensible, matching what we actually collect):**

1. *Who we are / controller* — TraxIntel Cloud operator, contact, jurisdiction.
2. *What we collect* — (a) account data (login identifier/email such as the owner's address); (b) device registry data (`deviceId`, model, OS, online/tracking status); (c) Observations: derived values (number/text/state), confidence, `deviceCapturedAt`/`serverReceivedAt`, scenario/device/tenant IDs; (d) optional screenshot crops of the operator-defined `detectionArea`, only when crop capture is enabled.
3. *What we explicitly do not collect* — full screen frames, screen video/stream, content outside the marked region, data from any app while sync is disabled.
4. *Source of the data* — captured from the user's own/enrolled devices via on-device OCR over a region the operator marks; the operator is responsible for the legality of what they mark.
5. *Purpose & legal basis* — providing the tracking dashboard and fleet control. For the **owner's own device**, the basis is the user's explicit opt-in consent (the DataStore toggles). For the **fleet-operator case where the device user is not the account owner** (e.g. an operator monitoring employee or kiosk devices), consent from the account owner is *not* a sufficient lawful basis for any personal data of the device's actual user — this is the weakest, highest-risk scenario for both Play policy and GDPR. The policy must state that in that case the operator acts as controller and is responsible for establishing its own lawful basis (e.g. legitimate-interest assessment plus notice to monitored individuals, or employment-context obligations), and TraxIntel acts as processor; the operator must not mark regions capturing the personal data of third parties without that basis.
6. *Sharing* — none sold; sub-processors (hosting/storage) listed; no third-party API/webhook access in MVP (out of scope).
7. *Retention* — per the windows above; append-only model explained.
8. *Security* — TLS in transit, encryption at rest, tenant isolation enforced server-side, device-scoped revocable tokens.
9. *User rights & deletion* — self-serve account/data deletion in the dashboard (per the handshake above); access/export expectations (note: CSV/BI export is out of MVP scope, so set the right expectation).
10. *Children / sensitive data* — not directed at children; warning that crops can capture sensitive content and that the operator must avoid marking regions containing others' personal data.
11. *Changes* — versioned; tied to the `cloud_consent_ack_version` re-prompt so material changes force re-consent.

The throughline: GPLv3 forces our client to be transparent, so our security and privacy posture must be built to survive a fully-readable client (server-enforced isolation, revocable tokens that we deliberately do not treat as secret-at-rest, opt-in-default-off consent, minimal-derived-value capture). Several load-bearing pieces — the recognized-text JNI marshalling, the Network Security Config, the offline queue with bounded retention and its scheduling, and the `Migration(21, 22)` — are net-new code that does not exist today and must be implemented and shipped as GPLv3 source. The new flavor *dimension* in `KlickrVariants.kt` keeps the legal and policy blast radius of the proprietary cloud feature contained to one build variant, leaving the GPLv3 base app — and its existing distribution — untouched.

---

## Testing, QA & observability

This section defines how TraxIntel's MVP is verified across the stack — from JVM unit tests of the new domain and sync code, through Room migration and native/JNI detection tests, instrumented service tests, backend API tests, and a single device-to-cloud end-to-end happy path — and how the running fleet and backend are observed in production. Every recommendation is grounded in patterns that already exist in this repository: the `MigrationTestHelper`-based migration suite under `core/smart/database/src/test/`, the golden-screenshot native detection suite under `core/smart/detection/src/androidTest/`, the version-catalog test dependencies in `gradle/libs.versions.toml`, and the flavor-gated Crashlytics wiring in `CrashlyticsConventionPlugin.kt`. Data shapes (Observation, TrackingScenario, the `/v1` API envelopes) are owned by the "Data contracts & schemas" section; I restate only the fields each test asserts on.

**Scaffolding that does not exist yet.** Several things this plan tests are net-new to the repo and must be built before any of these tests can compile or run. Calling this out up front so the test plan is not mistaken for "extend an existing pattern":

- **The four new modules do not exist.** `core:observation`, `core:network`, `core:scheduling`, and `feature:cloud` are absent from `settings.gradle.kts` today. Each must be created following the new-module recipe (add `include(":core:observation")` etc. to `settings.gradle.kts`; add a `build.gradle.kts` applying `alias(libs.plugins.buzbuz.androidLibrary)` + `alias(libs.plugins.buzbuz.hilt)` and the unit-test convention plugin; add a namespace and `di/Hilt.kt`). Tests for these modules cannot be authored until the modules and their convention-plugin wiring land.
- **WorkManager is net-new.** There is no `androidx.work:work-runtime-ktx`, no `androidx.hilt:hilt-work`, and no `HiltWorkerFactory`/`WorkerFactory` anywhere in the codebase today. The polling scheduler introduces WorkManager from scratch: add `androidx.work:work-runtime-ktx` and `androidx.hilt:hilt-work` to `gradle/libs.versions.toml`, add `androidx.work:work-testing` under `# Tests only`, register a `HiltWorkerFactory` and call `WorkManager.initialize(...)` (or use the `Configuration.Provider` path) in the application, and `@HiltWorker`-annotate `PollWorker`. The scheduler test code below depends entirely on this scaffolding existing first.
- **The `CONNECTIVITY` flavor dimension and `cloudImplementation` configuration do not exist.** Today `KlickrDimension` has only `VERSION`, and `KlickrFlavour` has only `fDroid`/`playStore`. There is no connectivity dimension and no `cloudImplementation` extension on `DependencyHandlerScope` (only `playStoreImplementation`). Adding a second flavor dimension is a non-trivial change: it multiplies the variant matrix (2 versions × 2 connectivity × 2 build types = 8 variants) and changes every module's build. The CI flavor-compilation gate in this section is therefore blocked on that scaffolding landing first; until then the observability gate is untestable.

### Test stack & where each module's tests live

The repo already standardizes test tooling through two convention plugins referenced in `gradle/libs.versions.toml`: `buzbuz-androidUnitTest` (`com.buzbuz.gradle.android.unittest`) and `buzbuz-androidLocalTest` (`com.buzbuz.gradle.android.localtest`). Available libraries are JUnit4 (`junit` 4.13.2), `mockk`/`mockk-android` (1.14.11), `robolectric` (4.16.1), `androidx-test-core`/`runner`/`rules` (1.7.0), `androidx-test-ext-junit` (1.3.0), `androidx-room-testing`, `kotlinx-coroutines-test`, `androidx-arch-core-testing`, `androidx-espresso-core`, and `google-dagger-hilt-testing`. New modules must reuse these — no new test frameworks. The only test-dependency additions are `okhttp3:mockwebserver` (for the network module) and `androidx.work:work-testing` (for the scheduler); both go in the catalog under `# Tests only`.

Test placement follows the existing convention: pure-JVM and Robolectric tests in `src/test/`, device/JNI tests in `src/androidTest/`. Each new module must first apply the unit-test convention plugin in its `build.gradle.kts` (the plugin is what wires `junit`/`mockk`/`robolectric`/`coroutines-test` onto the module — a freshly created module has no test deps otherwise). The new modules get:

- `core:observation` — `src/test/` for the Observation domain model, the entity↔domain mapper, and the new DAO (via Robolectric in-memory Room, mirroring how `core/smart/database` tests run today). Migration `21→22` tests live in `core:smart:database` alongside the existing `migrations/` suite, since the migration ships in `ClickDatabase` and bumps `DATABASE_VERSION` in `DatabaseInfo.kt`.
- `core:network` — `src/test/` for the auth client, batch-upload serialization, idempotency-key generation, and the offline-queue **sync state machine**, using `okhttp3:mockwebserver` fakes.
- `core:scheduling` — `src/test/` for the WorkManager polling scheduler using `androidx.work:work-testing` `TestListenableWorkerBuilder` and `WorkManagerTestInitHelper`. Note this is the module that first introduces WorkManager + `hilt-work` to the repo (see scaffolding note above).
- `feature:cloud` — `src/test/` for enrollment/pairing ViewModels with `kotlinx-coroutines-test` + `androidx-arch-core-testing`.

### Unit tests: domain, mappers, sync state machine

**Observation domain & mappers.** The `Observation` noun is captured at `SmartProcessingListener.onScreenConditionProcessingCompleted(result: ProcessedConditionResult.Screen)` (in `SmartProcessingListener.kt`), which carries `isFulfilled`, `haveBeenDetected`, `confidenceRate` (Double 0–100), `position` (Point?), and `size` (Point?). For Number reads the value comes from `DetectionResult.numberDetected` (Double?); for Text reads it comes from the new JNI-surfaced recognized string (see the JNI section — that field does not exist on `DetectionResult` yet). Unit tests assert the **listener-result → Observation** translation:

```kotlin
@Test fun textResult_mapsRecognizedStringToValue() {
    val result = ProcessedConditionResult.Screen(
        isFulfilled = true, haveBeenDetected = true,
        condition = textCondition(scenarioId = 7L),
        confidenceRate = 91.0, position = Point(10, 10), size = Point(40, 12),
    )
    val obs = observationMapper.from(result, recognizedText = "1,204", capturedAtMs = 1_700_000_000_000L)
    assertEquals("1,204", obs.value)
    assertEquals(ValueType.TEXT, obs.valueType)
    assertEquals(91, obs.confidence)            // clamped/rounded 0..100
    assertTrue(obs.isFulfilled)
}

@Test fun failedRead_stillProducesObservation_withIsFulfilledFalse() {
    val result = screenResult(isFulfilled = false, confidence = 12.0)
    val obs = observationMapper.from(result, recognizedText = null, capturedAtMs = now)
    assertFalse(obs.isFulfilled)               // contract: failed reads still record (AC #3)
    assertEquals(12, obs.confidence)
}
```

The entity↔domain mapper test mirrors the existing `ConditionMapper`/`ActionMapper` pattern (the codebase already round-trips entities through mappers; the migration suite's `TestsData.kt` shows the fixture style). Assert a full round-trip: `domain → entity → domain` is identity for every field including `idempotencyKey`, `deviceCapturedAt`, `valueType`, and nullable `cropPath`. Because every existing entity in this repo is `@Serializable` (used by the backup/export path, e.g. `ScenarioBackup`/`CompleteScenario`), add a second round-trip assertion through `kotlinx.serialization` (`entity → JSON string → entity` is identity) so the new Observation entity survives backup/export exactly as `ConditionEntity`/`ActionEntity` do — this guards against a non-serializable field slipping into the entity.

**Sync state machine.** The offline queue in `core:network` is the riskiest new logic and deserves the densest unit coverage. Model it as an explicit state machine — `IDLE → ENQUEUED → UPLOADING → {ACKED | RETRY_BACKOFF | FAILED_PERMANENT}` — and test transitions with a fake REST client and `kotlinx-coroutines-test` `runTest`/`TestDispatcher`:

```kotlin
@Test fun reconnect_flushesQueued_andNeverDuplicates() = runTest {
    val server = FakeIngestServer()                // records every idempotencyKey it sees
    val sync = SyncEngine(api = server, dispatcher = StandardTestDispatcher(testScheduler))
    repeat(5) { sync.enqueue(observation(idempotencyKey = "k$it")) }   // captured offline
    server.online = true
    sync.onConnectivityRestored(); advanceUntilIdle()
    assertEquals(5, server.acked.size)
    assertEquals(server.acked.distinct().size, server.acked.size)      // AC: zero duplicates
}

@Test fun http409Conflict_isTreatedAsAck_notRetry() = runTest { /* server already has key */ }
@Test fun http5xx_backsOffExponentially_thenSucceeds() = runTest { /* RETRY_BACKOFF path */ }
@Test fun idempotencyKey_isStableAcrossProcessDeath() { /* derived from deviceId+scenarioId+capturedAt */ }
```

The idempotency-key derivation test is contract-critical: the key must be deterministic from immutable Observation fields so a re-upload after process death reuses the same key (the server dedupes on it). The "409 = ack" test encodes the duplicate-prevention contract from the client side.

**Scheduler.** `core:scheduling` is a brand-new WorkManager-based poller (WorkManager itself is new to the repo — see scaffolding note). Each poll triggers a single detection pass via the existing `SmartProcessingRepositoryImpl.startDetection(context, liveDebugging, generateReport, autoStopDuration: Duration?)`. **Important: `startDetection` does not take a poll interval.** Internally `DetectorEngine.processScreenImages()` runs a continuous, FPS-limited loop until `stopDetection()` is called or the optional `autoStopJob` (the `coroutineScopeIo.launch { delay(duration); stopDetection() }` path) fires. The "~one Observation per 10s" cadence is therefore enforced by the **WorkManager poll schedule** (the new `PollWorker`'s interval), not by `startDetection`. So the interval-clamp math is a property of the new scheduler, and the auto-stop is a property of the existing repository. Test both:

```kotlin
@Test fun pollWorker_belowMinInterval_isClampedTo5s() { /* assert scheduler effective interval >= 5_000 */ }
@Test fun pollWorker_invokesStartDetection_withAutoStop() = runTest {
    val repo = mockk<SmartProcessingRepository>(relaxed = true)
    TestListenableWorkerBuilder<PollWorker>(context).build().doWork()
    coVerify { repo.startDetection(any(), any(), any(), autoStopDuration = any()) }
}
```

### Room migration tests (21 → 22)

The repo has a complete, copyable migration-test pattern: `core/smart/database/src/test/.../migrations/Migration20to21Tests.kt` uses `@RunWith(AndroidJUnit4::class)` + `@Config(sdk = [Build.VERSION_CODES.Q])` (Robolectric) with `MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), ClickDatabase::class.java)`. It seeds a v20 DB via `helper.createDatabase(path, OLD_VERSION)`, runs `helper.runMigrationsAndValidate(path, NEW_VERSION, true)`, then queries to assert column state using raw `ContentValues` inserts so the test does not depend on the post-migration entity classes.

**Note on the available helpers.** `Migration20to21Tests.kt` defines only two private `SupportSQLiteDatabase` insert helpers — `insertTestScenario(id: Long)` and `insertTestEvent(...)`. There is **no `insertTestCondition` helper** in that file. To prove "existing conditions intact" across `21→22`, you must **author a new `insertTestCondition` helper** in the same `ContentValues`/literal-column style. It does not exist today:

```kotlin
// NEW helper — must be added; not present in Migration20to21Tests.kt today.
private fun SupportSQLiteDatabase.insertTestCondition(id: Long, eventId: Long, type: String) {
    insert(CONDITION_TABLE, SQLiteDatabase.CONFLICT_FAIL, ContentValues().apply {
        put("id", id)
        put("eventId", eventId)
        put("name", "test_condition_$id")
        put("type", type)                 // e.g. "ON_NUMBER_DETECTED" (ConditionType enum name)
        put("priority", 0)
        // remaining type-specific columns on CONDITION_TABLE are nullable and may be left unset
    })
}
```

The `21→22` migration adds the Observation table (`OBSERVATION_TABLE`) and bumps `DATABASE_VERSION` in `DatabaseInfo.kt`. Because it only adds a table, it can be an `AutoMigration` (added to `ClickDatabase` `@Database(autoMigrations = [...])`), matching how `20→21` was done. The new `Migration21to22Tests` must assert the contract gate "migration lands cleanly with existing scenarios/conditions intact":

```kotlin
@RunWith(AndroidJUnit4::class) @Config(sdk = [Build.VERSION_CODES.Q])
class Migration21to22Tests {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), ClickDatabase::class.java)

    @Test fun migrate_existingScenarioAndConditions_preserved() {
        helper.createDatabase(dbPath, 21).use { db ->
            db.insertTestScenario(1L)
            db.insertTestEvent(10L, scenarioId = 1L)
            db.insertTestCondition(100L, eventId = 10L, type = "ON_NUMBER_DETECTED")  // NEW helper
        }
        helper.runMigrationsAndValidate(dbPath, 22, true).use { db ->
            db.query("SELECT COUNT(*) FROM $CONDITION_TABLE").use { c ->
                assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) }      // intact
            db.query("SELECT COUNT(*) FROM $OBSERVATION_TABLE").use { c ->
                assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)) }      // new, empty
        }
    }

    @Test fun migrate_observationTable_hasExpectedColumns() { /* PRAGMA table_info assertions */ }
}
```

Reuse the existing `insertTestScenario`/`insertTestEvent` helpers verbatim, add the `insertTestCondition` helper shown above, and reuse the `OLD_DB_VERSION`/`NEW_DB_VERSION` companion-constant style. Validating with `runMigrationsAndValidate(..., validateDroppedTables = true)` also re-validates the auto-generated schema against the Room-exported JSON, catching any drift in the new entity definition.

### Native/JNI detection tests (recognized-text marshalling)

`core/smart/detection/src/androidTest/` already contains the golden-screenshot harness this MVP depends on: `TemplateMatcherTests.kt` (`@LargeTest`, `@RunWith(AndroidJUnit4::class)`) instantiates the real detector via `NativeDetector.newInstance()`, calls `init()`, loads bitmaps from test assets via `loadTestBitmap`, and compares against expected `TestResults` (expected vs actual center position + confidence) using fixtures in `data/TestImages.kt`, `data/TestResults.kt`, `data/TestDetectionArea.kt`, and thresholds in `utils/Constants.kt`. These run on a device/emulator (not Robolectric) because they exercise the NCNN native library.

**The marshalling change — and the type mismatch it forces.** Today `toJniResult` in `jni/jni_detection_result.cpp` returns a **7-element `jdoubleArray`** (verified: `env->NewDoubleArray(7)`): `[detected(0|1), centerX, centerY, width, height, confidence, numberDetected]`, with `numberDetected` at **index 6**, and `DetectionResult.kt` unpacks exactly those 7 doubles (`numberDetected = this[6]`). The recognized OCR text currently lives only in C++ (`TextRecognizerResult.text`), is consumed for fuzzy matching, and is discarded — `DetectionResult` has **no `recognizedText` field**.

A recognized **string cannot be stuffed into a `jdoubleArray`** — it is text, not a double. So surfacing it requires one of two structural changes, both of which the test plan must account for:

- **Option A (recommended): switch `toJniResult` to return a `jobject`** — a small JNI-accessible result wrapper carrying the existing numeric `DoubleArray` plus a separate `String` field — and add `recognizedText: String?` to `DetectionResult.kt`. The numeric payload keeps its current 7-element layout (so `numberDetected` stays at index 6), and the string is marshalled out-of-band as the object's String member.
- **Option B: keep the `jdoubleArray` for numerics but marshal the string separately**, e.g. via a companion/return-pair, growing the *numeric* contract conceptually from 7 to 8 fields where field 7 is an out-of-band string reference rather than a double. Whichever option is chosen, the regression test below must assert the numeric indices did not shift.

This change must be covered at three layers:

1. **C++ unit level (optional but recommended).** Add a small native test exercising `TextMatcher::matchText`/`matchNumber` (`detector/matching/text/text_matcher.hpp`) so that `recognizedNumber` and the recognized string survive through `TextMatchingResult`. If a native test harness is not stood up for MVP, the JNI boundary tests below are the gate.
2. **JNI marshalling instrumented test.** New `androidTest` class `TextRecognitionTests` following `TemplateMatcherTests` structure: load OCR models (`ImageDetector.loadTextDetectionModels(detectionModelPath, recognitionModels)`), `setScreenBitmap` with a golden screenshot containing a known string, call `detectText(conditionText, recognitionModelId, detectionArea, threshold)` — where `recognitionModelId` is an `OCRAlphabet` enum name such as `"LATIN"` — and assert the **recognized string is now present on the Kotlin-side `DetectionResult`** via the new `recognizedText` field. This test **cannot compile until both `DetectionResult.kt` and `toJniResult` are changed** per Option A/B above; it is the executable owner of the contract gate "Text scenario value carries the recognized string end-to-end through the modified JNI path."

```kotlin
@LargeTest @RunWith(AndroidJUnit4::class)
class TextRecognitionTests {
    @Test fun detectText_surfacesRecognizedStringToKotlin() {
        detector.loadTextDetectionModels(detPath, mapOf("LATIN" to recPath))   // OCRAlphabet.LATIN.name
        detector.setScreenBitmap(context.loadTestBitmap(TestImage.Screen.CounterGold1204), "")
        val r = detector.detectText("1,204", "LATIN",
            detectionArea = goldCounterArea, threshold = TEST_DETECTION_THRESHOLD_STANDARD)
        assertTrue(r.isDetected)
        assertEquals("1,204", r.recognizedText)        // NEW field on DetectionResult, net-new
    }
}
```

3. **Number-path index regression.** Assert `detectNumber` still populates `numberDetected` and that it is still read from **array index 6** (`DetectionResult.kt` line `numberDetected = this[6]` must be unchanged), proving the string addition was marshalled out-of-band and did not shift the existing numeric layout. This is the explicit guard for the type-mismatch risk above.

### OCR extraction accuracy via golden screenshots

OCR accuracy is measured, not asserted boolean-only, using the existing golden-screenshot mechanism extended for text. Add a curated corpus under `core/smart/detection/src/androidTest/assets/` of real captures representing the MVP's canonical scenarios: in-game resource counters (gold/energy/level), price strings, step counters, and kiosk KPI/state strings. **Minimum corpus size for the MVP gate: at least 40 cases**, spread across the canonical categories so no single category dominates the aggregate. Each golden carries an expected `(detectionArea, expectedString, alphabet)` tuple in a `TestOcrCases` fixture mirroring `TestImage.expectedResults`. The accuracy test computes per-case correctness and an aggregate, with a published threshold so regressions in the model or marshalling fail CI rather than silently degrade. **Initial floor: `OCR_ACCURACY_FLOOR = 0.90` (90% exact-match)** — chosen as a conservative day-one bar that catches a broken marshalling path or a model regression while tolerating known-hard cases; revisit after the corpus is populated and the baseline measured:

```kotlin
const val OCR_ACCURACY_FLOOR = 0.90          // exact-match floor; corpus must have >= 40 cases

@Test fun ocr_corpus_meetsAccuracyFloor() {
    val cases = TestOcrCases.all
    assertTrue("OCR corpus too small: ${cases.size}", cases.size >= 40)
    val exact = cases.count { c ->
        detector.setScreenBitmap(context.loadTestBitmap(c.screen), "")
        detector.detectText(c.expected, c.alphabet.name, c.area, c.threshold).recognizedText == c.expected
    }
    val accuracy = exact.toDouble() / cases.size
    assertTrue("OCR exact-match accuracy $accuracy below floor $OCR_ACCURACY_FLOOR",
        accuracy >= OCR_ACCURACY_FLOOR)
}
```

Because number detection always uses the first-loaded recognition model (`defaultRecognitionModelId` in `text_matcher.hpp`, ignoring any user-selected alphabet), the number corpus is tested only against that default alphabet — matching the in-scope constraint that per-device/number alphabet selection is out of scope. Track accuracy as a CI artifact (printed per-case diff: expected vs recognized) so OCR regressions are diagnosable. Confidence calibration is also asserted: low-confidence golden cases must yield `confidenceRate` below the dashboard's "low-confidence" threshold so the dashboard's low-confidence rendering (AC #6) has a defined input.

### Instrumented tests: AccessibilityService, MediaProjection, scheduler

The capture path lives in `SmartAutoClickerService` (an `AccessibilityService` with `foregroundServiceType="mediaProjection"`), which builds `LocalService` in `onServiceConnected()` and exposes it via the static `LocalServiceProvider`. Full MediaProjection capture cannot be granted non-interactively in CI (it requires the system consent dialog), so split instrumented coverage:

- **Headless processing with injected frames.** The detection loop `DetectorEngine.processScreenImages()` (verified `private suspend`) acquires frames via `displayRecorder.acquireLatestBitmap()` and calls `scenarioProcessor.process(screenFrame)`. **Visibility note:** `ScenarioProcessor` is an `internal class` and `ScenarioProcessor.process(screenFrame: Bitmap)` is `internal suspend`, while `processScreenImages()` is `private`. So an instrumented test that drives `ScenarioProcessor.process()` directly **must live in the same module** (`core:smart:processing`'s `src/androidTest/`, where `internal` is visible) — it cannot call into it from another module without a public test seam. Given that, drive `ScenarioProcessor.process()` with golden `Bitmap`s and a test `SmartProcessingListener`, asserting that `onScreenConditionProcessingCompleted` fires once per condition with a populated `ProcessedConditionResult.Screen`, and that the Observation-capture hook produces exactly one Observation per poll. This avoids MediaProjection entirely while exercising the real native detector. (If same-module placement is undesirable, the alternative is a narrow `@VisibleForTesting` entry point on the engine.)
- **Scheduler ↔ repository integration.** Instrumented `work-testing` test confirming a `PollWorker` enqueued at a 10s interval invokes `SmartProcessingRepositoryImpl.startDetection(context, liveDebugging, generateReport, autoStopDuration)` and that the auto-stop job (the `coroutineScopeIo.launch { delay(duration); stopDetection() }` path) halts the loop. As noted in the scheduler unit-test section, the 10s cadence is owned by the WorkManager poll interval, not by `startDetection` — this test backs the "~one Observation/10s while active" and "remote stop halts within one poll cycle" gates.
- **Service lifecycle smoke (manual/nightly).** A device-attended instrumented test that grants MediaProjection once via UiAutomator, starts the service, and verifies a foreground notification appears and `LocalServiceProvider.localServiceInstance` is non-null. Kept out of the per-PR gate because of the consent dialog; run nightly on a provisioned device.

Hilt instrumented tests use `google-dagger-hilt-testing` (`HiltAndroidRule`) to swap the real `SmartProcessingRepository`/`SyncEngine` for fakes in `feature:cloud` tests.

### Backend (Trax Cloud) API tests

The Trax Cloud backend stack (language, framework, ORM, and the row-level-scoping mechanism that backs tenant isolation) is defined in the backend/Data-contracts sections, not here; these tests are written against whatever that stack exposes over the `/v1` surface. The `/v1` endpoint set under test, per the Data-contracts section, is: **auth** (`POST /v1/auth/token`), **enrollment** (`POST /v1/enroll`), **Observation ingest** (`POST /v1/observations:batch`), **scenario CRUD** (`GET/POST/PUT /v1/scenarios`, `GET /v1/scenarios/{id}`), **scenario assignment** (`POST /v1/scenarios/{id}/assign`, `GET /v1/devices/{id}/scenarios`), **device registry** (`GET /v1/devices`, `GET /v1/devices/{id}`), and **command queue** (`POST /v1/devices/{id}/commands`, `GET /v1/devices/{id}/commands:next`, `POST /v1/commands/{id}:ack`). API tests focus on the contract gates that are server-enforced:

- **Tenant isolation (highest priority).** For **every endpoint in the list above**, a test asserting that an Account token scoped to Tenant A receives 403/404 for any Tenant B resource, and that a device token can write only Observations tagged with its own `deviceId` under an assigned `scenarioId` — rejecting spoofed `deviceId`/`tenantId` in the body. This is the "Tenant isolation enforced" gate and must be exhaustive: at minimum one negative cross-tenant test per endpoint enumerated above.
- **Idempotent ingest.** Batch-upload the same Observation set twice to `POST /v1/observations:batch` with identical `idempotencyKey`s and assert the stored count is unchanged and the second response is a success/no-op (mirrors the client's "409 = ack" expectation) — the "zero server-side duplicates" gate.
- **Enrollment.** A valid pairing code to `POST /v1/enroll` binds Device→Tenant and returns a device token; expired/invalid codes are rejected with a clear error (User Story #1).
- **Scenario assignment fan-out.** Assigning one scenario to 3 devices (`POST /v1/scenarios/{id}/assign`) makes it appear in each device's `GET /v1/devices/{id}/scenarios` response under the shared `scenarioId` (AC: "author once, push to many").
- **Command queue.** Remote start/stop commands are idempotent and expiring; a consumed (`:ack`ed) or expired command is not re-delivered by `:next` (AC #7).

Run these against an ephemeral DB instance (testcontainers or whatever the backend section specifies) so tenant-isolation tests exercise the real row-level scoping mechanism, not mocks.

### Device → cloud end-to-end happy path

One automated E2E test ties the stack together for the canonical use case (track an in-game counter):

1. Enroll a test device against a test Tenant via pairing code.
2. Author a Number TrackingScenario (10s poll interval, `detectionArea`, crop disabled) on the backend and assign it to the device.
3. Device pulls the scenario, the scheduler runs one poll against a golden frame, an Observation is produced and queued.
4. Force offline, run a second poll (second Observation queues), restore connectivity, let the sync engine flush.
5. Assert the backend has exactly two Observations under the scenario, each with the device's `deviceId`, monotonic `deviceCapturedAt`, a `serverReceivedAt`, `confidence` in 0–100, and **no duplicates**.
6. Issue a remote stop; assert the next poll cycle does not produce an Observation and the device's `tracking` state flips to false.

A second E2E variant with `cropCaptureEnabled=true` asserts the crop is saved on-device via `BitmapRepository.saveImageConditionBitmap(bitmap, "Observation_")`, uploaded, and retrievable by `cropUrl` — the crop gate.

### Observability

**Client crash reporting, per flavor.** Crashlytics is already gated to a single flavor via `CrashlyticsConventionPlugin.kt` using `playStoreImplementation { platform(firebase.bom) }` + `playStoreImplementation(firebase.crashlytics.ktx)`. The MVP introduces a new `CONNECTIVITY` dimension (`LOCAL`/`CLOUD`) composing with the existing `VERSION` dimension (`fDroid`/`playStore`) — **but neither this dimension nor the `cloudImplementation` configuration exists yet** (today `KlickrDimension` has only `VERSION`, `KlickrFlavour` has only `fDroid`/`playStore`, and `DependencyHandlerScopeExt.kt` defines only `playStoreImplementation`). Adding the second dimension is a prerequisite and a non-trivial change: it takes the variant matrix to 2 × 2 × 2 = **8 variants** and touches every module that applies `buzbuz.flavour`. Until that scaffolding lands, the flavor-compilation CI gate below cannot run.

Once it exists, the requirement is that crash reporting must remain **only** in builds that already carry it. The GPLv3 `fDroid*` and the no-network `*Local*` builds must not link any reporting SDK. Concretely: keep Crashlytics on `playStore` as today; add network/telemetry deps through a new `cloudImplementation` configuration — a new `DependencyHandlerScope.cloudImplementation` extension mirroring `playStoreImplementation` in `DependencyHandlerScopeExt.kt` (add `internal fun DependencyHandlerScope.cloudImplementation(dependency) = add("cloudImplementation", dependency)`; Gradle then auto-creates the connectivity-flavor-scoped configuration). The connected, crash-reported variant is therefore `playStoreCloudRelease`; `fDroidLocalRelease` compiles and runs with neither the network module nor any reporter — satisfying the "fDroid/local builds compile and run with no network module linked (GPLv3 preserved)" gate. A flavor-compilation CI matrix builds `playStoreCloudRelease`, `playStoreLocalRelease`, `fDroidLocalRelease`, and `fDroidCloud*` (if that combination is disallowed, assert it fails to configure) to prove the gating holds — this matrix is the executable owner of the observability gate, and is itself blocked on the dimension scaffolding above.

**Structured client logs.** The sync engine and scheduler should emit structured, low-cardinality log events (poll started/skipped, Observation captured, batch enqueued, batch acked/retried/failed, command received) keyed by `scenarioId`/`deviceId`. On crash-reporting (`cloud`) builds, attach these as Crashlytics custom keys/breadcrumbs so a crash carries the last sync state. On `local`/`fDroid` builds they are plain logcat only. Never log Observation `value`s containing potentially sensitive screen text at info level.

**Backend metrics.** Trax Cloud emits per-Tenant counters and latencies: ingest request rate and batch size, duplicate-rejection rate (should track the idempotency path), end-to-end capture→serverReceived latency (P50/P95, validating the "within one poll cycle" command gate), command queue depth and delivery latency, enrollment success/failure, and per-endpoint authz-rejection counts (a spike in cross-tenant 403s is a security signal). Expose `/v1` health and readiness endpoints for uptime monitoring.

### MVP acceptance test matrix

Each contract-level gate maps to at least one automated test owner:

| Acceptance gate | Test type | Owner test |
|---|---|---|
| Number @10s → ~1 Observation/10s with full metadata | Instrumented (scheduler + headless processor) | `PollWorker` interval + `ScenarioProcessor` capture test (same-module) |
| Text value carries recognized string end-to-end | JNI instrumented + E2E | `TextRecognitionTests.detectText_surfacesRecognizedStringToKotlin` (requires `DetectionResult.recognizedText` + `toJniResult` change) + E2E |
| Numeric JNI layout unchanged (index 6 = numberDetected) | JNI instrumented | number-path index regression test |
| Offline Observations upload, zero duplicates | Unit + E2E | `reconnect_flushesQueued_andNeverDuplicates`, ingest API idempotency test |
| Scenario → 3 devices, each tagged own deviceId | Backend API | scenario assignment fan-out test |
| Remote stop halts within one poll; dashboard tracking=false; start resumes | Instrumented + backend | scheduler auto-stop test + command queue test |
| Time series renders, low-confidence distinguished, latest-per-device | OCR confidence calibration + dashboard test | `ocr_corpus_meetsAccuracyFloor` (confidence floor) + dashboard UI test |
| Tenant isolation enforced | Backend API | per-endpoint cross-tenant negative tests (one per `/v1` endpoint) |
| cloud builds connected; fDroid/local build with no network module (GPLv3) | CI flavor matrix (blocked on `CONNECTIVITY` dimension) | `playStoreCloudRelease` vs `fDroidLocalRelease` build jobs |
| Migration 21→22 clean, existing data intact | Robolectric migration | `Migration21to22Tests` (with new `insertTestCondition` helper) |
| Observation entity survives backup/export | Unit | mapper + `kotlinx.serialization` round-trip test |
| Crop stored, uploaded, viewable per Observation | E2E (crop variant) | crop E2E test |

The per-PR CI gate runs all `src/test/` JVM/Robolectric suites (fast: domain, mappers, sync state machine, migration, backend API against an ephemeral DB) plus the flavor-compilation matrix (once the `CONNECTIVITY` dimension exists). The `src/androidTest/` native/JNI, OCR-accuracy, scheduler-integration, and E2E suites run on an emulator/device job (nightly + pre-release), with the MediaProjection lifecycle smoke gated to an attended/provisioned device. This split keeps the inner loop fast while ensuring every contract gate has a named, executable owner before MVP sign-off.

### Relevant files

- `/Users/Sophia/Documents/GitHub/AutoClicker/core/smart/database/src/test/java/com/buzbuz/smartautoclicker/core/database/migrations/Migration20to21Tests.kt` — copyable migration-test template (MigrationTestHelper + Robolectric `@Config(sdk=Q)`); provides only `insertTestScenario`/`insertTestEvent` — `insertTestCondition` must be added.
- `/Users/Sophia/Documents/GitHub/AutoClicker/core/smart/detection/src/androidTest/java/com/buzbuz/smartautoclicker/core/detection/TemplateMatcherTests.kt` and `.../data/TestImages.kt`, `.../data/TestResults.kt` — golden-screenshot native detection harness to extend for OCR text.
- `/Users/Sophia/Documents/GitHub/AutoClicker/core/smart/detection/src/main/java/com/buzbuz/smartautoclicker/core/detection/DetectionResult.kt` — currently 7 doubles, `numberDetected = this[6]`, no `recognizedText`; must gain a `recognizedText: String?` field.
- `/Users/Sophia/Documents/GitHub/AutoClicker/core/smart/detection/src/main/cpp/jni/jni_detection_result.cpp` — `toJniResult` returns `NewDoubleArray(7)`; must marshal the recognized string out-of-band (jobject wrapper) since a String cannot live in a double array.
- `/Users/Sophia/Documents/GitHub/AutoClicker/core/smart/processing/src/main/java/com/buzbuz/smartautoclicker/core/processing/data/processor/ScenarioProcessor.kt` — `internal class`, `internal suspend fun process(...)`; same-module `androidText` placement (or a test seam) required to drive it.
- `/Users/Sophia/Documents/GitHub/AutoClicker/core/smart/processing/src/main/java/com/buzbuz/smartautoclicker/core/processing/domain/SmartProcessingRepositoryImpl.kt` — `startDetection(..., autoStopDuration: Duration?)`; runs a continuous FPS-limited loop, takes no poll interval (cadence owned by the new WorkManager scheduler).
- `/Users/Sophia/Documents/GitHub/AutoClicker/build-logic/convention/src/main/kotlin/com/buzbuz/gradle/convention/plugins/CrashlyticsConventionPlugin.kt` and `.../extensions/DependencyHandlerScopeExt.kt` — flavor-gated crash-reporting/dependency pattern to mirror for `cloudImplementation`; `CONNECTIVITY` dimension and `cloudImplementation` are net-new.
- `/Users/Sophia/Documents/GitHub/AutoClicker/build-logic/convention/src/main/kotlin/com/buzbuz/gradle/convention/model/KlickrVariants.kt` — `KlickrDimension` (only `VERSION`) and `KlickrFlavour` (only `fDroid`/`playStore`) today; adding `CONNECTIVITY` makes the matrix 8 variants.
- `/Users/Sophia/Documents/GitHub/AutoClicker/settings.gradle.kts` — does not yet include `core:observation`, `core:network`, `core:scheduling`, `feature:cloud`; they must be added before their tests can exist.
- `/Users/Sophia/Documents/GitHub/AutoClicker/gradle/libs.versions.toml` — sanctioned test dependencies; WorkManager + `hilt-work` (runtime) and `okhttp3:mockwebserver` + `androidx.work:work-testing` (tests) are all net-new additions.

---

## Implementation plan — epics, tickets & sequencing

This section turns the MVP Scope Contract into an ordered backlog. Every ticket names the exact files/modules it touches (from the codebase facts), states its dependencies, a definition-of-done (DoD), and a rough size (S ≈ <1 day, M ≈ 2–4 days, L ≈ 1–2 weeks). The numbering is stable (`P{phase}-{n}`) so it can be referenced from other blueprint sections. Canonical wire/DB shapes live in the "Data contracts & schemas" section; here I restate only the fields a ticket needs to be actionable.

The guiding principle: **prove the whole pipeline end-to-end with one read type, one device, no UI polish, before fanning out.** The thinnest vertical slice (defined at the end) cuts across P0/P1/P2 deliberately.

> **Load-bearing platform constraint (read first).** This product captures the screen on a schedule. Screen capture in this app is **MediaProjection-based** and runs only while the AccessibilityService (`SmartAutoClickerService` → `LocalService`) is connected and a projection token is live (granted via `SmartProcessingRepositoryImpl.startScreenRecord(resultCode, data)`, which is fed by a user-consent Activity result). **A background WorkManager worker cannot acquire MediaProjection consent**, and `startDetection()` itself early-returns unless a `scenarioId` is set (`scenarioId.value?.databaseId ?: return`) and the scenario resolves (`scenarioRepository.getScenario(id) ?: return`). Therefore the scheduling model is **not** "a background worker spins up capture from cold"; it is "a *scheduler* drives detection bursts *through the already-running foreground service that holds the projection token*." Tickets P1-0, P1-5a, and P1-5b below are written around this constraint, and the thinnest slice is sequenced to prove background capture feasibility before any WorkManager code is written.

### Epic P0 — Rebrand & build foundation

This epic introduces the `cloud`/`local` flavor split and the empty module skeletons so that all later work has a home and the GPLv3 builds stay clean. Nothing here changes runtime behavior.

**P0-1 — Add `CONNECTIVITY` flavor dimension (S)**
Touches: `build-logic/convention/src/main/kotlin/com/buzbuz/gradle/convention/model/KlickrVariants.kt`. Add `CONNECTIVITY("connectivity")` to `KlickrDimension` and two `KlickrFlavour` values — `LOCAL("local", KlickrDimension.CONNECTIVITY)` and `CLOUD("cloud", KlickrDimension.CONNECTIVITY)`. The verified current state is exactly two version flavors (`F_DROID`, `PLAY_STORE`) on a single `VERSION` dimension, so this is purely additive.
```kotlin
enum class KlickrDimension(val flavourDimensionName: String) {
    VERSION("version"),
    CONNECTIVITY("connectivity");
}
enum class KlickrFlavour(val flavourName: String, val dimension: KlickrDimension) {
    F_DROID("fDroid", KlickrDimension.VERSION),
    PLAY_STORE("playStore", KlickrDimension.VERSION),
    LOCAL("local", KlickrDimension.CONNECTIVITY),   // default, no network
    CLOUD("cloud", KlickrDimension.CONNECTIVITY);   // links network + feature:cloud
}
```
Dependencies: none — first ticket, an engineer can start today.
DoD:
- `FlavourConventionPlugin` (which iterates `KlickrDimension.entries`/`KlickrFlavour.entries` for both `androidApp` and `androidLib`) produces the 2×2 matrix; `./gradlew tasks` lists `playStoreCloudRelease`, `fDroidLocalRelease`, etc.
- `LOCAL` is set as the default flavor for the connectivity dimension so existing build invocations resolve unambiguously.
- **Variant-name remap is handled.** Adding a second dimension changes every variant name from two-part (`fDroidRelease`, `playStoreRelease`) to three-part (`fDroidLocalRelease`, `playStoreCloudRelease`, …). This is a breaking rename for any consumer that hard-codes the old names. As part of this ticket, audit and update: (a) CI / fastlane lane references to `assemble*`/`bundle*` task names; (b) `isBuildForVariant(flavour, buildType)` call sites and `ObfuscationPlugin` variant matching, since the obfuscation pipeline keys off variant identity; (c) any `getExtraActualApplicationId()` / `applicationIdSuffix` logic gated on the old `fDroidDebug` name (it must become `fDroidLocalDebug`). DoD is not met until `fDroidLocalRelease` and `playStoreCloudRelease` both assemble and the obfuscation plugin still randomizes correctly under the new names.

**P0-2 — Add `cloudImplementation` dependency-scoping extension (S)**
Touches: `build-logic/convention/src/main/kotlin/com/buzbuz/gradle/convention/extensions/DependencyHandlerScopeExt.kt`. Mirror the proven `playStoreImplementation` pattern:
```kotlin
internal fun DependencyHandlerScope.cloudImplementation(dependency: Provider<MinimalExternalModuleDependency>) =
    add("cloudImplementation", dependency)
```
**Scope clarification (per reviewer):** `cloudImplementation` gates only the *dependency edge* — it adds the dependency to the `cloud` variant's configuration and omits it from `local`. It does **not** prevent a module that applies `buzbuz.flavour` from *configuring and building its own `local` variant*. So this extension controls "is `core:network` linked into `fDroidLocalRelease`?" (answer: no), not "does `core:network` have a `local` variant at all?" (answer: yes, it does, because it applies the flavour plugin). The two cloud-only modules are handled in P0-4.
Dependencies: P0-1 (the `cloud` flavor must exist for Gradle to auto-create the `cloudImplementation` configuration).
DoD: a throwaway `cloudImplementation(libs.getLibrary("..."))` in a module compiles into the `cloud` variant and is absent from `localRelease` (verify via `./gradlew :smartautoclicker:dependencies --configuration fDroidLocalReleaseRuntimeClasspath`).

**P0-3 — Version-catalog entries for network + WorkManager (S)**
Touches: `gradle/libs.versions.toml`. Add `[versions]`, `[libraries]` entries for Retrofit/OkHttp + kotlinx-serialization-converter (REST client), and `androidx.work:work-runtime-ktx` + `androidx.hilt:hilt-work` + the matching `androidx.hilt:hilt-compiler` KSP processor (scheduler). The facts confirm WorkManager and Hilt-Work are **not currently present**, so these are net-new.
Dependencies: none (can run parallel to P0-1).
DoD: `libs.getLibrary("...")` resolves for each new alias; no version conflicts in `./gradlew :smartautoclicker:dependencies`.

**P0-4 — Scaffold the four new modules (M)**
Touches: `settings.gradle.kts` (add `include(":core:observation")`, `:core:network`, `:core:scheduling`, `:feature:cloud`); four new `build.gradle.kts` files; four `di/Hilt.kt` files each `@Module @InstallIn(SingletonComponent::class)` per the established convention (cf. `core/common/actions/src/main/java/.../di/Hilt.kt`). Namespaces: `com.buzbuz.smartautoclicker.core.observation`, `.core.network`, `.core.scheduling`, `.feature.cloud`.

Plugin application differs by module, and this is the crux of getting the gating right:
- `core:observation` and `core:scheduling` are **local-capable** (they run on-device regardless of cloud). They apply `alias(libs.plugins.buzbuz.androidLibrary)`, `buzbuz.hilt`, **and** `buzbuz.flavour`, and are linked into the app via plain `implementation(project(...))`.
- `core:network` and `feature:cloud` are **cloud-only**. Because the codebase has no per-variant source-set exclusion convention, the pragmatic approach is: these modules still apply `buzbuz.flavour` (so they get the same 2×2 matrix and their `local` variant *is configured and compiled*), but they contain **no `local`-specific source that the app calls**, and — critically — they are linked into the app **only** via `cloudImplementation(project(":core:network"))` / `cloudImplementation(project(":feature:cloud"))` in `smartautoclicker/build.gradle.kts`. The net effect is: `fDroidLocalRelease` does **not link** these modules even though Gradle still *builds* their (empty) `local` variants as standalone libraries. **Do not claim the local variant is "not built" — it is built but not linked.** If we later want to stop configuring the `local` variant of these modules entirely, that requires a new convention (e.g. a `buzbuz.cloudOnlyLibrary` plugin that omits the connectivity dimension or disables the `local` variant); that is explicitly out of scope here and tracked as a follow-up.

Module dep wiring: `core:observation` → `implementation(project(":core:smart:database"))`, `project(":core:smart:processing")`, `project(":core:common:bitmaps")`, `project(":core:smart:domain")`; `core:scheduling` → `implementation(project(":core:smart:processing"))`. `core:network` and `feature:cloud` depend on `core:observation` for the shared `Observation`/DAO types and are themselves linked only via `cloudImplementation`.
Dependencies: P0-1. (**Not P0-2**: `core:observation` and `core:scheduling` are linked with plain `implementation` and do not need `cloudImplementation`. Only the *cloud-only link* of `core:network`/`feature:cloud` in the app's `build.gradle.kts` depends on P0-2, so split the work: scaffold the four modules after P0-1; add the two `cloudImplementation` links once P0-2 lands.)
DoD: empty modules compile in both flavors; `fDroidLocalRelease` runtime classpath links neither `core:network` nor `feature:cloud` (verified via `:smartautoclicker:dependencies`); the `local` variants of `core:network`/`feature:cloud` may exist as compiled libraries but are absent from the app's local runtime classpath; Hilt KSP runs clean.

**P0-5 — Brand rename (strings/assets only) (S)**
Touches: `smartautoclicker/src/main/res/values*/strings.xml` (the `app_name` resource — surfaced as `android:label="${appName}"` via the obfuscation manifest placeholder, which maps to `@string/app_name` unless randomization is on), launcher icons. Per the contract, **packages stay `com.buzbuz.smartautoclicker.*`** — do not touch `namespace` (`= "com.buzbuz.smartautoclicker"`, fixed in the `android` block) or `applicationId` (which flows from `getExtraActualApplicationId()` in the obfuscation plugin), to avoid churning the R file and obfuscation pipeline.
Dependencies: none.
DoD: app displays "TraxIntel"; `ObfuscationPlugin` randomization still builds; no diff to `namespace = "com.buzbuz.smartautoclicker"`.

**P0-6 — Stable `deviceId` provisioning (S)**
Touches: `core:observation` (or `core/common/settings`) — a `DeviceIdentityDataSource` that, on first run, generates a UUID, persists it in a dedicated `DataStore` preferences file following the documented `Preferences.Key<String>` → `Flow` → `StateFlow` recipe (mirroring `SettingsDataSource`), and exposes `suspend fun getDeviceId(): String` / `Flow<String>`. This id is the **client-side device identity**; it is distinct from (and later reconciled with) the server-assigned device id from enrollment (P3-1). The orchestration epics (P2-4, P3-3, P3-4) all reference Observations "tagged with the device's own id", and `ObservationEntity` carries a `deviceId` column (P1-3) — this ticket is what populates it.
Dependencies: none (parallelizable).
DoD: `getDeviceId()` returns the same value across process death (DataStore-backed); the value is injected into the Observation builder (P1-2a) so every persisted Observation is stamped with it.

### Epic P1 — On-device capture (Observation pipeline)

This epic produces structured Observations locally — the heart of the product. It is independent of the cloud and fully testable on-device.

**P1-0 — Background-capture feasibility spike + service-driven trigger surface (M)**
Touches: `smartautoclicker/src/main/java/.../localservice/LocalService.kt`, `SmartAutoClickerService.kt`, `core:scheduling` (a new `DetectionTrigger` interface), `core:smart:processing` (`SmartProcessingRepositoryImpl`). This ticket exists because of the load-bearing constraint above: **MediaProjection consent is an Activity-result, foreground-only grant.** The spike must answer, with running code, exactly one question: *can a detection burst be initiated on a schedule while the foreground service holds a live projection token?* Concretely:
- Confirm that once the user has started a session (projection token acquired via `startScreenRecord(resultCode, data)` and the AccessibilityService connected), a programmatic trigger can call `SmartProcessingRepositoryImpl.startDetection(context, liveDebugging=false, generateReport=false, autoStopDuration=<short>)` and produce frames — **provided `setScenarioId(...)` has been called first** (otherwise `startDetection` early-returns).
- Define a `DetectionTrigger` seam owned by `LocalService` (it already holds the projection lifecycle and is the static-locator entry point via `LocalServiceProvider`). The scheduler (P1-5b) talks to this seam; it never talks to MediaProjection directly.
- Document the **no-projection / service-down behavior**: if `LocalServiceProvider.getLocalService()` is null or no projection token is live, the trigger must no-op (and surface a "not capturing — session not started" state to the cloud/UI), never attempt to acquire consent in the background.
Dependencies: P0-4.
DoD: a written feasibility note plus a manual end-to-end demo — with the foreground session already running, an in-process timer fires `startDetection(autoStopDuration=2s)` and one detection burst is observably executed (logged frame acquisition). The no-op-when-down path is demonstrated by killing the service and confirming the trigger logs "skipped, no projection" instead of crashing. **This ticket gates P1-5b and is the single largest functional de-risking item in P1 — staff it alongside P1-1.**

**P1-1 — JNI change: surface recognized OCR text to Kotlin (L)**
Touches: `core/smart/detection/src/main/cpp/jni/jni_detection_result.cpp` (`toJniResult`), `core/smart/detection/src/main/cpp/smartautoclicker.cpp` (`detectTextNative`), `core/smart/detection/src/main/java/.../NativeDetector.kt` (`detectTextNative` external decl + marshalling), `core/smart/detection/src/main/java/.../DetectionResult.kt`. The facts confirm `toJniResult` returns a fixed 7-element `DoubleArray` `[detected, centerX, centerY, width, height, confidence, numberDetected]` and the recognized string (`TextRecognizerResult.text`, available in C++ at `text_matcher.cpp` lines 64–77) is discarded after fuzzy matching. Add the string to the marshalling path. Lowest-risk approach: keep `detectNumberNative` untouched and change **only** `detectTextNative`'s return from `jdoubleArray` to a small `jobject` carrying the existing numeric array plus a `jstring recognizedText`. Add `val recognizedText: String? = null` to `DetectionResult` and populate it in the `detectText` marshalling path only.
Dependencies: none (pure detection layer). Highest native-risk ticket — schedule early; see P1-1a for the guard.
DoD: a unit/instrumentation test asserts `detectText(...)` populates `DetectionResult.recognizedText` with the OCR string; `detectNumber` and `detectImage`/`detectColor` results are byte-for-byte unchanged (their JNI signatures and `toJniResult` 7-element path are untouched); NDK build green on all configured ABIs.

**P1-1a — JNI regression guard: multi-ABI CI gate + feature flag (S)**
Touches: CI workflow (NDK build/test matrix across all configured ABIs), `core/smart/detection/.../ImageDetector.kt`/`NativeDetector.kt` (a `recognizedTextEnabled` compile-or-runtime flag). Because P1-1 is the highest-technical-risk item and touches the native marshalling boundary shared by all detection types, this ticket adds (a) a CI job that builds and runs the detection instrumentation tests for every ABI on every PR touching `core/smart/detection/src/main/cpp/**`, and (b) a guard so the new text-marshalling path can be disabled at runtime, falling back to the legacy 7-element `DoubleArray` behavior, without reverting the build — so a regression in OCR text surfacing cannot take down Number/Image/Color detection in production.
Dependencies: P1-1.
DoD: CI fails if any ABI's detection tests regress; toggling the flag off restores byte-for-byte legacy `detectText` results (text just returns null) and is covered by a test.

**P1-2 — Observation domain model + persistence wiring (M)**
Touches: `core:observation` — a domain `Observation` data class and the code that hands a built `Observation` to the DAO (P1-3). This is the assembly/persistence half; the *value extraction* half is P1-2a, which is a hard dependency because — as the facts confirm — `ProcessedConditionResult.Screen` carries **none** of the raw extracted values needed for the `value` field.
Build a domain `Observation { idempotencyKey, scenarioId, deviceId, deviceCapturedAt, value: String, valueType, confidence, isFulfilled, cropPath? }` and persist it via `ObservationDao.add`. Per contract story #3, **false reads are still recorded** (`isFulfilled=false` rows are persisted).
Dependencies: P1-2a, P1-3, P0-4, P0-6.
DoD: given an extraction event from P1-2a, exactly one `Observation` is built and persisted carrying value + confidence + `deviceCapturedAt` + `isFulfilled` + `deviceId`.

**P1-2a — Extend the processing seam to surface raw value + timestamp (M) — corrects the prior data-flow error**
Touches: `core/smart/processing/src/main/java/.../data/processor/ConditionsVerifier.kt`, `core/smart/processing/src/main/java/.../domain/SmartProcessingListener.kt`, and the new listener implementation in `core:observation`.
**Why this ticket exists (reviewer-flagged factual correction):** the source confirms `onScreenConditionProcessingCompleted(result: ProcessedConditionResult.Screen)` takes a *single* parameter, and `ProcessedConditionResult.Screen` exposes only `isFulfilled, haveBeenDetected, condition, confidenceRate, position, size`. It does **not** carry `numberDetected`, the recognized text, or a timestamp. The raw number lives on the lower-level `DetectionResult.numberDetected` (a local `numberDetected: Double?` at `ConditionsVerifier.kt` line 201), the recognized text will live on `DetectionResult.recognizedText` after P1-1, and the verification timestamp is the **private** field `currentVerificationTsMs` set at `ConditionsVerifier.kt` line 57. None of these are reachable from the current callback. The earlier plan's claim that the listener "reads `numberDetected`" and "captures `deviceCapturedAt` from the verifier's `currentVerificationTsMs` via listener context" was **false** and is corrected here.
The fix is to widen the seam inside `ConditionsVerifier`, where all three values are in scope, choosing one of:
1. **(Preferred) Extend the callback signature** to a richer observation hook, e.g. add `fun onScreenConditionExtracted(condition: ScreenCondition, result: ProcessedConditionResult.Screen, recognizedNumber: Double?, recognizedText: String?, verificationTsMs: Long) = Unit` to `SmartProcessingListener` (a `= Unit` default keeps every existing implementer source-compatible), and invoke it from each `verify*` method right next to the existing `onScreenConditionProcessingCompleted` calls (lines 149, 185, 228, 255), passing `currentVerificationTsMs` and the in-scope `detectionResult.numberDetected` / `detectionResult.recognizedText`.
2. Or add the raw extracted value + timestamp **as fields on a new sibling of `ProcessedConditionResult.Screen`** and emit through the existing callback.
Option 1 is recommended because it is purely additive (defaulted interface method) and keeps `ProcessedConditionResult.Screen`'s existing consumers untouched.
Dependencies: P1-1 (for `recognizedText`); P0-4.
DoD: an on-device test runs a Number scenario and a Text scenario and asserts the new hook fires per condition with the correct raw `recognizedNumber` (for Number), `recognizedText` (for Text), and a non-zero `verificationTsMs`; existing `SmartProcessingListener` implementers compile unchanged.

**P1-2b — Value/state stringification & typing rules (S) — closes the schema-mapping gap**
Touches: `core:observation` (a small `ObservationValueMapper`). Defines exactly how each read type maps onto the persisted `value: String` + `valueType` fields (the prior plan left this undefined):
- **Number**: `valueType = "NUMBER"`, `value =` the `recognizedNumber: Double` rendered with a fixed, locale-independent format (e.g. `Double.toString()` / `BigDecimal` plain string, no thousands separators, `.`-decimal). No units are inferred (units, if any, are a dashboard-side concern).
- **Text**: `valueType = "TEXT"`, `value =` the `recognizedText` string verbatim.
- **Color**: `valueType = "COLOR_STATE"`, `value =` a state string derived from the result — `"DETECTED"` when `haveBeenDetected` is true else `"ABSENT"`, with `isFulfilled` stored separately in its own column (so the "should-be-detected" semantics are not collapsed into the value string).
`confidence` is `result.confidenceRate` (0–100 Double) for all types.
Dependencies: P1-2a.
DoD: unit tests pin the exact string for each read type, including a false Number read (no number → not persisted as a value but the failed verification is still recorded with `valueType="NUMBER"`, empty/null value, `isFulfilled=false`).

**P1-3 — `ObservationEntity` + DAO + manual migration 21→22 (M)**
Touches: new `entity/ObservationEntity.kt` and `dao/ObservationDao.kt` under `core/smart/database/src/main/java/.../`; `ClickDatabase.kt` (`@Database(entities=[...])` + abstract `val observationDao`); `DatabaseInfo.kt` (verified `DATABASE_VERSION = 21` → `22`, add `OBSERVATION_TABLE` constant); new `migrations/Migration21to22.kt`. The contract requires a **manual** `Migration(21, 22)` — the codebase reserves manual migrations for non-trivial changes (cf. `Migration19to20`), and creating a new table with FK + unique index is cleanest as an explicit `CREATE TABLE`. Follow the entity recipe from the facts: `@Entity @Serializable`, `@PrimaryKey(autoGenerate=true) override var id: Long` (implement `EntityWithId`), indices on hot columns, FK with `onDelete=CASCADE` to scenario.

Reconciled schema (adds the columns the orchestration epics require — `deviceId`, `cropUrl` — and a typed `syncState`):
```kotlin
@Entity(
    tableName = OBSERVATION_TABLE,
    foreignKeys = [ForeignKey(
        entity = ScenarioEntity::class, parentColumns = ["id"],
        childColumns = ["scenarioId"], onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index("scenarioId"),
        Index("deviceCapturedAt"),
        Index("idempotencyKey", unique = true),
        Index("syncState"),          // hot path for getUnsynced()
    ],
)
@Serializable
data class ObservationEntity(
    @PrimaryKey(autoGenerate = true) override var id: Long,
    val idempotencyKey: String,
    val scenarioId: Long,
    val deviceId: String,            // from P0-6; absent from prior schema — added
    val deviceCapturedAt: Long,
    val value: String,
    val valueType: String,           // "NUMBER" | "TEXT" | "COLOR_STATE" (see P1-2b)
    val confidence: Double,
    val isFulfilled: Boolean,
    val cropPath: String? = null,    // local PNG path (P2-3)
    val cropUrl: String? = null,     // remote URL set after upload (P2-3) — added
    val syncState: Int = SyncState.PENDING.value,
) : EntityWithId
```
**`syncState` semantics (was undefined):** back it with an explicit enum mapped to the stored `Int`, e.g. `enum class SyncState(val value: Int) { PENDING(0), UPLOADING(1), SYNCED(2), FAILED(3) }`, with a Room `TypeConverter` or `Int`-column + mapper. `getUnsynced` selects `PENDING`/`FAILED`; `markSynced` sets `SYNCED`.
DAO pattern (from `CountersDao`/`ActionDao`): `@Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun add(o: ObservationEntity): Long`, `@Query suspend fun getUnsynced(limit: Int): List<ObservationEntity>`, `@Query suspend fun markSynced(ids: List<Long>)`, plus `@Query suspend fun setCropUrl(id: Long, url: String)`.
Dependencies: P0-4, P0-6 (for the `deviceId` column source).
DoD: migration test (Room `MigrationTestHelper`) opens a v21 DB with existing scenarios/conditions and migrates to v22 with all prior data intact and the new table present; `observationDao.add` round-trips; `idempotencyKey` unique index rejects dupes (second insert with same key is ignored).

**P1-3a — Mapper + Room/Hilt migration registration (S) — closes the wiring gap**
Touches: `core/smart/database/src/main/java/.../entity/...` mappers and **`core/smart/database/src/main/java/.../di/Hilt.kt` (`SmartDatabaseModule`)**. Two pieces the prior plan omitted:
1. **Entity↔domain mapper.** The documented recipe requires an `entity ↔ domain` mapper for every new persisted type (as `ConditionMapper`/`ActionMapper` do for conditions/actions). Add an `ObservationMapper` in the database (or `core:observation`) module converting `ObservationEntity ↔ Observation`. (Note: `Observation` is a *new* persisted type, not a new `ScreenCondition` subtype, so it does **not** touch `Condition.copyWithNewId()` or `ConditionType`; the condition-recipe steps about `copyWithNewId`/`ConditionType` do not apply here. This is called out explicitly to prevent an engineer from editing the condition sealed hierarchy by mistake.)
2. **Register the migration and DAO in the Hilt provider.** The verified `SmartDatabaseModule.providesClickDatabase(...)` builds the DB via `Room.databaseBuilder(...).addMigrations(Migration1to2, …, Migration19to20)` — manual migrations are listed there, *not* auto-discovered. `Migration21to22` **must be added to that `addMigrations(...)` call**, and the new `abstract val observationDao` on `ClickDatabase` is what exposes the DAO to injectors. The prior plan mentioned only `ClickDatabase.kt`'s entity list and silently assumed the migration would be picked up — it will not be.
Dependencies: P1-3.
DoD: `SmartDatabaseModule` lists `Migration21to22` in `addMigrations(...)`; a Hilt-injected `ClickDatabase.observationDao()` resolves at runtime; mapper round-trip test (`Observation → ObservationEntity → Observation`) passes.

**P1-4 — `TrackingScenario` ↔ local `Scenario` bridge (M)**
Touches: `core:observation` (an adapter that maps a cloud `TrackingScenario` — read type, `detectionArea` Rect, alphabet, interval — onto a single-`ScreenEvent` `Scenario` reusing `ScreenCondition.Number/Text/Color`). The facts show `ScreenCondition.Text` carries `text: String`, `detectionArea: Rect`, `alphabet: OCRAlphabet`; `ScreenCondition.Number` carries `detectionArea: Rect`, `comparisonOperation: ComparisonOperation`, `counterValue: CounterOperationValue`; `ScreenCondition.Color` carries `color`, `detectionArea`. So a `TrackingScenario` maps to one synthetic condition with **no actions** (read-only). `detectionArea` is device screen-space; `ScalingManager` handles scale-up of results.

**Required-field sentinels (reviewer note).** These condition subtypes have non-null fields with *matching* semantics that a read-only tracker does not have, so the adapter must synthesize sentinels rather than hit non-null fields cold:
- `ScreenCondition.Number` requires `comparisonOperation` + `counterValue`. For read-only capture we never want the comparison to gate anything, so synthesize an **always-detect** sentinel (e.g. `comparisonOperation = GREATER_OR_EQUALS`, `counterValue = Number(Double.NEGATIVE_INFINITY)` or the lowest representable value) so `isFulfilled` tracks "a number was read" rather than a threshold. The *raw* value we persist comes from P1-2a's `recognizedNumber`, independent of this comparison.
- `ScreenCondition.Text` requires `text` (the fuzzy-match target). For read-only OCR there is no target; set `shouldBeDetected = true` and a permissive sentinel, and persist the *actual* `recognizedText` from P1-2a. Document that `isFulfilled` for Text reflects the sentinel match, not a meaningful condition.
- **`detectNumber` ignores the alphabet (reviewer note).** Per the facts, `detectNumber` always uses the first-loaded recognition model (`defaultRecognitionModelId`) and ignores any selected `OCRAlphabet`. So when a `TrackingScenario` for a **Number** read specifies an alphabet, the adapter must record that the alphabet is **silently ignored for Number reads** (only Text reads honor `alphabet`). The DoD must assert this rather than implying Number honors the alphabet.
Dependencies: P1-3.
DoD: given a `TrackingScenario` JSON, the adapter produces a runnable `Scenario`/`ScreenEvent` whose single condition matches the requested read type with the documented sentinels; **no `Action` is attached** (verifies read-only); a Number `TrackingScenario` with a non-LATIN alphabet still runs and the test asserts the alphabet had no effect on number recognition.

**P1-5a — WorkManager + Hilt-Work infrastructure wiring (M)**
Touches: `core:scheduling` (`@HiltWorker CoroutineWorker` skeleton), `smartautoclicker/src/main/java/.../application/SmartAutoClickerApplication.kt`. WorkManager + Hilt-Work are net-new (confirmed absent), so this ticket only stands up the framework: add the `HiltWorkerFactory` injection, make `SmartAutoClickerApplication` (`@HiltAndroidApp`) implement `Configuration.Provider`, and prove a trivial `CoroutineWorker` can be enqueued and run with an `@Inject`ed dependency. **No detection logic yet** — this is deliberately split from the capture trigger because introducing WorkManager into an AccessibilityService-centric app carries unscoped lifecycle risk and should land green before any capture code depends on it.
Dependencies: P0-3.
DoD: a no-op `@HiltWorker` enqueued via `WorkManager.getInstance(context).enqueueUniqueWork(...)` runs and resolves an injected dependency; app still boots and the existing foreground-service lifecycle is unaffected.

**P1-5b — Scheduler → service-driven detection burst (M)**
Touches: `core:scheduling` (the real worker body), consuming the `DetectionTrigger` seam from P1-0. The worker does **not** acquire MediaProjection and does **not** call `startDetection` directly against cold state. It asks the `DetectionTrigger` (owned by `LocalService`) to run one burst, which requires: (a) `LocalServiceProvider.getLocalService()` non-null, (b) a live projection token, (c) `setScenarioId(...)` already called for the active tracking scenario. The trigger wraps `SmartProcessingRepositoryImpl.startDetection(context, liveDebugging=false, generateReport=false, autoStopDuration=<short>)` (the facts confirm `autoStopDuration` launches the auto-stop job at lines 173–179). Enforce the 5s minimum interval. One trigger fire = one detection burst = one Observation per active scenario. **When the service is down or no projection is live, the worker no-ops gracefully and reports "not capturing"** (behavior defined and demoed in P1-0).
Dependencies: P1-0, P1-2, P1-4, P1-5a.
DoD: with a foreground session running (projection live, `scenarioId` set), a Number tracking scenario at 10s interval yields ~one Observation/10s (contract acceptance gate); stopping the schedule halts within one cycle; with the service stopped, the worker logs a skip and persists nothing.

### Epic P2 — Connectivity & cloud sync

This epic moves Observations off-device and pulls scenarios/commands down. All code is `cloud`-flavor-gated (linked via `cloudImplementation`).

**P2-1 — `core:network` REST/auth client (L)**
Touches: `core:network` — Retrofit service for `/v1` (Trax Cloud), token store, device-scoped auth header. Linked via `cloudImplementation(project(":core:network"))`.
Dependencies: P0-2, P0-3, P0-4.
DoD: authenticated `GET /v1/...` round-trips against a stub server; tokens persist across process death.

**P2-2 — Offline queue + batched upload with idempotency (L)**
Touches: `core:network` (sync engine) + `core:observation` (`ObservationDao.getUnsynced`/`markSynced` + `syncState` transitions). Batch-upload `PENDING`/`FAILED` Observations using their `idempotencyKey`; move rows to `UPLOADING` during the request and to `SYNCED` on `2xx`, back to `FAILED` on error. Reuse the `BackupRepository` `channelFlow`-of-sealed-states progress pattern as the model for sync-state reporting. Retry with backoff (WorkManager constraints) on reconnect.
Dependencies: P1-3, P2-1.
DoD: contract gate — offline-captured Observations upload on reconnect with **zero server-side duplicates** (idempotency-key dedup); on-device `syncState` is observable as a `Flow`.

**P2-3 — Crop capture + upload (M)**
Touches: `core:observation` (call `BitmapRepository.saveImageConditionBitmap(bitmap, "Observation_")` when `cropCaptureEnabled`; the facts confirm the prefix-based API and recommend exactly this prefix, storing as `{prefix}{hash}.png` in `filesDir`); `core:network` (multipart upload, then `ObservationDao.setCropUrl(id, url)`). Crop the screen frame to the condition's screen-space `detectionArea`.
Dependencies: P1-2, P2-2, P2-5.
DoD: with crop enabled, PNG stored on-device (`cropPath` set), uploaded, `cropUrl` set on the row.

**P2-4 — Scenario pull + apply (M)**
Touches: `core:network` (`GET /v1/scenarios` for this device) → `core:observation` adapter (P1-4) → `core:scheduling` (schedule the pulled scenarios via the P1-5b trigger). Dependencies: P1-4, P1-5b, P2-1. DoD: a dashboard-authored scenario assigned to the device is fetched on next sync and begins emitting Observations tagged with the device's `deviceId` (from P0-6) once a foreground session is live.

**P2-5 — Cloud settings (DataStore) (S)**
Touches: `core/common/settings/src/main/java/.../engine/data/SettingsDataSource.kt` (+`SettingsRepositoryImpl.kt`, `SettingsRepository.kt`). Add `KEY_ACCOUNT_BOUND`, `KEY_SYNC_ENABLED`, `KEY_CROP_CAPTURE` following the documented `Preferences.Key` → `Flow` → `StateFlow` recipe. Dependencies: none (parallelizable). DoD: toggles persist and expose `Flow<Boolean>`; sync engine and crop capture read these.

### Epic P3 — Remote orchestration

**P3-1 — Device enrollment via pairing code (M)** — Touches: `feature:cloud` (enrollment UI), `core:network` (`POST /v1/devices/enroll`), `core:common:settings` (`KEY_ACCOUNT_BOUND`), `core:observation` (reconcile server-assigned device id with the client `deviceId` from P0-6). DoD: contract story #1 — device binds to Tenant within 30s; invalid/expired codes rejected clearly; the persisted `deviceId` used on Observations is the agreed (server-reconciled) identity. Dependencies: P0-6, P2-1, P2-5.

**P3-2 — Remote start/stop command pull (M)** — Touches: `core:network` (`GET /v1/devices/{id}/commands`), `core:scheduling` (start/stop the WorkManager schedule via the P1-5b trigger), `feature:cloud` (status). Commands idempotent + expiring. Remote *start* can only resume capture if a foreground session/projection is live; otherwise the command is acknowledged but the device reports "awaiting session" (per the P1-0 no-op contract). DoD: remote stop halts within one poll cycle; dashboard reflects `tracking=false`; start resumes when a session is available (contract gate). Dependencies: P1-5b, P2-1.

**P3-3 — Author-once push-to-many (M)** — Touches: cloud backend assignment + reuse of P2-4 pull on each device. DoD: a scenario assigned to 3 devices → all 3 emit Observations within one sync cycle (sessions live), each tagged with its own `deviceId` (contract gate). Dependencies: P2-4, P3-1.

**P3-4 — Tenant isolation enforcement (M)** — Touches: cloud backend authz (every read/write scoped to authenticated Account's Tenant; device tokens authorize only that device's Observations/assigned scenarios). DoD: Tenant A cannot touch Tenant B's data; cross-tenant requests rejected (contract gate). Dependencies: P2-1, P3-1.

### Epic P4 — Vision polish & dashboard

**P4-1 — Dashboard time series + latest-per-device (L)** — line/scatter of value over time, device + range filters, low-confidence points distinguished, latest-value summary table. Dependencies: P2-2, P3-4. DoD: contract dashboard gate.
**P4-2 — Crop viewer in dashboard (S)** — renders `cropUrl` per Observation. Dependencies: P2-3, P4-1.
**P4-3 — Scenario authoring/assignment UI (M)** — read type + detectionArea + alphabet + interval + `cropCaptureEnabled`; surfaces that alphabet is ignored for Number reads (per P1-4). Dependencies: P2-4, P3-3.
**P4-4 — Fleet start/stop controls (S)** — dashboard buttons over P3-2. Dependencies: P3-2.
**P4-5 — On-device sync-status + capture-status UI (S)** — Touches: `feature:cloud`, reading P2-2 sync state and the P1-0/P1-5b "capturing / awaiting session" state. Dependencies: P2-2, P1-5b.

### Critical-path ordering

The longest dependency chain (and therefore the schedule driver), corrected so it no longer routes the local-only modules through P0-2:

`P0-1 → P0-4 → P1-3 → P1-4 → P1-5b → P2-4 → P3-3 → P4-3`

with `P1-5b` additionally gated by `P1-0` and `P1-5a` (both feeding into the first scheduler-driven burst). Notes:
- **P0-2 is not on the critical path.** `core:observation`/`core:scheduling` scaffolding (P0-4) depends only on P0-1; P0-2 is required only for the *cloud-only link* of `core:network`/`feature:cloud`, which first matters at P2-1. The prior `P0-1 → P0-2 → P0-4` claim overstated the dependency and is removed.
- **P1-0 (background-capture feasibility) and P1-1 (JNI) are the two highest-risk items** and must be staffed first in parallel. P1-0 de-risks the entire scheduled-capture premise (P1-5b/P2-4/P3-2 all assume it); P1-1 de-risks the native marshalling change (guarded by P1-1a). Neither is on the literal longest chain, but both can sink the project if deferred.
- **P1-1 must finish before P1-2a** (which needs `recognizedText`), and **P1-2a must finish before P1-2/P1-2b** (which need the raw value + timestamp the seam now surfaces).
- **P0-3, P0-5, P0-6, P2-5** are dependency-free and can be done opportunistically.
- Cloud-backend tickets (**P3-4**, server side of **P3-3**) can proceed against the OpenAPI `/v1` contract in parallel with on-device P1 work, synchronizing at **P2-1**.

### Sizing caveats (reviewer-flagged)

- **P1-1 (JNI return-type change across cpp/jni/Kotlin marshalling + multi-ABI + tests)** is sized `L` but carries unscoped native risk; if the `jobject` return-type change ripples into shared marshalling helpers it can exceed two weeks. P1-1a's CI gate is the early-warning mechanism.
- **P1-5 was a single optimistic `L`; it is split** into P1-5a (framework wiring, `M`) and P1-5b (service-driven burst, `M`) precisely because introducing WorkManager + Hilt-Work + `Configuration.Provider` into an AccessibilityService/MediaProjection app is the kind of platform-integration risk that should not share a ticket with capture logic.

### Thinnest end-to-end vertical slice (build this first)

Before any breadth, prove the pipeline with a deliberately narrow cut — and prove the *riskiest* assumption (scheduled background capture) **before writing any WorkManager code**, per the reviewer's suggestion.

**Phase A — manual-trigger proof (no WorkManager).** Scope: **one device, one hard-coded Number tracking scenario, no crop, no dashboard authoring.** Exercise: P0-1 + P0-4 (build compiles a `cloud` flavor and the new modules) → P1-3 + P1-3a (Observation persists, migration 21→22 lands and is registered in `SmartDatabaseModule`) → P1-2a + P1-2b + P1-2 (a Number value + timestamp are surfaced from `ConditionsVerifier` and assembled into a persisted Observation) → **manually start a foreground session and fire one detection burst from the already-working service path (P1-0's `DetectionTrigger`), persisting one Observation.** This validates the detection-listener seam, the new Room table + migration registration, and the value-stringification rules **without depending on the unsolved-until-P1-0 background-capture problem and without WorkManager at all.**

**Phase B — add the scheduler.** Only once Phase A is green and P1-0's feasibility note confirms a session-bound trigger works: layer in P1-5a + P1-5b so the burst fires on a 10s schedule, then P2-1 + P2-2 so the Observation uploads idempotently, ending in a stub dashboard that lists the values.

Notably this slice **skips P1-1 (JNI)** by using a Number read, whose value arrives via `DetectionResult.numberDetected` (the existing 7-element `DoubleArray`, surfaced through the P1-2a seam) — so the riskiest *native* change is off the proof-of-pipeline path, and Text support (P1-1 + its use in P1-2a) lands immediately after as the first widening step. When Phase B is green, every architectural seam — flavor gating and cloud-only linking, the new Room table + registered migration, the widened detection seam carrying raw value + timestamp, the deviceId stamping, the service-driven (not background-cold) capture trigger, and idempotent upload — has been validated, and the remaining tickets are additive breadth (more read types, enrollment, remote control, richer dashboard) rather than new risk.

---

## Data contracts & schemas (canonical reference)

This section is the single source of truth for every data shape in TraxIntel. Other sections — capture, sync, scheduling, cloud, dashboard — defer here for exact column types, JSON field names, id semantics, and enum values. Where a downstream section restates a shape, this one wins.

The design problem is that TraxIntel spans three storage tiers with three different id idioms that must interoperate: (1) the existing on-device Room database (`core:smart:database`), whose primary keys are `Long` auto-increment values wrapped in `Identifier` (`/Users/Sophia/Documents/GitHub/AutoClicker/core/common/base/src/main/java/com/buzbuz/smartautoclicker/core/base/identifier/Identifier.kt`); (2) a new on-device append-only Observation store; and (3) the Trax Cloud relational DB behind the `/v1` REST API. The contract below reconciles them with a deliberate id strategy: legacy local rows keep `Long`/`Identifier`; everything that crosses the network is keyed by **client-generated UUIDv4 strings**.

### Id strategy: client UUIDs for offline-first, idempotent ingest

The existing `Identifier` type is unsuitable for cloud sync. Its `databaseId` is `0L` (`DATABASE_ID_INSERTION`) until Room assigns an autoincrement value on insert (`Identifier.isInDatabase()` returns `databaseId != 0L`), so the id is unknown until the row hits the local DB and is never globally unique across devices. A fleet of 3–20 devices all minting `scenario_id = 1` would collide on ingest.

Rule: **any entity that is created on-device and later uploaded carries a `String` UUIDv4 generated at creation time, before the row is persisted.** This applies to `Observation.id`. The UUID is the offline-first primitive — the device can create, queue, and reference an Observation with zero round-trips, and the server adopts the client id verbatim. `TrackingScenario` and `Device` ids are minted **server-side** (they are cloud-authored / enrollment-time) and flow down to the device as opaque strings; the device never invents them.

**A single UUID per Observation serves as both primary key and idempotency key.** The MVP does not split `id` and `idempotencyKey`: there is no exercised path in which a client retries an Observation under a *new* id while keeping a stable dedup key, so a second column would carry no information. The cloud `observations` table places a `UNIQUE` constraint on `(tenant_id, id)`. On `POST /v1/observations:batch`, the server upserts with `ON CONFLICT (tenant_id, id) DO NOTHING` and reports each row as `accepted` or `duplicate`. This is what makes the acceptance gate "offline-captured Observations upload on reconnect with zero server-side duplicates" hold: a batch that partially succeeded, then got retried after a dropped connection, re-sends the same ids and the server silently no-ops the already-stored rows. The client may safely re-enqueue the entire batch on any non-2xx without tracking partial progress.

The id is generated once, at capture time (`UUID.randomUUID().toString()`), and is **stable for the life of the row** — capture-side code must never re-generate it on retry. The local row is inserted with this UUID as its primary key, so the on-device store, the wire payload, and the cloud row all share one identity end-to-end. (See the local-insert correctness note under `ObservationDao` for how a crash-replay re-insert of the *same* id is handled.)

### Timestamps, timezones, units, and normalization

These conventions are global and every shape below obeys them.

- **Timestamps are UTC epoch milliseconds (`Long`/`bigint`) on-device and in Room.** The capture path already has this: `ConditionsVerifier` captures `currentVerificationTsMs` via `System.currentTimeMillis()` at verification start (the value other sections call the capture timestamp). `deviceCapturedAt` is exactly this `Long`. No `java.time` zoned types touch the DB.
- **On the wire, timestamps are RFC-3339 / ISO-8601 UTC strings** (e.g. `"2026-06-29T14:03:11.482Z"`), serialized from the epoch-millis `Long`. This keeps the JSON human-debuggable and lets the cloud store native `timestamptz`. **The serialization seam is pinned to a single formatter so capture and sync cannot drift on precision or zone:** `DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(deviceCapturedAt))` on serialize, and `Instant.parse(s).toEpochMilli()` on deserialize. `ISO_INSTANT` emits millisecond precision with a trailing `Z` and never a numeric offset, matching the example above. The dashboard renders in the viewer's local zone; the stored truth is always UTC.
- **`serverReceivedAt` is assigned by Trax Cloud at ingest**, never by the device. It is absent in the upload payload and present in pull/dashboard responses. The pair `(deviceCapturedAt, serverReceivedAt)` lets the dashboard distinguish capture latency from sync latency (important for the offline-batch case where a reading captured at T arrives hours later).
- **Number normalization.** Extracted numbers come from `DetectionResult.numberDetected: Double?` (`/Users/Sophia/Documents/GitHub/AutoClicker/core/smart/detection/src/main/java/com/buzbuz/smartautoclicker/core/detection/DetectionResult.kt`). The native marshalling uses a sentinel: in `toDetectionResult()` the 7th array element equal to `Double.MIN_VALUE` decodes to `null` (`numberDetected = if (numberDetected == Double.MIN_VALUE) null else numberDetected`). Therefore a Number Observation stores the raw `Double` with **no rounding, no locale formatting, no unit attached** — TraxIntel tracks the displayed glyph value, and unit semantics ("gold", "kWh") live only in the scenario's human-facing name, never in the value. A failed number read (`null`) becomes an Observation with `isFulfilled = false` and `value = null` rather than being dropped (acceptance criterion 3).
- **Text normalization (BLOCKED on the capture section's JNI work — see caveat below).** Text reads require a native change: the recognized string lives in `TextRecognizerResult.text` (`/Users/Sophia/Documents/GitHub/AutoClicker/core/smart/detection/src/main/cpp/detector/matching/text/recognition/text_recognizer_result.hpp`) but is currently **consumed for fuzzy matching and discarded in C++** — the JNI bridge marshals only a 7-element `jdoubleArray` (`[detected, centerX, centerY, width, height, confidence, numberDetected]`) with **no text slot** (`/Users/Sophia/Documents/GitHub/AutoClicker/core/smart/detection/src/main/cpp/jni/jni_detection_result.cpp`). Until that JNI change lands, **`value` for a `TEXT` Observation cannot be populated** and the TEXT read type is not capturable. When surfaced, the contract stores the string **verbatim, trimmed of leading/trailing whitespace only** — no case-folding, no Unicode normalization (NFC/NFKC is explicitly out of scope to avoid mangling the CJK/Arabic/Indic alphabets supported by `OCRAlphabet`). Empty string after trim with `isFulfilled = true` is legal (the OCR matched but read blank); the dashboard treats it as a distinct value from `null`.
- **State (Color) normalization.** A Color-State read has no extracted scalar; its "value" is the boolean detection outcome. The canonical encoding is the string `"detected"` / `"not_detected"` in `value`, mirrored by `isFulfilled`. This keeps `value` a single `String?` column across all three read types rather than introducing a polymorphic value column.
- **Confidence is an integer 0–100, rounded half-up.** `ProcessedConditionResult.Screen.confidenceRate` is a `Double` (0.0–100.0) sourced from `DetectionResult.confidenceRate`. The contract rounds to `Int` with **`Math.round(confidenceRate).toInt()`** (round-half-up, ties toward positive infinity) — not truncation — so the dashboard's low-confidence threshold is deterministic and any cloud-side re-derivation produces the identical integer. Rounding happens exactly once, on-device, at Observation construction; the cloud never re-rounds.

### SyncState enum (on-device only)

Sync state is a purely local concern — it never appears in any wire payload or cloud column. It drives the offline queue in `core:network`.

```kotlin
enum class SyncState {
    PENDING,   // captured, not yet attempted
    UPLOADING, // in-flight batch
    SYNCED,    // server returned accepted|duplicate for this id
    FAILED,    // last attempt errored; eligible for retry with backoff
}
```

`SYNCED` covers both `accepted` and `duplicate` server responses — once the server acknowledges the id, the local row is durably synced regardless of which path. Observations are append-only (editing/back-fill is out of scope), so a row only ever moves `PENDING -> UPLOADING -> {SYNCED|FAILED}` and `FAILED -> UPLOADING`. There is no delete-on-sync; the retention section may prune `SYNCED` rows older than a window, but pruning is gated on the crop reference-counting rule below, not part of this contract's hot path.

### On-device Observation entity (Room)

**Module placement (must-fix resolution).** `ObservationEntity`, `ObservationDao`, and the `OBSERVATION_TABLE` constant physically live **inside `core:smart:database`** — not in a separate dependent module. This is forced by three verified facts in the existing code:

1. `ClickDatabase` is `abstract class ClickDatabase : ScenarioDatabase()` with a **hardcoded 8-entity `@Database(entities = [...])` list** — `ActionEntity`, `EventEntity`, `ScenarioEntity`, `ConditionEntity`, `IntentExtraEntity`, `EventToggleEntity`, `ScenarioStatsEntity`, `CountersEntity` (`/Users/Sophia/Documents/GitHub/AutoClicker/core/smart/database/src/main/java/com/buzbuz/smartautoclicker/core/database/ClickDatabase.kt`). A module that *depends on* `core:smart:database` cannot add its own class to that annotation — the dependency arrow points the wrong way.
2. The table-name constants in `DatabaseInfo.kt` are declared `internal` (`internal const val SCENARIO_TABLE = "scenario_table"`, etc.), so a separate module physically cannot reference them or add a sibling `OBSERVATION_TABLE` next to them.
3. The DAO must be exposed via the existing `ScenarioDatabase` abstract-val pattern, which is compiled inside this module.

Therefore the new entity is added to `core:smart:database` alongside the existing entities, `OBSERVATION_TABLE = "observation_table"` is added to `DatabaseInfo.kt` as another `internal const`, and `ObservationEntity::class` is appended to `ClickDatabase`'s `@Database(entities = [...])` list. (The bitmap dependency `core:common:bitmaps` is already on the `core:smart:database` graph via the cropPath usage path; no new dependent module is introduced.) The earlier "new `core:observation` module that depends on `core:smart:database` and is added to `ClickDatabase`" design is abandoned because it cannot compile.

**Migration (must-fix resolution).** The table lands via **`AutoMigration(from = 21, to = 22)`** appended to `ClickDatabase`'s `autoMigrations` list, following the simple-additive-change path (a brand-new table with no data transform — Room infers the `CREATE TABLE`). Three concrete steps are required and all are load-bearing:

1. **Bump `DATABASE_VERSION` from `21` to `22`** in `DatabaseInfo.kt` (currently `const val DATABASE_VERSION = 21`).
2. **Add the entry** `AutoMigration(from = 21, to = 22)` to the `autoMigrations` array in `ClickDatabase.kt`. No `AutoMigrationSpec` is needed (nothing is deleted or renamed).
3. **Commit the generated schema JSON.** `ClickDatabase` has `exportSchema = true` and committed schemas exist through `/Users/Sophia/Documents/GitHub/AutoClicker/core/smart/database/schemas/com.buzbuz.smartautoclicker.core.database.ClickDatabase/21.json`. A 21→22 AutoMigration **will not build** unless the generated `22.json` is checked in under that same directory. The build must be run once to emit `22.json`, and that file committed alongside the version bump — this is a hard gate, not optional.

Because migration 22 only **adds** the `observation_table` and touches none of the eight existing tables (`scenario_table`, `event_table`, `action_table`, `condition_table`, `intent_extra_table`, `event_toggle_table`, `scenario_usage_table` / `ScenarioStatsEntity`, `counters_table`), no existing scenario/event/condition/stats/counter row is read or rewritten, satisfying the "21 → 22 lands cleanly with existing scenarios intact" gate against the *full* real entity set.

**Deviation from the legacy `Long` PK convention.** The legacy entities (`ScenarioEntity`, `EventEntity`, etc.) all use a `Long` primary key (`@PrimaryKey override var id: Long`). This entity deliberately uses a `String` UUID primary key, per the id strategy. There is **no foreign key to the local scenario tables** — `scenarioId` here is the cloud scenario UUID, not a local `ScenarioEntity.id`, so a Room `ForeignKey` would be wrong. Instead we index the columns the upload query and crop-cleanup query filter on.

```kotlin
@Entity(
    tableName = OBSERVATION_TABLE, // "observation_table", add as internal const in DatabaseInfo.kt
    indices = [
        Index("sync_state"),                 // offline queue scan: WHERE sync_state IN ('PENDING','FAILED')
        Index("scenario_id"),
        // No unique index needed on id beyond the @PrimaryKey itself.
    ],
)
@Serializable
data class ObservationEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")                 val id: String,            // client UUIDv4 (also the cloud id / dedup key)
    @ColumnInfo(name = "tenant_id")          val tenantId: String,      // cloud-assigned, cached on device
    @ColumnInfo(name = "device_id")          val deviceId: String,      // cloud-assigned at enrollment
    @ColumnInfo(name = "scenario_id")        val scenarioId: String,    // cloud scenario UUID (NOT a local Long)
    @ColumnInfo(name = "device_captured_at") val deviceCapturedAt: Long,// UTC epoch millis
    @ColumnInfo(name = "value")              val value: String?,        // null when !isFulfilled
    @ColumnInfo(name = "value_type")         val valueType: String,     // "NUMBER" | "TEXT" | "STATE"
    @ColumnInfo(name = "confidence")         val confidence: Int,       // 0..100, Math.round(confidenceRate)
    @ColumnInfo(name = "is_fulfilled")       val isFulfilled: Boolean,
    @ColumnInfo(name = "crop_path")          val cropPath: String?,     // content-addressed PNG name, "Observation_" prefix
    @ColumnInfo(name = "sync_state")         val syncState: String,     // SyncState.name
    @ColumnInfo(name = "retry_count")        val retryCount: Int = 0,   // backoff bookkeeping
)
```

Design choices:
- **`value` is a single nullable `String`** across all read types. Numbers are stored as their `Double.toString()` (lossless round-trip); the `value_type` discriminator tells the consumer how to parse. This avoids three nullable typed columns and matches the wire shape one-to-one.
- **`cropPath`** is the path returned by `BitmapRepository.saveImageConditionBitmap(bitmap, "Observation_")` (`/Users/Sophia/Documents/GitHub/AutoClicker/core/common/bitmaps/src/main/java/com/buzbuz/smartautoclicker/core/bitmaps/BitmapRepository.kt`). It is null when the scenario's `cropCaptureEnabled` is false or the read failed. See the crop-lifecycle section below for the exact filename semantics and the reference-counting rule.
- **`serverReceivedAt` is intentionally absent** from the local entity — the device never knows it. It exists only cloud-side and in pull/dashboard responses.

#### Crop file naming and lifecycle (content-addressed, deduplicated — must-fix resolution)

The crop PNG name is **not** keyed to the Observation. Verified in `ConditionBitmapsDataSource.saveBitmap()`: the file name is `"$prefix${bitmap.getBitmapIdentifier()}${FILE_EXTENSION_PNG}"`, where `getBitmapIdentifier()` returns the **`ByteBuffer.hashCode()` of the bitmap's pixel buffer** — a *signed `Int`* computed from pixels, not from any observation id. So a crop file is named **`Observation_<signed-pixel-hashCode>.png`** (e.g. `Observation_-1543.png`), and the function is **content-addressed and deduplicating**: if a file with that name already exists, `saveBitmap()` logs and returns the existing path **without rewriting** (`if (file.exists()) { ...; return path }`).

This has a direct, dangerous consequence for retention pruning: **two distinct Observations whose crop pixels are byte-identical share exactly one PNG file on disk.** A naive `getPrunableCropPaths()` that deletes a `SYNCED` row's `cropPath` could delete a PNG still referenced by a newer `PENDING` (un-uploaded) Observation, corrupting the pending crop upload.

The contract resolves this with **reference-counting before delete** (chosen over per-observation crop names, which would defeat the existing content-addressed dedup and bloat storage). A crop path is prunable only when **no remaining row references it**. The prune query and any deletion therefore filter on path-uniqueness, not row-identity:

```sql
-- A crop_path is deletable only if EVERY observation referencing it is SYNCED and old enough,
-- i.e. no surviving row (PENDING/UPLOADING/FAILED, or a newer SYNCED inside the window) still points at it.
SELECT crop_path FROM observation_table
 WHERE crop_path IS NOT NULL
 GROUP BY crop_path
HAVING MAX(CASE WHEN sync_state != 'SYNCED' OR device_captured_at >= :before THEN 1 ELSE 0 END) = 0
```

The retention worker then (a) deletes the rows it is pruning, and (b) calls `BitmapRepository.deleteImageConditionBitmaps(paths)` **only** for paths returned by the query above — guaranteeing no live (`PENDING`/`UPLOADING`/`FAILED`) or in-window `SYNCED` Observation loses its crop. This is the single source of truth for crop deletion; the sync and retention sections defer here.

DAO follows the established interface pattern (cf. `CountersDao`), and lives in `core:smart:database` exposed via `ScenarioDatabase`'s abstract-val pattern:

```kotlin
@Dao
interface ObservationDao {
    // INSERT IGNORE is safe here ONLY because id is stable for the row's life and reused verbatim.
    // A crash-replay re-insert of the SAME id is an intentional, lossless no-op: the original row
    // (identical id, value, timestamp) is already persisted, so dropping the duplicate write loses
    // nothing. There is NO path that re-inserts a DIFFERENT payload under an existing id, so IGNORE
    // cannot silently discard distinct data. Capture-side code MUST generate the UUID exactly once
    // per capture event and never reuse an id across two distinct readings.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(observation: ObservationEntity): Long  // -1 signals the IGNORE no-op; callers may log it.

    @Query("SELECT * FROM observation_table WHERE sync_state IN ('PENDING','FAILED') ORDER BY device_captured_at ASC LIMIT :limit")
    suspend fun getUploadBatch(limit: Int): List<ObservationEntity>

    @Query("UPDATE observation_table SET sync_state = :state, retry_count = :retry WHERE id IN (:ids)")
    suspend fun markSyncState(ids: List<String>, state: String, retry: Int)

    // Reference-counted prune candidates (see crop lifecycle above).
    @Query("""
        SELECT crop_path FROM observation_table
         WHERE crop_path IS NOT NULL
         GROUP BY crop_path
        HAVING MAX(CASE WHEN sync_state != 'SYNCED' OR device_captured_at >= :before THEN 1 ELSE 0 END) = 0
    """)
    suspend fun getPrunableCropPaths(before: Long): List<String>
}
```

The `insert` return value is the row-id or `-1` when the `IGNORE` conflict path fires; callers treat `-1` as "already captured, no-op" rather than an error.

### TrackingScenario representation

A `TrackingScenario` is **cloud-authored and device-assignable**; it is not a row in the legacy local scenario tables. On-device it is held as an in-memory domain model (cached for the active polling loop), reconstituted from the pull payload. Its capture parameters map directly onto the existing detection primitives so the scheduler can hand the engine a real `ScreenCondition`.

```kotlin
data class TrackingScenario(
    val id: String,                 // cloud UUID
    val tenantId: String,
    val name: String,               // human label, carries unit semantics
    val readType: ReadType,         // NUMBER | TEXT | STATE
    val detectionArea: Rect,        // device screen-space
    val alphabet: OCRAlphabet,      // used for TEXT; ignored for NUMBER/STATE
    val matchText: String?,         // TEXT only
    val color: Int?,                // STATE only (@ColorInt)
    val threshold: Int,             // reused detection threshold
    val pollIntervalMs: Long,       // >= 5000
    val cropCaptureEnabled: Boolean,
    val tracking: Boolean,          // see "tracking authority" note below
)

enum class ReadType { NUMBER, TEXT, STATE }
```

#### Reconstructing a `ScreenCondition` (synthetic Identifier/eventId/priority — must-fix resolution)

The legacy `ScreenCondition` subtypes all mandate three fields that have **no meaning in the tracking world** but are required by the data-class signatures (verified): `override val id: Identifier`, `override val eventId: Identifier`, and `override var priority: Int`. The scheduler must inject **synthetic** values when building the throwaway `ScreenCondition` it hands the engine per poll:

- **`id`** — `Identifier(databaseId = 0L, tempId = <stable hash of the cloud scenario UUID>)`, i.e. a *temporary* `Identifier` (`databaseId = DATABASE_ID_INSERTION`, so `isInDatabase()` is false). It is never persisted to the local DB; it exists only for the duration of one detection call. The `tempId` is derived deterministically from `TrackingScenario.id` so logs/debugging can correlate a synthetic condition back to its cloud scenario.
- **`eventId`** — a fixed sentinel temporary `Identifier` (e.g. `Identifier(databaseId = 0L, tempId = TRACKING_SYNTHETIC_EVENT_ID)`); tracking has no `Event`, and the engine's `ConditionsVerifier` does not require a real event row to run a single `verify*` call.
- **`priority`** — `0`. Tracking verifies exactly one condition per poll, so ordering among conditions is moot.

The mapping per read type (reusing the type from `/Users/Sophia/Documents/GitHub/AutoClicker/core/smart/domain/.../condition/ScreenCondition.kt`):

- **Number** → `ScreenCondition.Number(id, eventId, name, threshold, shouldBeDetected = true, priority = 0, detectionArea, comparisonOperation = <ignored>, counterValue = <ignored>)`. The legacy `comparisonOperation`/`counterValue` fields drive auto-clicker fulfillment and are **not used** for tracking; the scheduler passes innocuous defaults. A tracking Number read "fulfills" whenever a number is recognized.
- **Text** → `ScreenCondition.Text(id, eventId, name, threshold, shouldBeDetected, priority = 0, text = matchText ?: "", detectionArea, alphabet)`. (Blocked on the capture-section JNI work for `value` surfacing, per the TEXT caveat above.)
- **State** → `ScreenCondition.Color(id, eventId, name, threshold, shouldBeDetected, priority = 0, color = color!!, detectionArea)`.

The `detectionArea` is a `Rect` in **device screen-space**; `ScalingManager.scaleUpDetectionResult()` already maps detection-space results back to screen-space, so the scenario stores screen-space coordinates and the engine handles the rest. The `alphabet` is an `OCRAlphabet` enum value (`ARABIC`, `CHINESE_SIMPLIFIED`, `CHINESE_TRADITIONAL`, `CYRILLIC`, `DEVANAGARI`, `JAPANESE`, `KANNADA`, `KOREAN`, `LATIN`, `TAMIL`, `TELUGU`). Number reads ignore the alphabet field and use the first-loaded recognition model (a documented out-of-scope limitation — `matchNumber` uses `defaultRecognitionModelId`).

`pollIntervalMs` is validated `>= 5000` at authoring time in the cloud and re-validated on the device before scheduling.

#### Tracking authority (three-flag drift — resolution)

There are intentionally **three** `tracking` booleans in the system: `devices.tracking`, `scenario_assignments.tracking`, and `TrackingScenario.tracking` (the device-side cached projection of the assignment). They can disagree transiently. The authoritative one is **`scenario_assignments.tracking`** — it is *per-device, per-scenario* and is exactly what the scenario-pull endpoint projects into `TrackingScenario.tracking`. `devices.tracking` is a coarse device-level convenience flag (is this device tracking *anything*?) used only by the dashboard summary and is derived, never authoritative. The command-pull and scenario-pull endpoints both read and write `scenario_assignments.tracking`; the device treats the value returned by the most recent successful pull/command as current.

### Wire JSON (Trax Cloud `/v1`)

All bodies are JSON, `Content-Type: application/json`. Device requests authenticate with the device-scoped bearer token; dashboard requests with the Account token. Timestamps are ISO-8601 UTC strings (`ISO_INSTANT`, millisecond precision).

**Enrollment** — `POST /v1/devices:enroll` (unauthenticated except the pairing code):
```json
// request
{ "pairingCode": "7K2-9QX", "model": "Pixel 7", "osVersion": "Android 14", "appVersion": "4.0.0-beta02" }
// response
{ "deviceId": "d-9f3c…", "tenantId": "t-1a2b…", "deviceToken": "eyJ…", "tokenExpiresAt": "2026-12-29T00:00:00.000Z" }
```
The pairing code is short-lived and single-use; invalid/expired codes return `410 Gone` with `{ "error": "pairing_code_expired" }` so the UI can message clearly (user story 1).

**Observation batch upload** — `POST /v1/observations:batch` (device token):
```json
// request
{ "observations": [
  { "id": "o-uuid-1", "scenarioId": "s-uuid",
    "deviceCapturedAt": "2026-06-29T14:03:11.482Z",
    "value": "1543.0", "valueType": "NUMBER", "confidence": 91, "isFulfilled": true,
    "hasCrop": true },
  { "id": "o-uuid-2", "scenarioId": "s-uuid",
    "deviceCapturedAt": "2026-06-29T14:03:21.500Z",
    "value": null, "valueType": "NUMBER", "confidence": 12, "isFulfilled": false,
    "hasCrop": false }
] }
// response — per-row idempotent outcome
{ "results": [
  { "id": "o-uuid-1", "status": "accepted", "serverReceivedAt": "2026-06-29T14:05:02.001Z",
    "cropUploadUrl": "https://…signed-put…" },
  { "id": "o-uuid-2", "status": "duplicate" }
] }
```
`tenantId`/`deviceId` are **not** in the upload body — the server derives them from the device token (this is what enforces "device tokens write only their own Observations"). The `id` doubles as the dedup key against the `UNIQUE (tenant_id, id)` constraint. `hasCrop=true` yields a signed `cropUploadUrl` the client PUTs the PNG to; the cloud fills `crop_url` after the PUT succeeds. `status` is `accepted` or `duplicate`; both let the client mark the row `SYNCED`.

**Scenario pull** — `GET /v1/devices/{deviceId}/scenarios` (device token) returns the assigned scenarios with current `tracking` state (projected from `scenario_assignments.tracking`):
```json
{ "scenarios": [
  { "id": "s-uuid", "name": "Account A gold", "readType": "NUMBER",
    "detectionArea": { "left": 220, "top": 880, "right": 460, "bottom": 940 },
    "alphabet": "LATIN", "matchText": null, "color": null,
    "threshold": 80, "pollIntervalMs": 10000, "cropCaptureEnabled": true, "tracking": true } ] }
```

**Command pull** — `GET /v1/devices/{deviceId}/commands` (device token) drains the remote start/stop queue:
```json
{ "commands": [
  { "id": "c-uuid", "type": "START", "scenarioId": "s-uuid", "expiresAt": "2026-06-29T15:00:00.000Z" } ] }
```
`type` is `START` | `STOP`. Commands are idempotent (re-applying START to an already-tracking scenario is a no-op) and **expiring** — a command past `expiresAt` is discarded unapplied, so a device that was offline for hours doesn't replay stale toggles. Applying a command flips `scenario_assignments.tracking` (the authoritative flag). The device ACKs via `POST /v1/devices/{deviceId}/commands:ack { "ids": ["c-uuid"] }`.

### Cloud DB schema (Trax Cloud, DDL-ish)

PostgreSQL-flavored. Every tenant-scoped table carries `tenant_id` and every query is filtered by it (row-level tenant isolation — the gate "Tenant A cannot touch Tenant B's data").

```sql
CREATE TABLE accounts (
  id          uuid PRIMARY KEY,
  email       text NOT NULL UNIQUE,
  created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE tenants (
  id          uuid PRIMARY KEY,
  owner_account_id uuid NOT NULL REFERENCES accounts(id),
  created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE devices (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL REFERENCES tenants(id),
  model       text,
  os_version  text,
  app_version text,
  last_seen_at timestamptz,                 -- drives online/offline in dashboard
  tracking    boolean NOT NULL DEFAULT false, -- coarse, derived; NOT authoritative (see tracking authority)
  enrolled_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE tracking_scenarios (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL REFERENCES tenants(id),
  name        text NOT NULL,
  read_type   text NOT NULL CHECK (read_type IN ('NUMBER','TEXT','STATE')),
  area_left   int NOT NULL, area_top int NOT NULL,
  area_right  int NOT NULL, area_bottom int NOT NULL,   -- Rect, device screen-space
  alphabet    text NOT NULL DEFAULT 'LATIN',
  match_text  text,
  color       int,
  threshold   int NOT NULL,
  poll_interval_ms bigint NOT NULL CHECK (poll_interval_ms >= 5000),
  crop_capture_enabled boolean NOT NULL DEFAULT false,
  created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE scenario_assignments (
  scenario_id uuid NOT NULL REFERENCES tracking_scenarios(id),
  device_id   uuid NOT NULL REFERENCES devices(id),
  tracking    boolean NOT NULL DEFAULT false,  -- AUTHORITATIVE per-device tracking state
  PRIMARY KEY (scenario_id, device_id)
);

CREATE TABLE observations (
  id              uuid PRIMARY KEY,           -- adopted from client; also the dedup key
  tenant_id       uuid NOT NULL REFERENCES tenants(id),
  device_id       uuid NOT NULL REFERENCES devices(id),
  scenario_id     uuid NOT NULL REFERENCES tracking_scenarios(id),
  device_captured_at timestamptz NOT NULL,
  server_received_at timestamptz NOT NULL DEFAULT now(),
  value           text,                       -- null when not fulfilled
  value_type      text NOT NULL CHECK (value_type IN ('NUMBER','TEXT','STATE')),
  value_number    double precision,           -- materialized numeric mirror; see invariant below
  confidence      smallint NOT NULL CHECK (confidence BETWEEN 0 AND 100),
  is_fulfilled    boolean NOT NULL,
  crop_url        text,
  CONSTRAINT uq_obs_idem UNIQUE (tenant_id, id),
  -- value_number is populated EXACTLY when this is a fulfilled NUMBER read, and null otherwise.
  CONSTRAINT ck_obs_value_number CHECK (
    (value_type = 'NUMBER' AND is_fulfilled AND value_number IS NOT NULL)
    OR (value_number IS NULL)
  )
);

CREATE INDEX idx_obs_series ON observations (tenant_id, scenario_id, device_id, device_captured_at);

CREATE TABLE device_commands (
  id          uuid PRIMARY KEY,
  tenant_id   uuid NOT NULL REFERENCES tenants(id),
  device_id   uuid NOT NULL REFERENCES devices(id),
  scenario_id uuid REFERENCES tracking_scenarios(id),
  type        text NOT NULL CHECK (type IN ('START','STOP')),
  created_at  timestamptz NOT NULL DEFAULT now(),
  expires_at  timestamptz NOT NULL,
  acked_at    timestamptz
);
```

Two cloud-only fields warrant explanation. `observations.value_number` is a **materialized numeric mirror** of `value`, populated on ingest only when `value_type = 'NUMBER' AND is_fulfilled`; the dashboard time-series query reads it directly (`idx_obs_series` covers the filter, then `value_number` is selected) so it never parses text per-row. The canonical truth remains `value` (text) — `value_number` is a query-performance derivative, not a second source of truth, and the `ck_obs_value_number` CHECK keeps it honest: it is non-null **exactly** for fulfilled NUMBER rows and null for everything else (failed NUMBER reads, TEXT, STATE), so the two columns can never silently disagree. `devices.last_seen_at` is touched on every authenticated device request and is what the dashboard's online/offline indicator reads; it has no on-device counterpart.

The `UNIQUE (tenant_id, id)` constraint is the linchpin of duplicate-free ingest and is scoped to the tenant (not global) so two tenants could theoretically mint the same UUID without collision — astronomically unlikely with UUIDv4, but the scoping keeps tenant isolation absolute even at the constraint level.

### Cross-tier id mapping summary

| Entity | On-device id | Wire id | Cloud PK | Minted by |
|---|---|---|---|---|
| Observation | `String` UUID (`ObservationEntity.id`) | `id` (== dedup key) | `uuid` (adopted) | device, at capture |
| TrackingScenario | cached `String` UUID | `id` | `uuid` | cloud, at authoring |
| Device | cached `String` UUID | `deviceId` | `uuid` | cloud, at enrollment |
| Tenant | cached `String` UUID | `tenantId` | `uuid` | cloud |
| Legacy local Scenario/Condition | `Long` via `Identifier` | n/a (not synced) | n/a | Room autoincrement |
| Synthetic tracking `ScreenCondition` | temporary `Identifier` (`databaseId = 0L`, derived `tempId`) | n/a (never persisted/synced) | n/a | scheduler, per poll |

This table is the quick-reference other sections cite when they need to know "which id do I have here?" The dividing line is crisp: legacy auto-clicker data stays `Long`/`Identifier` and never crosses the network; everything in the TraxIntel tracking path is a `String` UUID, client-minted for Observations and server-minted for Scenarios/Devices/Tenants. The throwaway `ScreenCondition` the engine consumes per poll carries a *temporary* `Identifier` that is never written to either store.

---

## Risks, open questions & decision log

This section is the honest counterweight to the rest of the blueprint. The MVP scope contract commits us to a read-only screen-intelligence platform built on the existing Smart-AutoClicker detection pipeline, and most of that machinery already exists and works. But "reuse the pipeline" hides several sharp edges: the capture stack depends on MediaProjection and an AccessibilityService that must survive headless background polling; the detection pipeline computes a match boolean and *discards* the recognized value before it reaches any listener, so even a Number observation needs new plumbing; the OCR text string never crosses the JNI boundary at all; and the entire connected build has to be partitioned from a GPLv3 codebase without contaminating the F-Droid/Play distributions. Below is a risk register scored on likelihood (L) and impact (I), each Low/Med/High, with concrete mitigations tied to real files. Then a decision log capturing why the MVP is shaped the way it is and what we explicitly considered and rejected. Finally, the open questions that only the product owner can close.

### Technical risk register

#### R1 — Headless MediaProjection / capture session limits (L: High, I: High)

The whole product is "read a region on a schedule." Capture today runs through `DetectorEngine` (`core/smart/processing/.../data/DetectorEngine.kt`), which acquires frames in `processScreenImages()` via the `DisplayRecorder` and feeds `scenarioProcessor.process(screenFrame)`. That recorder sits on top of `MediaProjection`, and the foreground service is declared `android:foregroundServiceType="mediaProjection"` in `smartautoclicker/src/main/AndroidManifest.xml` (verified, alongside `FOREGROUND_SERVICE_MEDIA_PROJECTION`).

The risk is threefold. First, **MediaProjection requires a user grant per session** — there is no fully unattended, reboot-surviving, "headless" projection on stock Android. On Android 14+ the system can re-prompt and the projection can be torn down by the OS; our "remote start within one poll cycle" acceptance gate (Story 7) assumes capture can be re-established without a human at the device. Second, our scheduling model in the contract is *fixed-interval polling (min 5s)* via a new `core:scheduling` WorkManager wrapper around `SmartProcessingRepositoryImpl.startDetection` with auto-stop. If we tear the projection down between polls to save battery, every poll re-prompts; if we keep it up continuously, we hold a long-lived capture session and the "schedule" is really "run continuously and sample." Third, kiosk/appliance fleets (Secondary Use Case 2) are exactly the deployments where re-granting projection by hand is impractical.

Mitigations:
- **Keep the projection session alive for the lifetime of a tracking session rather than per-poll.** The `core:scheduling` scheduler should *not* start/stop MediaProjection each interval; it should gate the *processing* of frames. Concretely, keep `DetectorEngine` running (projection up) and let the scheduler decide which frames to actually evaluate, or use `startDetection(..., autoStopDuration)` for bounded windows and re-arm. This makes "5s interval" mean "sample one frame every 5s from a live session," which is what the contract's acceptance test (`~one Observation/10s`) actually measures.
- **For unattended fleets, document a device-prep step**: enrollment (Story 1) should capture the one-time MediaProjection grant and the AccessibilityService enablement as part of pairing, and we rely on the OEM/MDM allowing persistent projection. This is a *deployment constraint*, not something the app can fully solve.
- **Treat projection loss as a first-class state using the hook the codebase already provides.** `SmartProcessingRepositoryImpl` exposes `setProjectionErrorHandler { ... }` (`SmartProcessingRepositoryImpl.kt:141`), and that handler is invoked on the main scope (`SmartProcessingRepositoryImpl.kt:152`) when `DetectorEngine`'s screen-record `onError` fires. We wire the tracking session's error handler to emit an Observation with `isFulfilled=false` and a low/zero confidence value (Story 3/4). A torn-down projection therefore becomes an observable, syncable signal in the dashboard rather than a silent stall — this is concrete plumbing, not an aspiration.

Residual risk stays High: there is no API to make MediaProjection truly unattended on stock Android. This is the single largest threat to the fleet/kiosk use cases and must be flagged to the product owner (see Open Questions).

#### R2 — OCR accuracy variance and the text-value JNI extension (L: High, I: Med)

The detection engine's number/text reads go through `ConditionsVerifier` (`verifyNumberCondition`, `verifyTextCondition`) into `ImageDetector.detectNumber()` / `detectText()`, which ultimately run the ncnn OCR models in native C++ (`smartautoclicker.cpp`, `text_recognizer.cpp`, `text_matcher.cpp`). Two distinct risks live here.

**Accuracy variance.** OCR confidence depends on font, contrast, anti-aliasing, scaling, and alphabet. The pipeline scales the detection area by `detectionQuality` (`ScalingManager.startScaling()`), so a region marked at one quality, or read on a device with a different screen density than where the scenario was authored, can degrade recognition (the cross-device resolution case is significant enough that it is broken out separately as R8). The contract acknowledges OCR fallibility by making `confidence` (0–100) a first-class field on every Observation and requiring the dashboard to *distinguish low-confidence points* (Story 6). That is the correct product-level mitigation: we do not pretend OCR is exact; we surface confidence and let the operator judge. We should additionally:
- Be precise about which alphabet path is configurable. **Text reads already select their alphabet per-condition**: each `ScreenCondition.Text` carries its own `alphabet: OCRAlphabet`, and `verifyTextCondition` passes `condition.alphabet.name` as `recognitionModelId` (`ConditionsVerifier.kt:241`). Text alphabet selection is therefore an existing, working, in-scope feature — *no* build work is needed to choose the Text recognizer. **Only `detectNumber` is pinned to the first-loaded recognition model** (`text_matcher.hpp` `defaultRecognitionModelId`; `matchNumber` ignores any per-condition alphabet). So the "wrong model, garbage value" risk applies specifically to the Number path, and only there is the model choice a product/default question rather than a per-condition setting.
- Persist the cropped PNG (`BitmapRepository` with the `Observation_` prefix) when `cropCaptureEnabled` so a human can audit any suspicious value against the actual pixels (Story 4, crop viewer in Story 6).

**The JNI marshalling change for Text.** This is the most invasive *native* change in the MVP, and the codebase is less ready for it than a casual reading suggests. Today `detectTextNative` returns a 7-element `DoubleArray` marshalled by `toJniResult()` in `jni_detection_result.cpp` — `[detected, centerX, centerY, width, height, confidence, numberDetected]`. The recognized *string* exists only transiently: it is a local variable returned from the OCR recognizer (`text_recognizer.cpp:181`, `return {boundingBox, recognizedText, confidence};`), consumed by `TextMatcher` for fuzzy matching, and then discarded. **There is no `Detector::lastRecognizedText()` method and no per-instance storage of the recognized string anywhere in the C++ today** — both would have to be newly written on the C++ `Detector`/`TextMatcher` classes (capture the `TextRecognizerResult.text` during `matchText` and stash it on the detector instance so a subsequent accessor can read it). Risk: JNI string lifetime/encoding bugs (UTF-8 vs Java modified-UTF-8, local-ref leaks, null returns) are easy to get wrong and crash natively rather than throwing.

Mitigation — prefer the lowest-blast-radius option of the three the codebase notes suggest. Rather than changing the return type to `jobject`, keep `detectTextNative` returning the numeric array and add a *separate, newly written* string accessor that reads back the recognized text captured during the same detection call. The sketch below is **illustrative of net-new C++ that must be authored** — `getDetectorFromJavaRef` is the real existing pointer helper (`jni_detector.cpp:94`), but `storeLastRecognizedText`/`lastRecognizedText` are *new* members we add to the C++ `Detector` (and the capture call inside `TextMatcher::matchText`):

```cpp
// jni/jni.hpp (existing) — real helper:
//   Detector* getDetectorFromJavaRef(JNIEnv *env, jobject self);
//
// NEW C++ to add: Detector must gain a std::string member set during
// matchText() (e.g. detector->storeLastRecognizedText(result.text);)
// and a getter lastRecognizedText(). Neither exists yet.

// smartautoclicker.cpp — NEW JNI export reading the newly-stored string
JNIEXPORT jstring JNICALL
Java_..._NativeDetector_getLastRecognizedTextNative(JNIEnv *env, jobject self) {
    auto detector = getDetectorFromJavaRef(env, self);            // existing helper
    const std::string &t = detector ? detector->lastRecognizedText() // NEW member
                                     : std::string();
    return env->NewStringUTF(t.c_str()); // UTF-8; see non-Latin caveat below
}
```

```kotlin
// DetectionResult.kt — extend the Kotlin model, keep existing fields intact
data class DetectionResult(
    val isDetected: Boolean = false,
    val confidenceRate: Double = 0.0,
    val position: Point = Point(),
    val size: Point = Point(),
    val numberDetected: Double? = null,
    val recognizedText: String? = null,   // NEW, populated for detectText()
)
```

This keeps the proven 7-element array untouched (no risk to the legacy auto-clicker's Image/Color/Number paths), confines the change to the text path, and lets `ConditionsVerifier.verifyTextCondition` read `recognizedText`. Note `NewStringUTF` uses modified UTF-8; for CJK/Arabic alphabets we should validate round-tripping and, if needed, marshal a UTF-16 jstring via a byte array. Residual risk Med, mostly around non-Latin alphabets, which the dashboard can still display as the raw recognized string.

> **Critical dependency — see R2b.** Surfacing `recognizedText` (and `numberDetected`) on `DetectionResult` is *necessary but not sufficient*: neither value currently survives `ConditionsVerifier`'s construction of `ProcessedConditionResult.Screen`, which is the object actually delivered to the observation listener. The mandatory Kotlin-side change is its own risk, R2b.

#### R2b — `ProcessedConditionResult.Screen` carries no value field (L: Med, I: High)

This is the single most understated end-to-end gap in the original blueprint, and it affects **Number observations too, not just Text**. The object delivered to `SmartProcessingListener.onScreenConditionProcessingCompleted` is `ProcessedConditionResult.Screen`, and its fields are exactly `isFulfilled, haveBeenDetected, condition, confidenceRate, position, size` — there is **no value field of any kind** (verified, `ProcessedConditionResult.kt:37-44`). Worse, `verifyNumberCondition` already reads `detectionResult.numberDetected` (`ConditionsVerifier.kt:201`) but uses it *only* to compute the comparison and then throws it away — the `Screen` result it builds at `ConditionsVerifier.kt:218-225` never copies the number in. `verifyTextCondition` (`ConditionsVerifier.kt:246-253`) likewise builds a `Screen` with no text. So even though the codebase *already* surfaces `numberDetected: Double?` to Kotlin, that value cannot reach the dashboard today.

The consequence: **any value-bearing observation — Number included — is blocked until `ProcessedConditionResult.Screen` gains value fields and both verify methods populate them.** This is mandatory work for the MVP's core value proposition, independent of the Text/JNI work in R2.

Mitigation:
- Add nullable value fields to `ProcessedConditionResult.Screen`, e.g. `val numberDetected: Double? = null` and `val recognizedText: String? = null`. Defaulting to null keeps every other producer of `Screen` (Image at `~175-182`, Color at `~140-147`) compiling unchanged.
- Populate `numberDetected` in `verifyNumberCondition` (copy the value already read at `ConditionsVerifier.kt:201` into the `Screen` built at `218-225`).
- Populate `recognizedText` in `verifyTextCondition` (from the `DetectionResult.recognizedText` delivered by R2) at `246-253`.
- The observation listener then reads these straight off the `Screen` result; no further pipeline change is needed.

Residual risk is Low for the Number path (a pure additive Kotlin change, no native work) and Med for the Text path (gated on the R2 native work). It is rated I: High because without it the product ships no values at all. This is also a decision (D3b) because it deliberately widens a shared domain type used by the legacy auto-clicker — mitigated by nullable defaults.

#### R3 — Play Store rejection (L: High, I: High)

This codebase is a screen-reading AccessibilityService that uses MediaProjection and `SYSTEM_ALERT_WINDOW` overlays — exactly the permission cluster Google scrutinizes hardest. `accessibilityservice.xml` declares `canRetrieveWindowContent=true`, `canPerformGestures=true`, and `canRequestFilterKeyEvents=true` (all three verified true). The capabilities relevant to the *screen-content-reading* policy story are `canRetrieveWindowContent` (reads the UI tree) together with MediaProjection capture; `canPerformGestures` is a separate gesture-injection capability and `canRequestFilterKeyEvents` is a separate key-event-filtering capability — both are independently scrutinized but neither is what makes the "read other apps' screens and ship values to a cloud backend" story hard. Google's accessibility policy requires that the *advertised* function genuinely serve accessibility, and a cloud screen-reader is a hard story to tell on Play regardless.

Mitigations:
- **Lean into the flavor split.** The contract already separates the connected `cloud` flavor from `fDroid`/`playStore` via the new `KlickrDimension.CONNECTIVITY` dimension. The riskiest distribution — connected screen-reading + cloud upload — does not have to ride on the Play listing at all. The `playStoreCloud*` matrix entry is *possible* but optional; `playStoreLocalRelease` (current behavior, no network) preserves the existing compliant listing.
- For the tracking mode, `canPerformGestures` is unused (MVP is read-only). We should consider a build/runtime that does not advertise gesture injection in the connected flavor, narrowing the accessibility surface to what we actually use (window content + capture). Note this is a genuine *narrowing* of an unrelated capability, not the core Play argument.
- Provide a clear in-app disclosure and privacy policy covering screen capture and upload, per Play's prominent-disclosure requirement.

Residual risk High for Play specifically; the realistic mitigation is that the fleet/cloud product distributes outside Play (see Open Question Q2). This must be a product-owner decision, not an engineering assumption.

#### R4 — Battery, foreground-service, and WorkManager-bootstrap constraints (L: Med, I: Med)

A live MediaProjection session plus continuous frame acquisition in `processScreenImages()` (with FPS limiting via `minProcessingDurationNs`) is power-hungry, and the contract permits intervals as tight as 5s across a fleet of always-on devices. Android's foreground-service and Doze/standby restrictions, plus the newly introduced WorkManager scheduler, interact awkwardly. The codebase fact is explicit: **no WorkManager exists today** (no `androidx.work` dependency, no `hilt-work`) — it is a net-new dependency *and* a net-new application bootstrap. WorkManager's minimum periodic interval is also 15 minutes, which is *coarser* than our 5s requirement.

Mitigations:
- **Do not use WorkManager periodic work for the 5s poll loop.** WorkManager is the wrong tool for sub-15-minute cadence. Use it for *coarse, reliable* jobs — sync flush, command pull, re-arming a tracking session after reboot — and drive the actual 5s sampling from inside the already-running foreground service (the live `DetectorEngine` session sampling frames). This re-reads the `core:scheduling` responsibility: WorkManager guarantees the session is *running*; an in-service timer/coroutine governs the *sample cadence*. The blueprint's scheduling section should reconcile this explicitly.
- **Budget the WorkManager + Hilt bootstrap as its own (small) integration risk.** Even the coarse jobs require a net-new setup currently absent from the app: add `androidx.work:work-runtime-ktx` and `androidx.hilt:hilt-work` to `libs.versions.toml`, introduce a `HiltWorkerFactory`, and wire `WorkManager.initialize(context, Configuration.Builder().setWorkerFactory(...).build())` into `SmartAutoClickerApplication.onCreate()` (the app currently does no such initialization). This is straightforward but invisible in the original register and should be scheduled, not assumed.
- Reuse the existing foreground-service lifecycle in `LocalService`/`SmartAutoClickerService` (`startForegroundMediaProjectionServiceCompat`) and its notification, rather than spinning a second foreground service.
- Make crop capture (`cropCaptureEnabled`) and interval owner-tunable so power-sensitive fleets can widen the interval; the dashboard already owns scenario authoring.

#### R5 — GPLv3 boundary mistakes (L: Med, I: High)

Every source file carries the GPLv3 header (verified in `DatabaseInfo.kt` / `ProcessedConditionResult.kt`: "GNU General Public License ... version 3 ... or (at your option) any later version"). The connected build links new `core:network` and `feature:cloud` code and talks to a proprietary backend. The risk is a license violation if proprietary/network code is statically combined into the GPLv3 binary without the whole work being offered under GPLv3, or if we accidentally pull a GPL-incompatible dependency.

Mitigations:
- **The flavor gating is the license firewall.** Network dependencies are added only via the `cloudImplementation` configuration (mirroring the proven `playStoreImplementation` pattern in `DependencyHandlerScopeExt.kt` and `CrashlyticsConventionPlugin.kt`). The `fDroid`/`local` builds must compile and run with *no network module linked*.
- **Make the no-network-classes check a hard acceptance gate, not a CI aside.** The contract should require: *the CI pipeline builds `fDroidLocalRelease` and asserts the `core:network`/`feature:cloud` classes are absent from the artifact; the build fails if they are present.* This is a genuinely testable firewall and is strong enough to be a gate in its own right rather than a "CI must" footnote.
- The connected client, being derived from the GPLv3 work, **is itself GPLv3** — that is fine and intended. The *backend* (Trax Cloud) is a separate program communicating over a network boundary (`/v1` REST), which under GPLv3 is not a derivative work. We must keep the boundary a network protocol, not a linked library, and ensure no GPLv3 client code is copied server-side.
- Vet every new dependency in `libs.versions.toml` for GPL compatibility (Retrofit/OkHttp/kotlinx-serialization are permissive — fine).

#### R6 — Migration 21 → 22 and append-only integrity (L: Low, I: Med)

The new `Observation` Room entity requires bumping `DATABASE_VERSION` from 21 to 22 (`DatabaseInfo.kt`, verified at 21). Adding a brand-new table with no transform of existing rows is the *simplest* migration class. This is demonstrable, not merely asserted: the `autoMigrations` list in `ClickDatabase.kt` already contains a contiguous recent run including `AutoMigration(from=20, to=21)` (verified, `ClickDatabase.kt:58`). Because 20→21 is itself an AutoMigration of the add-schema variety, a 21→22 AutoMigration that only adds a table slots in cleanly behind it. (Note that several *older* steps — e.g. 12→13, 19→20 — are manual `Migration(from,to)` transforms; those are irrelevant here because we add, not alter.)

The acceptance gate only requires existing scenarios/conditions survive intact, and the risk is low precisely because we add, not alter. Mitigation and the full wiring checklist (not just the migration line):
- Create the `@Entity @Serializable Observation` data class with `@PrimaryKey(autoGenerate=true)` and a `scenarioId` `ForeignKey(... onDelete=CASCADE)`.
- **Add `Observation::class` to the `@Database(entities = [...])` list in `ClickDatabase.kt`** — Room will not generate the table otherwise.
- **Add a new `ObservationDao` and expose it as an abstract `val observationDao: ObservationDao` on `ClickDatabase`.**
- Add `AutoMigration(from=21, to=22)` to the `autoMigrations` list and bump `DATABASE_VERSION` to 22; no manual `SupportSQLiteDatabase` transform is needed.
- Index `Observation` by `scenarioId` and `deviceCapturedAt` for the sync-queue query.
- Keep the table append-only at the DAO level (insert + read; no update/delete except post-sync pruning).

#### R7 — Offline sync idempotency, duplicate observations, and prune-vs-ack ordering (L: Med, I: Med)

The contract demands zero server-side duplicates across offline batch upload (Story 5, acceptance gate). The risk is the classic at-least-once delivery problem: client retries a batch the server already persisted. Mitigation is already specified — client-generated `idempotencyKey` per Observation; the server upserts on that key. We reuse the proven streaming/progress patterns from `BackupEngine`/`BackupRepository` (channelFlow, `BackupProgress` callbacks) for the upload pipeline.

A second, distinct correctness gap follows directly from D7's append-only-with-post-sync-pruning model and is left open by the original draft: **the client must not prune a locally-stored Observation until it holds a confirmed server ack for that record's `idempotencyKey`.** If the client prunes optimistically and the server actually rejected or lost the write (network partition, 5xx after partial commit, ambiguous timeout), the observation is gone from both sides — a silent data-loss bug that idempotency on the *server* cannot fix because the client never retries. Mitigation:
- Gate the DAO prune strictly on a per-record confirmed-ack signal (e.g. the server returns the set of persisted `idempotencyKey`s; the client prunes only that set).
- Treat ambiguous/timeout responses as "not acked" — keep the row, rely on server upsert to dedupe on the inevitable retry.
- This makes the two halves compose: server upsert handles *duplicate delivery*; ack-gated pruning handles *lost delivery*. Residual risk Med, and now explicitly contingent on both the server honoring idempotency under concurrent device writes *and* the client honoring ack-before-prune ordering.

#### R8 — Authoring-density vs runtime-density mismatch across heterogeneous fleets (L: Med, I: Med)

For Secondary Use Case 2 (kiosk/appliance fleets), one scenario is authored once on a developer/reference device and then run on *many* devices that may differ in screen resolution and density. This is a first-order accuracy risk, not a footnote to R2. Each `ScreenCondition` (Color/Image/Number/Text) carries its `detectionArea: Rect` in **absolute coordinates**, and the scaling path (`ScalingManager.startScaling()`) maps *detection-quality* to the *current* display size — it does **not** reconcile the author-device resolution against a different target-device resolution. A region authored at the author device's pixel rectangle can therefore land on the wrong pixels (or partially off-screen) on a device with a different resolution, silently degrading or invalidating every read in that region. The backup format already hints at this class of problem: `ScenarioBackup` stores `screenWidth`/`screenHeight` and the smart backup data source raises a `screenCompatWarning` on dimension mismatch — but that is an import-time warning, not a runtime coordinate transform.

Mitigations / posture:
- At minimum, surface the mismatch: when a device's display dimensions differ from the scenario's authoring dimensions, mark observations from that device as suspect (low confidence / a distinct flag) so the dashboard can segregate them, reusing the same `screenWidth`/`screenHeight` metadata the backup path already carries.
- Document a deployment constraint: author scenarios on a device matching the fleet's resolution class, or maintain one scenario variant per resolution class.
- A true resolution-normalizing transform (map author-rect → target-rect proportionally) is *out of MVP scope* and is the subject of Open Question Q6; doing it correctly requires product input on whether fleets are homogeneous enough to skip it.

Residual risk Med — bounded for homogeneous fleets, sharp for heterogeneous ones, and only partially observable without the Q6 decision.

### Decision log

**D1 — MVP is read-only ("capture, don't act").** *Decision:* exclude all action/gesture execution; do not invoke `ActionExecutor` in tracking mode. *Rationale:* the value proposition is fleet-wide observability of a value over time, and read-only dramatically shrinks the risk surface (no gesture-injection liability, simpler Play/accessibility story, no destructive action across a fleet). *Alternatives considered:* (a) full automation — rejected as scope explosion and far higher policy/safety risk; (b) read + single "tap to refresh" action — rejected because it reintroduces the action engine into the critical path for marginal benefit. *Reversibility:* high — the action engine remains in the codebase and can be layered on post-MVP.

**D2 — Reuse the existing detection pipeline rather than build a new capture stack.** *Decision:* capture Observations at `SmartProcessingListener.onScreenConditionProcessingCompleted`, reusing `ScreenCondition.Number/Text/Color` and `ConditionsVerifier`. *Rationale:* the frame-acquisition, scaling (`ScalingManager`), and OCR machinery is battle-tested; rebuilding it would be the bulk of the effort for no differentiation. *Alternatives:* a from-scratch capture/OCR module — rejected (cost, duplicated bugs). *Trade-off accepted:* we inherit the pipeline's coupling to MediaProjection and the AccessibilityService lifecycle (see R1), and we inherit a result object that does **not** yet carry the extracted value, forcing the D3b change below. The original framing that "the extracted value reaches the observation via the existing result object" was wrong: `ProcessedConditionResult.Screen` has no value field today.

**D3 — Surface OCR text via a targeted JNI extension, not a result-type rewrite.** *Decision:* add `recognizedText` alongside the existing 7-element `DoubleArray` rather than changing `toJniResult` to return a `jobject`; capture the recognizer's string on the C++ `Detector` instance and read it back via a new accessor. *Rationale:* confines blast radius to the text path; the Image/Color/Number marshalling — used by the legacy auto-clicker — stays byte-identical. *Reality check:* this is net-new C++ — there is no existing per-instance text store and no `lastRecognizedText()`; the string currently lives only as the transient `TextRecognizerResult.text` return value (`text_recognizer.cpp:181`). The existing helper we reuse is `getDetectorFromJavaRef` (`jni_detector.cpp:94`). *Alternatives:* (a) `jobject` wrapper with text+numeric fields — rejected as it perturbs all four `verify*` paths; (b) 8th array element encoding a string handle — rejected as fragile. (See R2 for the corrected sketch.)

**D3b — Extend `ProcessedConditionResult.Screen` with nullable value fields and populate them in both verify methods.** *Decision:* add `numberDetected: Double? = null` and `recognizedText: String? = null` to `ProcessedConditionResult.Screen`, populate `numberDetected` in `verifyNumberCondition` (the value is already read at `ConditionsVerifier.kt:201` but discarded) and `recognizedText` in `verifyTextCondition`. *Rationale:* this is the *mandatory* link that makes any value-bearing observation reach the listener — Number included; without it the product surfaces no values at all. It is lower-risk than the native work (pure additive Kotlin), and nullable defaults keep the legacy Image/Color producers compiling unchanged. *Alternatives:* (a) a parallel side-channel keyed by condition id from `ConditionsVerifier` to the listener — rejected as it duplicates state the `Screen` result should already own; (b) re-deriving the value in the listener by re-running detection — rejected (double work, racy). *Trade-off accepted:* we widen a domain type shared with the legacy auto-clicker, mitigated by null defaults.

**D4 — New flavor *dimension* (CONNECTIVITY: LOCAL/CLOUD) rather than a build type or a fork.** *Decision:* add `KlickrDimension.CONNECTIVITY` composing with the existing `VERSION` dimension. *Rationale:* it is the established, license-safe mechanism (mirrors `playStoreImplementation` gating) and yields the exact matrix the contract needs (`fDroidLocalRelease`, `playStoreCloudRelease`). *Alternatives:* (a) a separate Git fork for the cloud build — rejected (divergence, double maintenance); (b) a runtime feature flag with network deps always linked — rejected (breaks the GPLv3 firewall in R5). *Constraint locked:* network deps enter *only* via `cloudImplementation`, and CI hard-fails if network classes appear in `fDroidLocalRelease` (R5).

**D5 — WorkManager for coarse reliability, in-service timer for the 5s cadence.** *Decision:* introduce WorkManager (net-new dependency *and* net-new `HiltWorkerFactory` + `WorkManager.initialize` bootstrap in `SmartAutoClickerApplication`) for sync flush / command pull / session re-arm, but drive sub-15-minute sampling from the live foreground service. *Rationale:* WorkManager's 15-minute periodic floor cannot meet the 5s contract minimum; conflating the two would either violate the interval or abuse WorkManager. *Alternatives:* pure-WorkManager polling — rejected (interval floor); pure in-service `AlarmManager` — viable but loses WorkManager's reboot/constraint guarantees for the coarse jobs. *Risk:* the scheduling section must restate this split clearly, and the bootstrap is itself a small net-new integration (see R4).

**D6 — Package names stay `com.buzbuz.smartautoclicker.*`.** *Decision:* TraxIntel is a brand rename only; `namespace` stays fixed while the obfuscation pipeline continues to randomize `applicationId`. *Rationale:* renaming the namespace churns the R file and the obfuscation pipeline (`ObfuscationPlugin`, randomized application IDs in `RandomExt.kt`), which is pure risk for zero functional gain; `smartautoclicker/build.gradle.kts` already decouples fixed `namespace` from dynamic `applicationId`. *Alternatives:* full package rename — rejected.

**D7 — Observations are append-only; no edit/back-fill.** *Decision:* DAO supports insert + read (+ post-sync prune) only. *Rationale:* append-only is the natural shape of a time series, simplifies idempotent sync (R7), and keeps Migration 21→22 trivial (R6). *Constraint added:* pruning is gated on a *confirmed per-record server ack*, never on optimistic send (R7), to avoid silent data loss. *Alternative:* editable records with soft-delete — rejected as unnecessary for MVP.

### Open questions for the product owner

These cannot be resolved by engineering and gate the design:

- **Q1 — What does TraxIntel concretely track first?** The pipeline supports Number, Text, and Color-State. The canonical example is an in-game counter, but the OCR confidence story (R2) differs sharply between, say, Latin-digit game counters versus CJK kiosk strings versus colored status indicators. Note the `OCRAlphabet` enum already ships **11 alphabets** (LATIN, ARABIC, CYRILLIC, CHINESE_SIMPLIFIED/TRADITIONAL, JAPANESE, KOREAN, DEVANAGARI, KANNADA, TAMIL, TELUGU), and Text conditions already select among them per-condition — so a CJK or Arabic beachhead is a *model-availability and accuracy* question, not a missing-feature question. *We need one named beachhead value type and target app* to tune detection-quality defaults, choose the first-loaded recognition model (which matters specifically because the **Number** path is pinned to it, per R2), and write realistic acceptance fixtures.
- **Q2 — What is the distribution channel for the connected build?** R3 makes Play rejection likely for a cloud screen-reader. Is the `cloud` flavor distributed via Play, via direct APK/MDM to managed fleets, or via F-Droid-style sideload? This decision changes the disclosure/privacy work, whether we strip `canPerformGestures` in the connected flavor, and how device enrollment (pairing code) is provisioned at scale.
- **Q3 — Where and how is Trax Cloud hosted, and what is the data-residency posture?** Observations may include cropped screenshots of arbitrary third-party app screens (potentially sensitive). We need the hosting region(s), retention policy for crops, and whether tenant data must be isolated at the storage level or only logically. This drives the `serverReceivedAt` ingest design, crop storage (CDN vs. object store), and the tenant-isolation acceptance gate (Story 9).
- **Q4 — Unattended capture expectation for kiosk fleets.** R1 has no clean stock-Android solution. Does the product owner accept a one-time manual MediaProjection grant per device (with re-grant required if the OS tears it down), or is an OEM/MDM partnership with persistent-projection privileges in scope? This is the highest-leverage unknown for the fleet/kiosk use cases.
- **Q5 — Confidence threshold semantics.** The contract surfaces confidence and marks low-confidence points but does not define the threshold or whether a below-threshold read should still emit `isFulfilled=false`. We need the product owner's definition of "low confidence" to wire the dashboard distinction (Story 6) consistently with the on-device `threshold` already carried by each `ScreenCondition`.
- **Q6 — How homogeneous are the target fleets, and do we need resolution normalization?** R8 shows that a scenario authored on one resolution and run on a device of a different resolution can read the wrong pixels, because `detectionArea` is absolute and `ScalingManager` only maps detection-quality, not author-vs-target resolution. Are fleet devices uniform enough (same resolution class) that a "author on a matching device" deployment constraint suffices for MVP, or must we build a proportional author-rect → target-rect transform? The answer determines whether R8 is a documented constraint or a build item.

---

## Addendum — cross-cutting concerns & gap closure

This addendum is binding where it conflicts with any individual section: it reconciles the schema/protocol/sequencing contradictions the sections introduced and closes the MVP-blocking gaps none of them owns. It defers to the **Data contracts & schemas** section as the canonical data-shape authority and resolves the remaining disputes in that section's favor, then fills the operational holes.

### 1. One Observation schema, one id, one migration (reconciliation)

The three sections that define `ObservationEntity` (System-architecture, core:capture, core:network) each invented a different schema, PK type, and migration mechanism. **The Data-contracts shape wins, with these locked decisions:**

- **Identity:** a single client-generated `String` UUIDv4 is the Room `@PrimaryKey`, the wire `id`, the cloud PK, **and** the idempotency/dedup key. There is no separate `idempotencyKey` column and no `Long` autoincrement PK. The core:network "random UUID per listener call" and core:capture "deterministic `scenarioId:conditionId:ts` key" are **both replaced**: the UUID is generated **once** at capture in the listener and is stable for the row's life. This satisfies the zero-duplicate gate (server `ON CONFLICT (tenant_id, id) DO NOTHING`) without core:capture's collision concern, because the client never re-mints an id for the same reading — capture-side code must call `UUID.randomUUID()` exactly once per `onScreenConditionProcessingCompleted` fire and persist it before any upload.
- **No foreign key to `scenario_table`.** `scenarioId` on the Observation row is the **cloud scenario UUID** (a `String`), not a local `Long` `ScenarioEntity.id`, so a Room `ForeignKey`/`CASCADE` is wrong (System-architecture's and core:capture's CASCADE arguments are void). Pruning is by retention worker, not DB cascade.
- **`syncState` enum is canonical `{PENDING, UPLOADING, SYNCED, FAILED}`**, stored as `.name` TEXT. The `IN_FLIGHT`, `UPLOADED`/Int-encoded, and two-state variants are dropped. `SYNCED` covers both `accepted` and `duplicate` server outcomes.
- **`valueType` is exactly `{NUMBER, TEXT, STATE}`** (the cloud `CHECK` constraint). The implementation plan's `COLOR_STATE` is **wrong and would be rejected server-side**; State reads use `valueType="STATE"`, `value="detected"|"not_detected"`, mirrored by `isFulfilled`. All other vocabularies (`MATCH`/`NO_MATCH`, `DETECTED`/`ABSENT`) are dropped.
- **`confidence` is an `Int` 0–100 on-device and on the wire**, produced by `Math.round(confidenceRate).toInt()` exactly once at capture. The cloud column may remain `NUMERIC(5,2)` for forward-compat, but the device never sends a fraction; the TraxIntel-Cloud example sending `91.5` is corrected to `91`.

**Migration:** ship **one** `21 -> 22` migration. Because it is purely additive (a new table, plus — see §6 — possibly two nullable `ConditionEntity` columns), use a **manual `object Migration21to22 : Migration(21, 22)`** registered in `SmartDatabaseModule.providesClickDatabase(...)`'s `addMigrations(...)`, **not** an `AutoMigration`. Rationale grounded in the verified tree: the `autoMigrations` list exists and 20→21 is add-style, so an AutoMigration is *possible*, but the moment §6's `ConditionEntity` nullable columns are bundled in (and to keep the FK-free observation DDL explicit and reviewable), a single hand-written `CREATE TABLE` + `alterTableAddColumn` is the lower-risk, single-source-of-truth choice. Do **not** also append an `AutoMigration(21,22)` — pick one. The entity, DAO, and migration physically live in **`core:smart:database`** (forced by the `internal` table-name constants and the hardcoded `@Database(entities=[...])` list). `core:observation`/`core:capture` is **one module** (canonical name `core:observation`; the `core:capture` label is an alias) that owns only the domain model, repository, capture listener, and crop capturer, and *depends on* `core:smart:database` for the persisted types.

### 2. One value-surfacing path through the pipeline (reconciliation)

The blueprint cannot ship both the jobject-return-type JNI change (Vision/network/testing) and the separate-`jstring`-accessor change (Risks/D3). **Decision: extend `ProcessedConditionResult.Screen` with two nullable fields `numberDetected: Double? = null` and `recognizedText: String? = null`** (defaults keep Image/Color/legacy callers compiling), populated in `verifyNumberCondition` (the value already read and discarded at line ~201) and `verifyTextCondition`. For the JNI text path, **adopt the jobject/`NativeTextResult` wrapper carrying a `jbyteArray` decoded with `Charsets.UTF_8`** — not `NewStringUTF` — because the product explicitly targets CJK/Arabic/Cyrillic alphabets and `NewStringUTF`'s modified-UTF-8 handling is a documented correctness hazard for supplementary-plane and multi-byte text. The Risks-section separate-accessor approach is dropped. The single sealed `ExtractedValue` carrier from Vision is also dropped in favor of the two flat nullable fields, so all sections read the same field names. `deviceCapturedAt` is delivered by **extending the listener signature with a `deviceCapturedAtMs: Long` parameter** sourced from `ConditionsVerifier.currentVerificationTsMs` (Vision's approach), not by threading it onto the result object or generating it in the listener — this keeps capture time identical to verification time and removes the private-field-access hack.

### 3. First-run onboarding & permission sequencing (gap closure)

No section sequences the three independent grants the product needs. The canonical first-launch flow for a `cloud` build, owned by `feature:cloud`, is a four-step wizard with explicit gating and resumable partial states:

1. **Enroll** (pairing code -> `POST /v1/devices/enroll`). On success persist token + `deviceId`. Device shows `tenantId` bound within 30s (Story 1). 410/404 -> "code invalid or expired, ask your operator for a new one."
2. **Consent disclosure + sync toggle** (DataStore `cloud_sync_enabled`, default OFF). Until ON, no upload occurs even if capture runs locally.
3. **Enable AccessibilityService** (deep-link to settings; poll `SmartAutoClickerService` connection).
4. **Grant MediaProjection** (the one-time consent dialog; capture the token into the live `LocalService`).

The device reports its **setup state** to the cloud on every heartbeat (`{enrolled, syncEnabled, a11yEnabled, projectionLive}`) so the dashboard can show **"awaiting setup"** sub-states instead of a binary online dot. A remote START on a device missing step 3 or 4 is **acked as `PENDING_CONSENT`** (not silently dropped), and the device posts a high-priority "tap to start tracking" notification — the only honest behavior, since neither accessibility nor projection can be granted from the background.

### 4. Health, errors, and capture-success SLO (gap closure)

Liveness (`last_seen_at`) is not health. Add a **per-device capture-health signal** with no new endpoint: each device includes a rolling `pollSuccessRate` (fulfilled Observations / scheduled polls over the last hour) and a `lastFailureReason` enum (`no_projection`, `engine_busy`, `a11y_disabled`, `ocr_low_confidence`, `network`) in the heartbeat body. The dashboard renders three device states — **online+healthy** (`last_seen_at` fresh AND `pollSuccessRate` above a threshold), **online+degraded** (fresh but low success or a sticky failure reason), **offline** (stale). This is the number an operator actually needs and it is currently produced by nobody.

Error classification is unified: **terminal** (401 token-revoked -> stop all workers, clear queue, surface "re-pair"; 410 expired code) vs **retryable** (5xx, IOException, timeout -> exponential backoff, row stays/returns `PENDING`). Per §8, ambiguous timeouts are treated as **not acked** so the server upsert dedupes the inevitable retry rather than the client dropping data.

### 5. Heartbeat producer, protocol version, and forced upgrade (gap closure + reconciliation)

The cloud section defines `POST /v1/devices/heartbeat` (30s cadence, online = `last_seen_at > now()-90s`) but **no on-device section schedules it.** Add a **`HeartbeatWorker`** to `core:network`'s worker set, driven from the same in-service loop that owns capture cadence (not WorkManager periodic — its 15-min floor is too coarse) while a session is active, and from a WorkManager `OneTimeWork` chain when idle-but-enrolled. The heartbeat carries the setup-state and capture-health payloads from §3–4.

Every device request sends `X-TraxIntel-Client: <appVersion>/<dbVersion>/<protocol=1>`. The server may return `426 Upgrade Required` (forced) or a `clientStatus: "deprecated"` field (soft warn) so an unmanaged fleet can be migrated off a broken `/v1` shape; `feature:cloud` surfaces an "update required" wall on 426. This is the missing client/server version-negotiation story.

### 6. Single 21->22 migration content & comparisonOperation decision (reconciliation)

If the Vision section's extract-only Text/Number work ships in MVP, its `ConditionEntity` column adds **must be folded into the same `Migration21to22`** as the Observation table — two sections cannot each independently own version 22. **Decision to minimize migration surface:** do **not** make `comparisonOperation` nullable and do **not** version the backup deserializer. Instead use core:scheduling's/Data-contracts' **synthetic-sentinel** approach: the tracking adapter builds a throwaway `ScreenCondition.Number` with a permissive comparison (`GREATER_OR_EQUALS`, lowest counter value) and a temporary `Identifier`, never persisting it, so `isFulfilled` tracks "a number was read" and the raw `numberDetected` is what we record. This removes the `ConditionEntity`/`ConditionMapper`/`copyWithNewId`/`ScenarioBackup`-version changes entirely, so `Migration21to22` is **observation-table-only** — the simplest, lowest-risk version bump consistent with the migration-cleanliness acceptance gate.

### 7. App-update, token, and component-identity continuity (gap closure)

The obfuscation pipeline randomizes `applicationId` and component class names; enrollment binds to a device identity. **Lock the `cloud` flavor out of applicationId/component randomization** (the existing `shouldRandomize` gate already excludes everything but F-Droid, so this is preserved by construction — add an explicit guard excluding `KlickrFlavour.CLOUD`). The device token lives in standard DataStore (per the security section's deliberate "token is revocable, not secret" decision) and survives in-place app updates; a **reinstall** loses it and triggers re-pairing. On token expiry (`tokenExpiresAt`), the device performs a silent re-enroll using a stored long-lived refresh path is **out of MVP** — instead the device surfaces "re-pair required," consistent with the revoke-not-refresh model. DB forward-compat: the client refuses to open a DB newer than its compiled `DATABASE_VERSION` and shows the §5 update wall rather than crashing.

### 8. Ingest integrity, rate-limiting, and clock skew (gap closure)

- **Ack-before-prune:** the client prunes a local row only on a confirmed per-`id` ack (`accepted` or `duplicate`); ambiguous/timeout = keep + retry. Server upsert handles duplicate delivery; ack-gating handles lost delivery. Crop deletion uses Data-contracts' **reference-counted** prune (content-addressed `Observation_<pixelHash>.png` files are shared across identical crops), never a per-row path delete.
- **Backpressure:** `POST /v1/observations:batch` enforces a server-side max batch size (matching the client `BATCH_SIZE=100`) and a per-device ingest rate limit (a generous multiple of the 5s floor); over-limit returns `429` with `Retry-After`, which the client treats as retryable backoff. This protects the hypertable from a malfunctioning or token-compromised device (tokens are GPL-readable).
- **Clock skew:** the server compares `device_captured_at` to `server_received_at` on ingest; if skew exceeds a threshold it stamps a `clock_skew_ms` on the row and the dashboard flags that device's series as "device clock suspect." The stored truth stays UTC-from-device, but the operator is warned rather than silently mischarted.

### 9. Cost, residency, and retention defaults (gap closure)

Commit the decisions the blueprint left open: **crop capture default OFF** (already in scope) is justified concretely by storage cost — at 5s polling across 20 devices a crop-on scenario produces ~345k PNGs/device/day; the default-off plus a **committed 14-day crop retention** and **180-day numeric/text series retention** bounds both S3 and hypertable growth. **Single hosting region for MVP, logical (tenant_id-scoped + RLS) isolation, region surfaced in the privacy policy** — closing open question Q3 enough to ship, with multi-region/data-residency deferred and named as such. These defaults are configurable per tenant post-MVP but must have a shipped value, since the schema and upload path are already fully specified against them.

### 10. WorkManager bootstrap (reconciliation)

Use core:network's **per-flavor Application** strategy, not the `src/main` `Configuration.Provider` approach: extract `BaseSmartAutoClickerApplication` (un-annotated) into `src/main`; `@HiltAndroidApp LocalApplication` in `src/local` (no WorkManager, no `Configuration.Provider`); `@HiltAndroidApp CloudApplication` in `src/cloud` adding `Configuration.Provider`+`HiltWorkerFactory`, with the `WorkManagerInitializer` `androidx.startup` node removed **only** in `src/cloud/AndroidManifest.xml`. The Risks/Scheduling claim that the existing `src/main` application implements `Configuration.Provider` directly is rejected because it pulls `androidx.work` into the GPLv3 LOCAL build and breaks the no-network gate that is a contract acceptance criterion. Consequently, **scheduled tracking is a `cloud`-flavor capability**; the `local` build's `core:scheduling` is present but inert (no enrollment, no commands, no projection-state UI), resolving the "can fDroidLocal run tracking?" ambiguity to **no, by design**.

---

## Appendix — Critic findings (raw)

### Cross-cutting gaps

- No first-run onboarding / permission-acquisition flow is specified end-to-end. The blueprint assumes MediaProjection consent + AccessibilityService enablement + cloud enrollment all happen, but no section sequences them into a single first-launch wizard, nor defines what the device shows before any of the three are granted. Enrollment (P3-1) and projection grant (R1) and accessibility enablement are described in isolation; the order, the gating between them, and the 'partially set up' states are undefined.
- No error taxonomy / recovery UX. Failure modes are scattered (projection lost -> unfulfilled Observation; 4xx vs 5xx in upload; invalid pairing code -> 410; engine_busy/no_projection reasons) but there is no unified model of how errors surface to the operator on-device or on the dashboard, what is retryable vs terminal, or how a device in a permanently broken state (token revoked, accessibility disabled, app updated) is detected and surfaced. The dashboard's 'online dot' covers liveness but not health.
- No app-update / migration path for enrolled devices. The obfuscation pipeline randomizes applicationId and component names; the security section warns the cloud variant needs a STABLE component identity for enrollment, but no section defines how an enrolled device survives an app update, how device tokens persist across reinstall, schema/protocol version negotiation between client and /v1 (the API is /v1 but there is no client-version compatibility or forced-upgrade story), or what happens when the DB is at v22 and a newer build expects v23.
- No cost model anywhere. No section addresses cloud hosting cost, S3 storage cost for crops (PNGs at quality 100, potentially every 5s across 20 devices), egress, observation volume/retention economics, or device battery/data cost. Billing is explicitly out of scope, but cost-of-goods for the MVP fleet at 5s polling is never even estimated, which matters for the retention-window and crop-default decisions that ARE in scope.
- No data-residency / region decision is made (it is only raised as open question Q3 in the risks section). Crops can contain arbitrary third-party screen content (PII), yet no section commits to a hosting region, tenant-level storage isolation vs logical isolation, or crop retention default — leaving a GDPR-load-bearing decision unresolved while the schema and upload path are fully specified.
- No observability of the capture-success rate as a product metric. The testing section defines an OCR accuracy floor for CI, but production has no defined SLO for 'fraction of scheduled polls that produced a fulfilled Observation per device,' which is the single number that tells an operator whether a device is actually working. Backend metrics list latency/ingest counters but not per-device capture health.
- No rate-limiting / backpressure / abuse control on the ingest path. POST /v1/observations:batch has no documented per-device or per-tenant rate limit, max batch size enforcement (client says BATCH_SIZE=100 but the server contract never bounds it), or protection against a malfunctioning device flooding the hypertable. Given device tokens are in GPL-readable source, a compromised token could spam ingest with no described throttle.
- No clock-skew handling for deviceCapturedAt. The time-series chart plots value over device_captured_at, which is System.currentTimeMillis() on an unmanaged device whose clock may be wrong. No section addresses skew detection or correction, despite the dashboard's primary visualization keying entirely on a device-supplied timestamp.
- No accessibility-config narrowing is actually wired. The security section argues the cloud flavor should drop canPerformGestures and narrow the AccessibilityService, but no section in the implementation plan (P0–P4) has a ticket to create the per-flavor accessibilityservice.xml override or runtime-narrowed AccessibilityServiceInfo — so the Play-risk mitigation is asserted but unbuilt.

### Inconsistencies

- ObservationEntity schema is defined THREE incompatible ways across sections. (a) System-architecture & core:capture: String/enum-named columns, Identifier-based ids, FK to ScenarioEntity with onDelete=CASCADE, columns event_id/condition_id/screen_pos_*/have_been_detected, syncState stored as Int (0=PENDING,1=UPLOADED). (b) core:network: NO foreign key, Long autoGenerate PK, idempotencyKey String, cloud_scenario_id column, ObservationSyncState{PENDING,UPLOADING,SYNCED,FAILED} stored as TEXT, no event/condition/position columns. (c) Data-contracts (declared canonical): String UUID PRIMARY KEY, NO foreign key, tenant_id+device_id columns, value_type='NUMBER|TEXT|STATE', single value:String, syncState as enum .name, retry_count. These cannot all be the migration that ships. Data-contracts wins by its own declaration, but core:capture and system-architecture both build elaborate FK/CASCADE arguments that directly contradict it.
- PRIMARY KEY type contradicts itself. core:capture and core:network use Long autoGenerate PK with a separate unique idempotency_key. Data-contracts uses a String UUID as BOTH the PK and the idempotency/dedup key and explicitly argues a second column would carry no information. The DAO @Insert(onConflict=IGNORE) semantics, the crash-replay behavior, and the migration DDL all differ as a result.
- Migration 21->22 is specified as both MANUAL and AUTO. System-architecture, core:capture, core:network, and the Vision section all insist on a MANUAL object Migration21to22 (CREATE TABLE), explicitly arguing a new FK+indexed table is 'not auto-inferable.' Data-contracts and the Risks/implementation sections (R6, P1-3 partially) specify an AutoMigration(from=21,to=22) appended to the autoMigrations list, arguing an add-only table IS auto-inferable. The verified codebase (AutoMigration 20->21 is add-style; manual migrations exist for transforms) makes EITHER defensible — but the blueprint must pick one, and right now half the sections register it in addMigrations() and half in the autoMigrations array. core:capture even hedges by registering it in addMigrations while calling it manual, while Data-contracts requires committing a generated 22.json that a manual migration would not need.
- Module name conflict: core:observation vs core:capture. The contract and most sections call the module core:observation; the 'core:capture' section renames it core:capture and says 'treat them as the same artifact.' Worse, core:network and Data-contracts both physically relocate the entity/DAO/migration INTO core:smart:database and argue core:observation 'does not own' them (core:network: 'core:network depends on this layer; it does not own it'), while core:capture says the domain model/repository/listener live in core:capture and the entity lives in core:smart:database. The ownership boundary of the Observation entity is genuinely unsettled.
- ObservationSyncState enum values differ. core:capture: {PENDING, IN_FLIGHT, SYNCED, FAILED}. core:network: {PENDING, UPLOADING, SYNCED, FAILED}. Data-contracts (canonical): {PENDING, UPLOADING, SYNCED, FAILED}. System-architecture: {0=PENDING,1=UPLOADED} (only two states, Int-encoded). These drive the offline-queue query and the on-device sync-status UI.
- The value/valueType encoding for Color-State conflicts. core:capture maps Color -> valueType 'STATE' with value 'MATCH'/'NO_MATCH'. System-architecture maps Color -> 'STATE' with 'MATCH'/'NO_MATCH'. Implementation plan P1-2b maps Color -> valueType 'COLOR_STATE' with value 'DETECTED'/'ABSENT'. Data-contracts (canonical) maps it to 'STATE' with value 'detected'/'not_detected'. Three different valueType strings ('STATE' vs 'COLOR_STATE') and three different value vocabularies for the same read type; the cloud CHECK constraint only accepts 'NUMBER|TEXT|STATE', so 'COLOR_STATE' would be rejected server-side.
- The JNI marshalling approach is described two incompatible ways. Vision section and core:network favor changing detectTextNative's RETURN TYPE to a jobject/NativeTextResult wrapper carrying a jbyteArray (UTF-8-safe, explicitly rejecting NewStringUTF). The Risks section (R2/D3) explicitly REJECTS the jobject approach and instead adds a SEPARATE jstring accessor (getLastRecognizedTextNative) using NewStringUTF, requiring new per-instance string storage on the C++ Detector. The testing section assumes the jobject/return-type-change path. These are mutually exclusive implementations of the same contract line, with opposite positions on the UTF-8/NewStringUTF safety question.
- ProcessedConditionResult.Screen extension field names differ. System-architecture/core:capture add numberDetected + recognizedText (or capturedNumber/capturedText/verificationTsMs). Vision adds a single sealed 'extracted: ExtractedValue?' (Num/Txt/ColorState). Risks (D3b) adds numberDetected + recognizedText. The capture listener code in different sections reads different field names off the same object.
- How deviceCapturedAt reaches the Observation conflicts. core:capture threads a private verificationTsMs onto the Screen result and uses it as the basis of a DETERMINISTIC idempotency key (scenarioId:conditionId:ts). Vision EXTENDS the listener signature with a new deviceCapturedAtMs parameter. core:network and Data-contracts use System.currentTimeMillis() at capture and a RANDOM UUID (core:network: 'UUID.randomUUID()') as the idempotency key. The deterministic-vs-random idempotency key is a real correctness disagreement: core:capture explicitly says a random UUID 'never collides' and undercuts dedup, while core:network ships exactly that random UUID.
- Heartbeat / liveness mechanism only exists in the cloud section. The TraxIntel-Cloud section defines POST /v1/devices/heartbeat on a 30s cadence and an online rule of last_seen_at > now()-90s. No on-device section (core:network, scheduling, feature:cloud) implements or schedules a heartbeat worker; core:network's worker list is upload + scenario-pull + command-pull only. The dashboard's online dot has no client-side producer in the device-side plans.
- Scenario-pull endpoint shape conflicts. core:network: GET /v1/scenarios/assigned?since=<cursor>. TraxIntel-Cloud: GET /v1/scenarios with a knownRevisions map body + removedScenarioIds response. Data-contracts: GET /v1/devices/{deviceId}/scenarios returning a flat scenarios array with a tracking flag. Three different paths, three different revisioning schemes (single since-cursor vs per-scenario knownRevisions vs flat list).
- Observation batch endpoint and response shape conflict. core:network: POST observations:batch returning {accepted:[key], cropUploadUrls:{key->url}}. TraxIntel-Cloud: returning {results:[{idempotencyKey,status,cropUploadUrl}]} keyed by idempotencyKey. Data-contracts: returning {results:[{id,status,serverReceivedAt,cropUploadUrl}]} keyed by id (since id==idempotency key). The dedup key field name (id vs idempotencyKey) and the crop-URL delivery shape (map vs per-row) differ.
- TrackingScenario read-type naming: the State/Color read type is variously 'State', 'STATE', and 'Color-State', and maps to ScreenCondition.Color. The cloud DDL stores read_type CHECK IN ('NUMBER','TEXT','STATE') and a separate match_text column 'for STATE: the ScreenCondition.Text target string' — but State maps to ScreenCondition.Color (a color match), not ScreenCondition.Text, so the cloud's own comment on match_text is internally wrong about which condition subtype State uses.
- Crop coordinate space is contradicted within the capture story. core:capture has a detailed 'must-fix' that crops MUST use the detection-space (scaled-down) rect via ScalingManager, because the frame in hand is detection-resolution. System-architecture's FrameCropper crops the live frame to the 'scaled-up detectionArea' (screen-space). Security/data sections say the crop is 'the bitmap of the detectionArea region' (screen-space). The resolution of the stored crop (detection-space vs screen-space) is genuinely unsettled and affects dashboard fidelity.
- Crop file naming and dedup is contradicted. Data-contracts establishes (verified against BitmapRepository) that crop filenames are content-addressed by pixel hashCode (Observation_<pixelHash>.png), so identical crops SHARE one file, forcing reference-counted pruning. core:capture and system-architecture treat cropPath as effectively per-observation and prune by deleting a synced row's path directly — which Data-contracts shows would corrupt a still-pending observation sharing that file.
- Application class strategy for WorkManager conflicts. core:network specifies per-flavor Application classes (BaseSmartAutoClickerApplication in src/main, LocalApplication in src/local, CloudApplication in src/cloud) with a manifest InitializationProvider node-removal ONLY in src/cloud. Scheduling and Risks (D5) instead say SmartAutoClickerApplication (the existing @HiltAndroidApp class in src/main) implements Configuration.Provider directly and the default WorkManagerInitializer is removed in the manifest — which core:network explicitly argues is impossible without breaking the GPLv3/no-network gate because it pulls WorkManager into LOCAL.
- Whether core:scheduling is local-capable conflicts. Implementation plan (P0-4) and rebrand section say core:scheduling is local-capable (plain implementation, linked in both flavors, with a no-op local sync binding). System-architecture says core:scheduling is flavor-agnostic and local capture works without network. But scheduling itself, core:network, and Risks all tie scheduled capture to enrollment + cloud commands + projection that only the cloud flavor wires up, and feature:cloud (cloud-only) is what surfaces re-grant/capture state — so whether a pure fDroidLocal build can actually run scheduled tracking at all is left genuinely ambiguous.
- DATABASE_VERSION bump is claimed by two independent feature sets landing at 21->22. The Vision section bundles ConditionEntity nullable columns (extract_only, nullable comparison_operation) + the Observation table into one 21->22 migration. core:capture and core:network land ONLY the Observation table at 21->22. If both ship, 21->22 must include the ConditionEntity column adds too, but the Observation-only sections' migration DDL omits them — a real collision on the single version number.
- comparisonOperation nullability conflicts. Vision makes ScreenCondition.Number.comparisonOperation NULLABLE (extract-only) and details a versioned ScenarioBackup/DeserializerFactory change. core:scheduling instead keeps it non-null and synthesizes a permissive sentinel (EQUALS / GREATER_OR_EQUALS with an extreme counterValue). Data-contracts uses the sentinel approach ('comparisonOperation = <ignored>'). The nullable-vs-sentinel choice changes whether ConditionEntity/backup migration work is even needed.
- device_commands / command-pull endpoint shape conflicts. core:network: GET commands/pending + POST commands/{id}/ack with a body. TraxIntel-Cloud: GET /v1/commands + POST /v1/commands/{id}:ack, dashboard enqueues via POST /v1/devices/{id}/commands. Data-contracts: GET /v1/devices/{deviceId}/commands + POST /v1/devices/{deviceId}/commands:ack with {ids:[...]}. Command type enum also differs: START|STOP (core:network, data-contracts) vs START_TRACKING|STOP_TRACKING (TraxIntel-Cloud cloud DDL CHECK).
- Confidence type conflicts on the wire and in storage. core:capture stores confidence as Double on the entity. System-architecture/core:network round to Int (0-100). Data-contracts mandates Int via Math.round (round-half-up) and stores cloud-side as NUMERIC(5,2) — but TraxIntel-Cloud's DDL declares confidence NUMERIC(5,2) and its API example sends 91.5, while Data-contracts' API example sends integer 91 and the entity column is Int. So the device either sends a rounded Int (data-contracts) or a fractional Double (cloud section) — the two canonical-ish sections disagree on whether fractional confidence survives.

### Missing topics

- Client/server protocol versioning and forced-upgrade. The API is rooted at /v1 but nothing defines how the device advertises its app/schema version, how the server rejects or warns an out-of-date client, or how a breaking /v1 change is rolled out across an unmanaged fleet.
- Device de-enrollment, token rotation, and re-pairing lifecycle from the device's perspective. The cloud can revoke (DELETE /v1/devices/{id}), but the on-device behavior on a 401 after revocation (stop workers, clear local queue, surface 're-pair' UI) is only half-specified, and token EXPIRY (enroll response includes tokenExpiresAt) has no refresh flow at all.
- Crop retention and storage-cost policy as a concrete default. Retention is mentioned ('N days') but no committed default, no enforcement mechanism on the server, and no link between the crop-default-off decision and the storage cost it controls.
- Time-series downsampling / query performance at scale on the dashboard. The chart queries raw observations (one per 5s per device); for 20 devices over a 30-day range that is millions of points with no described aggregation, bucketing, or LTTB-style downsampling.
- Secrets management for the backend (JWT signing key, S3 credentials, DB credentials, the device-token hashing). The client side discusses token storage carefully; the server side never says where its own secrets live or how they rotate beyond a mention of JWT kid.
- Internationalization of the dashboard and of OCR-captured non-Latin values end-to-end (the app is translated into 11 locales; the dashboard is English-only and the verbatim-UTF-8 storage path's rendering in the web UI is unspecified).
- Accessibility (a11y) of the web dashboard itself — never mentioned; relevant given the product literally rides on an AccessibilityService and may face scrutiny.
- Disaster recovery / backup of Trax Cloud (Postgres/Timescale + S3) — no RPO/RTO, no backup cadence, despite Observations being the irreplaceable product data.
- Load/perf and battery testing for the continuous-capture-with-sampling model — the testing section covers correctness gates but not sustained 5s polling battery drain or thermal behavior on real fleet hardware, which R4 flags as a risk but no test owns.
- Legal basis / consent flow for the fleet case where the monitored device's user is NOT the account owner (employee/kiosk monitoring) — the security section raises it as the highest-risk GDPR scenario but no product flow (in-device notice to the monitored person, operator attestation) is designed.

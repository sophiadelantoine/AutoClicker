---
title: TraxIntel MVP — ACMRI
status: Draft
owner: Sophia D'Antoine <ibenmoataz@gmail.com>
created: 2026-06-29
updated: 2026-06-29
repo_source_path: documentation/TRAXINTEL_MVP_ACMRI_2026-06-29.md
blueprint_source: TRAXINTEL_MVP_BLUEPRINT.md
jira_issue: TBD — no tracker configured for this repo
confluence_page: TBD — no tracker configured for this repo
labels: [traxintel-mvp, acmri, cross-cutting, read-only-mvp, manual-trigger-first, gplv3-license-firewall, flavor-gating, connectivity-flavor, ocr, jni, mediaprojection, accessibility-service, room-migration, observation, offline-sync, idempotency, workmanager, foreground-service, battery, play-policy, tenant-isolation, gdpr, risk-register, decision-log]
affected_paths:
  - settings.gradle.kts
  - build-logic/
  - core/smart/processing/
  - core/smart/database/
  - core/smart/detection/
  - core/smart/detection-models/
  - core/smart/domain/
  - core/smart/debugging/
  - feature/smart-config/
  - feature/backup/
  - feature/notifications/
  - smartautoclicker/
  - smartautoclicker/src/main/
  - smartautoclicker/src/local/
  - smartautoclicker/src/cloud/
  - core/capture/
  - core/observation/
  - core/network/
  - core/scheduling/
  - feature/cloud/
  - ../trax-cloud/
---

# TraxIntel MVP — ACMRI

> Actionable, Checkable Markdown Roadmap Implementation plan — human-editable and machine-checkable.
> Lint: `python3 scripts/acmri_lint.py documentation/TRAXINTEL_MVP_ACMRI_2026-06-29.md`
> Derived from `TRAXINTEL_MVP_BLUEPRINT.md`. Section references below point into that blueprint.

## Goal

Ship the thinnest viable TraxIntel MVP that turns an enrolled Android device into an unattended, read-only observer of another app's on-screen value: an operator marks a screen region, the existing on-device OCR engine reads a Number/Text/State on a schedule, each reading is persisted as a timestamped structured Observation (value + Int confidence 0-100 + optional cropped PNG), and Observations sync to a separate non-GPL cloud backend with a web dashboard that trends values across a 3-20 phone fleet and pushes scenarios + remote start/stop back down. Achieve this by extending (not rebuilding) the GPLv3 Smart AutoClicker codebase: add one capture seam where the OCR result is currently read and discarded, persist Observations behind an additive Room migration 21->22, and gate ALL networking/WorkManager behind a new CONNECTIVITY flavor dimension so F-Droid/Play local builds stay GPLv3-clean and network-free. Sequence manual-trigger-first (prove one Observation lands in cloud and renders on a chart) before headless scheduling, remote control, and fleet-wide scenario push.

## OKRs

### O1 — Prove the end-to-end value path: a real on-screen value reaches the cloud dashboard. WHY: nothing else matters until one Observation with a value renders on a chart; the result object carries no value field today (R2b), so this is the gating proof that the product can ship any data at all.
- KR: A Number scenario at a 10s interval produces ~1 Observation per 10s, each with full metadata (UUIDv4 id, valueType=NUMBER, value, confidence 0-100, deviceCapturedAt, scenarioId).
- KR: A Text scenario carries the recognized string end-to-end through the modified JNI path (NativeTextResult byte[] decoded UTF-8) into ProcessedConditionResult.Screen.recognizedText and onto the dashboard.
- KR: Migration 21->22 lands via manual Migration21to22 with zero loss: all existing scenarios and conditions survive an upgrade in CI.
- KR: A manually triggered Observation is visible on the web dashboard time-series chart within one sync cycle.

### O2 — Keep the F-Droid/Play local product GPLv3-clean and policy-compliant while shipping a connected cloud build. WHY: a license-boundary or Play-policy mistake can pull the whole listing or create a GPL violation against a proprietary backend; the flavor split is the license firewall and must be CI-enforced, not aspirational.
- KR: CI hard-fails the build if any core:network or feature:cloud class is linked into fDroidLocalRelease or playStoreLocalRelease.
- KR: The cloud flavor narrows accessibilityservice config (drops unused canPerformGestures for the read-only MVP) and ships prominent in-app disclosure + privacy policy.
- KR: The backend (Trax Cloud) communicates only over a versioned /v1 REST boundary with zero GPLv3 source copied server-side, verified by repo separation.
- KR: Every new network dependency (Retrofit/OkHttp/kotlinx-serialization) is confirmed permissive-licensed before merge.

### O3 — Make fleet observation reliable and idempotent across flaky networks and OS-imposed limits. WHY: at-least-once delivery plus optimistic pruning equals silent data loss the server cannot repair; reliability is what an operator actually pays for across 3-20 phones.
- KR: Offline Observations upload with zero server-side duplicates via client UUIDv4 idempotency key + server ON CONFLICT (tenant_id, id) DO NOTHING.
- KR: A local Observation row is pruned only after a confirmed per-record server ack; ambiguous timeouts are treated as not-acked and retried.
- KR: Projection teardown is captured as a first-class syncable signal (Observation with isFulfilled=false + low/zero confidence) rather than a silent gap.
- KR: Tenant isolation (logical, RLS-scoped) is enforced so no tenant can read another tenant's Observations.

### O4 — Deliver remote fleet control within the constraints of stock-Android capture. WHY: dashboard-authored scenarios and remote start/stop are the fleet differentiator, but MediaProjection consent and WorkManager's 15-min floor reshape what scheduling can promise; we must promise only what the platform can keep.
- KR: A dashboard-authored scenario pushed to 3 devices results in all 3 emitting Observations within one sync cycle.
- KR: A remote stop command halts tracking within one command-poll cycle.
- KR: The 5s sampling cadence is driven from the live foreground service (gating frame processing, not session start/stop), while WorkManager handles only coarse jobs (sync flush, command pull, session re-arm, heartbeat).
- KR: A remote START on an unready device returns an explicit PENDING_CONSENT ack rather than silently failing.

## Conventions

- Task IDs are stable (`P1-T03`) and never reused after publication. When a task is split into single-slice subtasks, suffix the id with a lowercase letter (`P0-T04a`, `P0-T04b`); the base number is never reassigned.
- Check a task box `[x]` only when its Definition of Done is met AND validation evidence is recorded in the Evidence Ledger.
- Validation uses the smallest meaningful command first; full repo gates only when shared behavior changes.
- `./gradlew` commands assume the `fDroid` flavor unless a task targets Play-Store-only surfaces.
- Backend tasks live in a separate, non-GPL TraxIntel Cloud repo; commands are placeholders until that repo exists.

## Assumptions

- The existing frame-acquisition -> ScalingManager -> OCR -> ConditionsVerifier stack is reusable and battle-tested; only one new observation seam at SmartProcessingListener.onScreenConditionProcessingCompleted is needed (D2).
- A one-time MediaProjection grant + AccessibilityService enablement can be captured at enrollment and persisted by OEM/MDM for unattended fleets; in-app persistence across reboot/teardown is a deployment constraint, not solvable in-app (R1, pending Q4).
- Target fleets are homogeneous enough in resolution that an 'author on a matching-resolution device' constraint suffices for MVP; no runtime proportional author-rect->target-rect transform is built (R8, pending Q6).
- Retrofit/OkHttp/kotlinx-serialization are permissive-licensed and preserve GPLv3 compatibility (R5).
- All 11 OCR alphabets already ship and Text conditions already select alphabet per-condition (ConditionsVerifier.kt:241), so CJK/Arabic/Cyrillic support is a model-availability/accuracy question, not a missing feature (Q1).
- Only detectNumber is pinned to the first-loaded recognition model (text_matcher.hpp defaultRecognitionModelId); the 'wrong model' risk is Number-only, not Text.
- Device tokens are revocable-not-secret: stored in standard GPL-readable DataStore; security relies on server-side revocation + rate limiting, not token secrecy.
- The obfuscation pipeline's shouldRandomize gate already excludes everything but F-Droid, so the cloud flavor's stable component identity is preserved by construction.
- Package names stay com.buzbuz.smartautoclicker.* (brand rename only) so the R file and obfuscation pipeline are not churned (D6).
- MVP hosting commits to a single region with logical tenant-scoped RLS isolation, 14-day crop retention, and 180-day numeric/text retention; multi-region/data-residency is deferred (Addendum 9, partially closes Q3).
- Crop capture defaults OFF given the volume (~345k PNGs/device/day at 5s x 20 devices).

## Global Dependencies

- R2b is the gating prerequisite for the entire value proposition: ProcessedConditionResult.Screen (core:smart:processing) must gain nullable numberDetected/recognizedText fields and both verify methods must populate them before any value (Number OR Text) reaches the dashboard.
- R2 native/JNI work (NativeTextResult jbyteArray wrapper, per-instance C++ string storage in text_recognizer.cpp/text_matcher.hpp) blocks Text observations specifically; the Number path does not depend on it.
- MediaProjection-consent-in-background constraint (R1) reshapes what scheduled execution and remote-start can promise; scheduling design depends on resolving Q4.
- WorkManager + HiltWorkerFactory + WorkManager.initialize bootstrap is net-new (no androidx.work today) and prerequisite for all coarse reliable jobs (sync flush, command pull, session re-arm, heartbeat) (R4/D5).
- Manual Migration21to22 must land in SmartDatabaseModule.providesClickDatabase addMigrations(...) before any persisted Observation; Observation entity/DAO physically live in core:smart:database due to internal table-name constants and the hardcoded @Database list.
- A HeartbeatWorker (POST /v1/devices/heartbeat) must be newly built in core:network; the original plan had no on-device producer.
- Per-flavor Application classes (BaseSmartAutoClickerApplication in src/main, LocalApplication in src/local, CloudApplication in src/cloud) are prerequisite for WorkManager bootstrap; the WorkManagerInitializer startup node is removed only in src/cloud/AndroidManifest.xml.
- The separate Trax Cloud backend repo (REST /v1 + web dashboard) must exist and define the versioned protocol before the connected client can integrate.
- A CI acceptance gate that fails the build when network classes appear in local flavors is a prerequisite control for the GPLv3 license firewall (R5).

## Phase P0 — Rebrand & build foundation

**Objective:** Rebrand Klick'r to TraxIntel (app_name, the ~17 hardcoded brand references, launcher icons, translated strings) without churning namespace/applicationId, point the playStore Firebase/FCM config at a new TraxIntel project, and establish the connectivity flavor strategy plus version-catalog entries that unblock all later cloud work. The connectivity dimension must preserve variant-gating correctness: getVariantName() in build-logic must emit the new CONNECTIVITY segment so the substring-based isBuildForVariant() keeps resolving fDroidLocalDebug / playStoreCloudRelease and the gated build-script logic (debug applicationIdSuffix, per-ABI splits, asset packs, versionCode loop, crashlytics apply) keeps firing. Strictly low-risk and runtime-behavior-neutral: every change must keep the renamed fDroidLocal debug build assembling across the app module and the ~25 library modules that apply the flavour convention plugin.

**Entry criteria:**
- master is green on a clean checkout before any P0 change: ./gradlew testFDroidDebugUnitTest and ./gradlew :smartautoclicker:assembleFDroidDebug both pass
- JDK 21 and Android NDK 28.2.13676358 are installed and configured (required by the convention plugins and the native CMake/JNI build)
- Decision recorded that LOCAL is the default connectivity flavor and that namespace + applicationId remain com.buzbuz.smartautoclicker (only app_name / brand strings / launcher icons change in P0)
- A new TraxIntel Firebase project (or a valid placeholder google-services.json whose client package_name is exactly com.buzbuz.smartautoclicker) is available to replace smartautoclicker/src/playStore/google-services.json
- Confirmed the variant-gating mechanism before edits: isBuildForVariant() (build-logic/convention/src/main/kotlin/com/buzbuz/gradle/convention/extensions/ProjectExt.kt:46-56) substring-matches getVariantName() (ProductFlavourExt.kt:41-51) against the invoked Gradle task name; this is the contract P0-T04b must preserve

**Exit criteria:**
- App displays "TraxIntel" (app_name string + manifest ${appName} label) and no user-facing or build-script string still reads Klick'r outside license headers, the historical-project source comments, and the unchanged com.buzbuz.smartautoclicker namespace/applicationId
- CONNECTIVITY flavor dimension exists with LOCAL (default, ordered first) and CLOUD flavours; the 2x2x2 variant matrix resolves (e.g. fDroidLocalDebug, playStoreCloudRelease) and the renamed fDroidLocalDebug build assembles in the app module
- getVariantName() emits the CONNECTIVITY segment so isBuildForVariant() still resolves; verified by the fDroidLocalDebug build producing an applicationId with the .debug suffix (com.buzbuz.smartautoclicker.debug) and per-ABI + universal split APKs
- The doubled flavor matrix configures across the ~25 androidLib modules that apply com.buzbuz.gradle.android.flavour: a representative library module assembles its new fDroidLocalDebug variant
- cloudImplementation dependency-scoping helper exists; a probe cloudImplementation(...) edge resolves into the cloud variant configuration and is absent from the fDroidLocalReleaseRuntimeClasspath dependency tree
- Version catalog has Retrofit/OkHttp + kotlinx-serialization-converter and WorkManager + Hilt-Work aliases that resolve with no version conflict against existing kotlinx-serialization/Hilt/KSP/AGP versions, with no consumer wiring yet
- playStore CLOUD release builds against the new TraxIntel google-services.json (assemblePlayStoreCloudRelease succeeds, processing google-services without a package-mismatch error) with no Klick'r / old Firebase project identifiers remaining
- ObfuscationPlugin / RandomizeApplicationTask still resolve the intended variant after the rename, verified against a built obfuscated release variant
- CI workflow assemble*/bundle*/uploadCrashlyticsSymbolFile* task references are remapped to the new variant names in release.yml, release-playstore.yml, and nightly-obfuscation.yml
- Smoke gate: ./gradlew clean :smartautoclicker:assembleFDroidLocalDebug assembles end to end including NDK native compilation + Hilt/KSP codegen

**Validation gates:**
- `./gradlew testFDroidDebugUnitTest (whole-repo fDroid debug unit gate; the exact task execute-tests.yml runs — run after any rename touching shared modules)`
- `./gradlew clean :build-logic:convention:compileKotlin (convention plugins, including KlickrVariants and getVariantName changes, compile)`
- `./gradlew :smartautoclicker:assembleFDroidLocalDebug (renamed FOSS default-flavor debug smoke build assembles)`
- `./gradlew :core:common:base:assembleFDroidLocalDebug (representative androidLib module assembles its new connectivity variant, proving the doubled matrix configures)`
- `./gradlew :smartautoclicker:assemblePlayStoreCloudRelease -PsigningStorePassword="$SIGNING_STORE_PASSWORD" -PsigningKeyAlias="$SIGNING_KEY_ALIAS" -PsigningKeyPassword="$SIGNING_KEY_PASSWORD" (playStore CLOUD release: processes the new google-services.json + applies crashlytics + exercises obfuscation/R8)`
- `./gradlew :smartautoclicker:lintFDroidLocalDebug (variant rename introduces no lint errors in the app module)`
- `Full local gate before merging the phase: ./gradlew clean :build-logic:convention:compileKotlin lint testFDroidDebugUnitTest testPlayStoreDebugUnitTest :smartautoclicker:assembleFDroidDebug :smartautoclicker:assemblePlayStoreDebug`

**Stop / blocker conditions:**
- getVariantName() cannot be made to emit the CONNECTIVITY segment without breaking another consumer of the variant name (e.g. the fDroid()/playStore() productFlavors accessors in ProductFlavourExt.kt), such that isBuildForVariant() substring matching or obfuscation variant resolution still fails -> stop and re-audit the variant-name model before proceeding
- The new TraxIntel Firebase project / google-services.json is unavailable, or its client package_name does not match com.buzbuz.smartautoclicker, causing the google-services Gradle plugin to fail under the PLAY_STORE+RELEASE apply -> stop P0-T06 until a valid config is provided
- Adding the CONNECTIVITY dimension multiplies CI assemble*/bundle* task names such that any reference in release.yml, release-playstore.yml, or nightly-obfuscation.yml cannot be remapped without breaking the release pipeline -> stop and resolve the rename audit first
- Adding CONNECTIVITY across the ~25 androidLib modules causes a representative library module to fail configuration/assembly (variant explosion, missing source set) -> stop and re-scope the flavour convention change before continuing
- Version-catalog additions introduce a transitive version conflict that ./gradlew help / dependency resolution cannot satisfy -> stop and pin versions before scaffolding consumers

#### [x] P0-T01 — Rebrand app_name, manifest label, and hardcoded brand strings to TraxIntel  `size: S`
**Slice:** Rename the user-facing brand from Klick'r to TraxIntel in the default app_name string, the in-string brand mentions, and the manifest ${appName} label, without touching namespace or applicationId.
**Definition of Done:**
- [ ] smartautoclicker/src/main/res/values/strings.xml: app_name (line 20) reads TraxIntel; the two other Klick'r mentions (lines 89 and 143) are reworded to TraxIntel
- [ ] The ${appName} manifest placeholder in smartautoclicker/src/main/AndroidManifest.xml (android:label) resolves to TraxIntel at build time
- [ ] grep -i 'klick' smartautoclicker/src/main/res/values/strings.xml returns no matches
- [ ] namespace and applicationId in smartautoclicker/build.gradle.kts are unchanged (still com.buzbuz.smartautoclicker on lines 42, 50)
- [ ] App assembles and the launcher label shows TraxIntel
**Validation:**
```sh
./gradlew :smartautoclicker:assembleFDroidDebug
```
**Traceability:** Blueprint P0-5 (Brand rename, strings/assets only); smartautoclicker/src/main/res/values/strings.xml; smartautoclicker/src/main/AndroidManifest.xml; smartautoclicker/build.gradle.kts
**Dependencies:** none

#### [x] P0-T02 — Rebrand translated app strings and rootProject display name  `size: S`
**Slice:** Propagate the TraxIntel rename into every translated strings.xml that mentions Klick'r and update the Gradle rootProject display name, leaving applicationId untouched.
**Definition of Done:**
- [ ] All locale strings.xml under smartautoclicker/src/main/res/values-*, core/common/ui/src/main/res/values-*, and core/common/permissions/src/main/res/values-* that contain a Klick'r mention are reworded to TraxIntel (confirmed set includes values-ar, -fr, -it, -es, -ru, -ja, -pt-rBR, -uk, -zh-rCN, -zh-rTW in each of the three module trees)
- [ ] settings.gradle.kts line 27 rootProject.name is changed from "Klick'r" to "TraxIntel"
- [ ] grep -rli 'klick' over the src trees (excluding **/build/**) returns ONLY the enumerated allowlist of expected residual matches: GPL license-header lines, historical-project source-code comments, and the unchanged com.buzbuz.smartautoclicker namespace/package strings — no user-facing translated string remains
- [ ] No translatable="false" string regresses and all XML still parses (the unit gate compiles resources successfully)
**Validation:**
```sh
./gradlew testFDroidDebugUnitTest
```
**Traceability:** Blueprint P0-5 (translated strings); smartautoclicker/src/main/res/values-*/strings.xml; core/common/ui/src/main/res/values-*/strings.xml; core/common/permissions/src/main/res/values-*/strings.xml; settings.gradle.kts
**Dependencies:** P0-T01

#### [x] P0-T03 — Replace launcher icons with TraxIntel branding  `size: S`
**Slice:** Swap the ic_smart_auto_clicker launcher/round icon assets (all density mipmaps + adaptive-icon v26 XML) for TraxIntel artwork, keeping the existing resource names so the manifest references stay valid.
**Definition of Done:**
- [ ] New TraxIntel artwork replaces ic_smart_auto_clicker.png and ic_smart_auto_clicker_round.png across mipmap-mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi
- [ ] mipmap-anydpi-v26/ic_smart_auto_clicker.xml and ic_smart_auto_clicker_round.xml adaptive icons reference the new foreground/background
- [ ] Manifest android:icon=@mipmap/ic_smart_auto_clicker and android:roundIcon references are unchanged and still resolve
- [ ] aapt resource processing succeeds (no missing-density or malformed-PNG error) and the debug APK installs showing the new icon
**Validation:**
```sh
./gradlew :smartautoclicker:assembleFDroidDebug
```
**Traceability:** Blueprint P0-5 (launcher icons); smartautoclicker/src/main/res/mipmap-*/ic_smart_auto_clicker.png; smartautoclicker/src/main/res/mipmap-anydpi-v26/ic_smart_auto_clicker.xml; smartautoclicker/src/main/AndroidManifest.xml
**Dependencies:** none

#### [x] P0-T04a — Add CONNECTIVITY dimension with LOCAL(default)/CLOUD to the variant model  `size: S`
**Slice:** Add a CONNECTIVITY flavour dimension plus LOCAL(default)/CLOUD flavours to KlickrVariants.kt so the flavor matrix doubles, ordering LOCAL first so it is the default.
**Definition of Done:**
- [ ] build-logic/convention/src/main/kotlin/com/buzbuz/gradle/convention/model/KlickrVariants.kt: KlickrDimension gains a CONNECTIVITY entry and KlickrFlavour gains LOCAL and CLOUD entries with dimension = KlickrDimension.CONNECTIVITY
- [ ] LOCAL is declared before CLOUD in KlickrFlavour.entries so it resolves as the default connectivity flavour
- [ ] FlavourConventionPlugin.kt requires no manual edit: its KlickrFlavour.entries loop (lines 36-42 and 49-55) creates the new flavours for both the androidApp and androidLib blocks
- [ ] build-logic:convention compiles and ./gradlew tasks shows the doubled variant names (fDroidLocalDebug, playStoreCloudRelease, etc.)
**Validation:**
```sh
./gradlew clean :build-logic:convention:compileKotlin
./gradlew :smartautoclicker:tasks
```
**Traceability:** Blueprint P0-1 (CONNECTIVITY flavor dimension); build-logic/convention/src/main/kotlin/com/buzbuz/gradle/convention/model/KlickrVariants.kt; build-logic/convention/src/main/kotlin/com/buzbuz/gradle/convention/plugins/FlavourConventionPlugin.kt
**Dependencies:** none

#### [x] P0-T04b — Fix getVariantName to emit the CONNECTIVITY segment and verify app-module gating fires  `size: M`
**Slice:** Update getVariantName() so it includes the CONNECTIVITY flavour segment in the order AGP produces (e.g. fDroidLocalDebug), so the substring-based isBuildForVariant() still resolves and every gated block in the app build script keeps firing.
**Definition of Done:**
- [ ] build-logic/convention/src/main/kotlin/com/buzbuz/gradle/convention/extensions/ProductFlavourExt.kt: getVariantName() is changed to compose the variant name including the CONNECTIVITY segment (version flavour + connectivity flavour + buildType, matching AGP's emitted task name such as fDroidLocalDebug / playStoreCloudRelease) rather than only flavour+buildType
- [ ] Each isBuildForVariant() call site in smartautoclicker/build.gradle.kts continues to match the new variant names: the F_DROID+DEBUG applicationIdSuffix block (line 64), the F_DROID per-ABI splits (line 73), the PLAY_STORE assetPacks block (line 94), the F_DROID versionCode loop (line 113), and the PLAY_STORE+RELEASE crashlytics apply (line 136)
- [ ] Verified by build output, not assertion: ./gradlew :smartautoclicker:assembleFDroidLocalDebug produces an applicationId of com.buzbuz.smartautoclicker.debug (proving the F_DROID+DEBUG suffix gate fired) and emits per-ABI + universal split APKs under smartautoclicker/build/outputs/
- [ ] No existing single-flavour call site (e.g. isBuildForVariant(KlickrFlavour.F_DROID) with no buildType) regresses; the fDroid()/playStore() productFlavors accessors that key off flavourName still work
**Validation:**
```sh
./gradlew clean :build-logic:convention:compileKotlin :smartautoclicker:assembleFDroidLocalDebug
```
**Traceability:** Blueprint P0-1 (variant gating preserved under new dimension); Phase stop condition: isBuildForVariant matching after rename; build-logic/convention/src/main/kotlin/com/buzbuz/gradle/convention/extensions/ProductFlavourExt.kt; build-logic/convention/src/main/kotlin/com/buzbuz/gradle/convention/extensions/ProjectExt.kt; smartautoclicker/build.gradle.kts
**Dependencies:** P0-T04a

#### [x] P0-T04c — Verify obfuscation variant resolution against an obfuscated release variant  `size: S`
**Slice:** Confirm ObfuscationPlugin / RandomizeApplicationTask still resolve the intended variant after the dimension rename, by building an obfuscated release variant and checking the randomized/obfuscated application class manifest placeholder resolves.
**Definition of Done:**
- [ ] The shouldRandomize gate in smartautoclicker/build.gradle.kts (isBuildForVariant(KlickrFlavour.F_DROID), line 45) still evaluates correctly for the new fDroidLocal* variant after the P0-T04b getVariantName fix
- [ ] ObfuscationPlugin onVariants block (build-logic/obfuscation/.../ObfuscationPlugin.kt:140) sets manifestPlaceholders for the renamed variant without error; getExtraActualApplicationId() (used at smartautoclicker/build.gradle.kts:58) resolves
- [ ] An obfuscated release variant builds end-to-end: ./gradlew :smartautoclicker:assembleFDroidLocalRelease (with signing -P props) completes and R8/obfuscation runs
- [ ] No NoSuchElementException / unresolved-variant error from RandomizeApplicationTask during the release assemble
**Validation:**
```sh
./gradlew :smartautoclicker:assembleFDroidLocalRelease -PsigningStorePassword="$SIGNING_STORE_PASSWORD" -PsigningKeyAlias="$SIGNING_KEY_ALIAS" -PsigningKeyPassword="$SIGNING_KEY_PASSWORD"
```
**Traceability:** Blueprint P0-1 (obfuscation variant resolution preserved); Phase stop condition: ObfuscationPlugin/RandomizeApplicationTask resolves variant; build-logic/obfuscation/src/main/kotlin/com/buzbuz/gradle/obfuscation/ObfuscationPlugin.kt; build-logic/obfuscation/src/main/kotlin/com/buzbuz/gradle/obfuscation/ProjectExtra.kt; smartautoclicker/build.gradle.kts
**Dependencies:** P0-T04b

#### [x] P0-T04d — Remap CI workflow assemble/bundle task names to the new variant names  `size: S`
**Slice:** Update the assemble*/bundle*/uploadCrashlyticsSymbolFile* Gradle task references in the CI workflows so they name the new connectivity-qualified variants and the release pipeline keeps working.
**Definition of Done:**
- [ ] .github/workflows/release.yml line 41 assembleFDroidRelease is remapped to the new variant (assembleFDroidLocalRelease, matching the FOSS default connectivity flavour)
- [ ] .github/workflows/release-playstore.yml line 42 assemblePlayStoreRelease / bundlePlayStoreRelease / smartautoclicker:uploadCrashlyticsSymbolFilePlayStoreRelease are remapped to the cloud connectivity variant (assemblePlayStoreCloudRelease / bundlePlayStoreCloudRelease / uploadCrashlyticsSymbolFilePlayStoreCloudRelease)
- [ ] .github/workflows/nightly-obfuscation.yml line 56 assembleFDroidRelease is remapped to assembleFDroidLocalRelease
- [ ] No fastlane lane references exist in the repo (confirmed: no Fastfile present), so no fastlane remap is required; this is recorded rather than left implicit
- [ ] execute-tests.yml is left unchanged because it runs testFDroidDebugUnitTest, which is variant-flavour agnostic at the test-task level and still resolves
**Validation:**
```sh
./gradlew :smartautoclicker:assembleFDroidLocalRelease --dry-run -PsigningStorePassword="$SIGNING_STORE_PASSWORD" -PsigningKeyAlias="$SIGNING_KEY_ALIAS" -PsigningKeyPassword="$SIGNING_KEY_PASSWORD"
./gradlew :smartautoclicker:assemblePlayStoreCloudRelease --dry-run -PsigningStorePassword="$SIGNING_STORE_PASSWORD" -PsigningKeyAlias="$SIGNING_KEY_ALIAS" -PsigningKeyPassword="$SIGNING_KEY_PASSWORD"
```
**Traceability:** Phase stop condition: CI assemble*/bundle* task names remapped; .github/workflows/release.yml; .github/workflows/release-playstore.yml; .github/workflows/nightly-obfuscation.yml; .github/workflows/execute-tests.yml
**Dependencies:** P0-T04b

#### [x] P0-T04e — Verify the doubled flavor matrix configures across androidLib modules  `size: S`
**Slice:** Prove the CONNECTIVITY dimension applied by FlavourConventionPlugin to every androidLib module (~25 core/* and feature/* modules) does not break library configuration, by assembling a representative library module's new connectivity variant.
**Definition of Done:**
- [ ] FlavourConventionPlugin androidLib block (lines 45-56) creates the LOCAL/CLOUD flavours for library modules with no manual per-module edit
- [ ] A representative androidLib module assembles its new fDroidLocalDebug variant with no missing-source-set or variant-explosion error: ./gradlew :core:common:base:assembleFDroidLocalDebug succeeds
- [ ] No library module gains a stray cloud-only source set requirement in P0 (LOCAL and CLOUD share the existing main source set; CLOUD-specific code arrives in later phases)
- [ ] The whole-repo fDroid debug unit gate still passes after the dimension is added to all library modules
**Validation:**
```sh
./gradlew :core:common:base:assembleFDroidLocalDebug
./gradlew testFDroidDebugUnitTest
```
**Traceability:** Blueprint P0-1 (flavor dimension applies repo-wide via flavour convention plugin); build-logic/convention/src/main/kotlin/com/buzbuz/gradle/convention/plugins/FlavourConventionPlugin.kt; core/common/base
**Dependencies:** P0-T04a

#### [x] P0-T05 — Add cloudImplementation dependency-scoping extension  `size: S`
**Slice:** Add a cloudImplementation DependencyHandlerScope helper mirroring playStoreImplementation so a dependency edge can be gated into the cloud connectivity variant only.
**Definition of Done:**
- [ ] build-logic/convention/src/main/kotlin/com/buzbuz/gradle/convention/extensions/DependencyHandlerScopeExt.kt gains internal fun DependencyHandlerScope.cloudImplementation(dependency) = add("cloudImplementation", dependency), mirroring the existing playStoreImplementation helper (lines 26-27)
- [ ] build-logic:convention compiles with the new helper
- [ ] A throwaway cloudImplementation(...) edge added to a module resolves into the cloud variant configuration and is ABSENT from that module's fDroidLocalReleaseRuntimeClasspath (verified via ./gradlew :<module>:dependencies --configuration fDroidLocalReleaseRuntimeClasspath); the probe edge is removed before completing the task
- [ ] The helper gates only the dependency edge, not whether a module has a local variant
**Validation:**
```sh
./gradlew :build-logic:convention:compileKotlin
```
**Traceability:** Blueprint P0-2 (cloudImplementation extension); build-logic/convention/src/main/kotlin/com/buzbuz/gradle/convention/extensions/DependencyHandlerScopeExt.kt
**Dependencies:** P0-T04a, P0-T04b

#### [ ] P0-T06 — Point playStore Firebase/FCM config at new TraxIntel project  `size: S`
**Slice:** Replace the playStore google-services.json with the new TraxIntel Firebase/FCM project config so Crashlytics/FCM resolve under the TraxIntel project, keeping the client package_name aligned to com.buzbuz.smartautoclicker.
**Definition of Done:**
- [ ] smartautoclicker/src/playStore/google-services.json is replaced with the TraxIntel Firebase project config and its client package_name is exactly com.buzbuz.smartautoclicker (matching the playStore applicationId; no .debug client is needed because the .debug applicationIdSuffix only applies to the fDroid debug variant per smartautoclicker/build.gradle.kts:64, not to playStore)
- [ ] No old/Klick'r project_id, project_number, or mobilesdk_app_id remains in the file
- [ ] The google-services Gradle plugin processes the file without a package-mismatch error during a PLAY_STORE+RELEASE build (the crashlytics/google-services plugin is only applied under isBuildForVariant(PLAY_STORE, RELEASE) at smartautoclicker/build.gradle.kts:136, so only a release playStore assemble exercises it)
- [ ] playStore CLOUD release assembles with the new config
**Validation:**
```sh
./gradlew :smartautoclicker:assemblePlayStoreCloudRelease -PsigningStorePassword="$SIGNING_STORE_PASSWORD" -PsigningKeyAlias="$SIGNING_KEY_ALIAS" -PsigningKeyPassword="$SIGNING_KEY_PASSWORD"
```
**Traceability:** Blueprint P0 (new Firebase/FCM project); smartautoclicker/src/playStore/google-services.json; smartautoclicker/build.gradle.kts; gradle/libs.versions.toml
**Dependencies:** P0-T04b

#### [ ] P0-T07 — Add Retrofit/OkHttp + WorkManager/Hilt-Work version-catalog entries  `size: S`
**Slice:** Add net-new dependency aliases for Retrofit/OkHttp + kotlinx-serialization-converter and androidx WorkManager + Hilt-Work (runtime, worker, KSP compiler) to the version catalog with versions that resolve cleanly, without wiring them into any module yet.
**Definition of Done:**
- [ ] gradle/libs.versions.toml adds version refs and library aliases for retrofit, okhttp, the retrofit kotlinx-serialization converter, androidx.work:work-runtime-ktx, androidx.hilt:hilt-work, and androidx.hilt:hilt-compiler
- [ ] All new aliases resolve (no unresolved coordinate) and introduce no version conflict with the existing kotlinx-serialization, Hilt, KSP, and AGP versions already in the catalog
- [ ] No existing alias version is changed as a side effect
- [ ] Catalog parses and the build still configures: the whole-repo unit gate compiles
**Validation:**
```sh
./gradlew testFDroidDebugUnitTest
```
**Traceability:** Blueprint P0-3 (Version-catalog entries); gradle/libs.versions.toml
**Dependencies:** none

#### [ ] P0-T08 — Smoke: renamed fDroid local debug build assembles end-to-end  `size: S`
**Slice:** Confirm the rebrand plus the new connectivity dimension and the getVariantName fix produce a renamed FOSS debug variant that fully assembles, exercising NDK native compilation and Hilt/KSP codegen, as the phase exit smoke gate.
**Definition of Done:**
- [ ] ./gradlew clean :smartautoclicker:assembleFDroidLocalDebug completes successfully on a clean checkout after P0-T01, P0-T02, P0-T03, P0-T04a-e, and P0-T07
- [ ] Per-ABI split APKs plus the universal APK are produced under smartautoclicker/build/outputs/ for the renamed FOSS debug variant, and the applicationId carries the .debug suffix
- [ ] Installing the produced APK shows the TraxIntel name and the new launcher icon
- [ ] Whole-repo fDroid debug unit gate and app-module lint variant pass with no new failures introduced by the rename/dimension change
- [ ] P0-T05 (cloudImplementation helper) and P0-T06 (playStore Firebase config) are NOT prerequisites of this smoke gate because the fDroidLocal debug variant neither applies the cloud configuration nor the google-services/crashlytics plugin; they are validated by their own release/compile gates instead
**Validation:**
```sh
./gradlew clean :smartautoclicker:assembleFDroidLocalDebug testFDroidDebugUnitTest :smartautoclicker:lintFDroidLocalDebug
```
**Traceability:** Phase focus: renamed fDroid debug build still assembles; Blueprint P0-1 (2x2x2 matrix assembles, LOCAL default); smartautoclicker/build.gradle.kts; .github/workflows/execute-tests.yml
**Dependencies:** P0-T01, P0-T02, P0-T03, P0-T04b, P0-T04e, P0-T07

## Phase P1 — On-device capture pipeline

**Objective:** Produce structured Observations entirely on-device and cloud-independent: surface the recognized OCR value+confidence from the JNI/native layer up through the processing seam, persist an ObservationEntity via a v21->v22 Room migration on ClickDatabase, wire the new core:observation domain/repo/listener, and prove the chain end-to-end with a DAO-query unit test asserting exactly one persisted Observation per extraction event (plus a visibly-logged Observation). This is the load-bearing capture slice that everything in P2+ uploads.

**Entry criteria:**
- P0 complete: CONNECTIVITY flavor split (P0-1) merged; the four module skeletons core:observation, core:network, core:scheduling, feature:cloud scaffolded and compiling on both flavors (P0-4); version-catalog WorkManager/Hilt-Work aliases present (P0-3); stable deviceId provisioning available (P0-6).
- ClickDatabase is at DATABASE_VERSION=21 (confirmed: const DATABASE_VERSION = 21 in DatabaseInfo.kt) with exported schema 21.json committed under core/smart/database/schemas, and all existing migration unit tests (Migration3to4Tests ... Migration20to21Tests) pass via ./gradlew :core:smart:database:testFDroidDebugUnitTest.
- JDK 21 and the configured Android NDK (28.2.13676358) are installed so native (cpp/CMake) compilation can run for all ABIs.
- Baseline fast gate ./gradlew testFDroidDebugUnitTest is green on the branch point.

**Exit criteria:**
- detectText/detectNumber surface the parsed value and confidence into Kotlin: DetectionResult.recognizedText is populated for text reads and Number/Image/Color numeric results are byte-for-byte unchanged (asserted by tests, NDK green all ABIs).
- ObservationEntity + ObservationDao live in core:smart:database, ClickDatabase entity list includes ObservationEntity and ScenarioDatabase exposes abstract fun observationDao(): ObservationDao, DATABASE_VERSION is 22, the manual Migration21to22 is registered in SmartDatabaseModule.providesClickDatabase(...) addMigrations(...) in di/Hilt.kt (no AutoMigration(21,22)), and 22.json is committed under schemas; a v21->v22 MigrationTestHelper test passes with existing data intact.
- The processing seam is widened: ProcessedConditionResult.Screen carries numberDetected/recognizedText, the four verify* paths in ConditionsVerifier populate them (Color/Image null), and SmartProcessingListener delivers deviceCapturedAtMs; existing implementers still compile.
- core:observation builds an Observation (UUID id, scenarioId, deviceId, deviceCapturedAt, value, valueType in {NUMBER|TEXT|STATE}, confidence Int 0-100, isFulfilled, cropPath?) from each extraction event and persists it via ObservationDao; false reads persist with isFulfilled=false and value=null.
- End-to-end demo is checkable: a DAO-query unit test drives one Number extraction event and asserts exactly one Observation row is present (and a log line is emitted) per event.
- ./gradlew testFDroidDebugUnitTest and ./gradlew :core:smart:database:testFDroidDebugUnitTest are green.

**Validation gates:**
- `./gradlew testFDroidDebugUnitTest (CI fast gate — exact task run by .github/workflows/execute-tests.yml; compiles all Kotlin and runs all module JVM/Robolectric unit tests for fDroid debug; required before PR for any task touching shared processing/listener interface code).`
- `./gradlew :core:smart:database:testFDroidDebugUnitTest (Room entity/DAO/migration tests including the new Migration21to22 test; required after any change to ObservationEntity, ObservationDao, ClickDatabase, ScenarioDatabase, DatabaseInfo, or the migration).`
- `./gradlew :smartautoclicker:assembleFDroidDebug (confirms JNI/CMake native code links across the ABI matrix and the FOSS app packages with the recognized-text JNI change and the new module wiring).`
- New 22.json schema committed under core/smart/database/schemas alongside the DB version bump (verified by the green :core:smart:database test task, which wires the schemas dir as test assets).

**Stop / blocker conditions:**
- JNI change to detectTextNative / toJniResult cannot keep Number/Image/Color numeric arrays byte-for-byte identical, or fails NDK build on any ABI — stop and reassess the jobject (numeric array + UTF-8 jbyteArray) approach before proceeding; the native return-type change (P1-T01) is the highest native risk.
- Migration21to22 corrupts or drops existing scenario/condition data in the MigrationTestHelper v21->v22 test — do not bump DATABASE_VERSION or commit 22.json until the migration is verified data-preserving.
- Any attempt to add a ForeignKey/CASCADE from ObservationEntity to scenario_table, or to store scenarioId as a local Long Identifier — this violates the canonical contract (scenarioId is the cloud scenario UUID String, no FK). Stop and re-read Data contracts.
- valueType emitted as anything other than NUMBER|TEXT|STATE (e.g. COLOR_STATE/DETECTED/ABSENT), or confidence emitted as a fraction rather than Int 0-100 via Math.round — stop; cloud CHECK constraints reject these.
- Adding an AutoMigration(21,22) in addition to the manual migration — contract resolves to a single manual observation-table-only migration; stop if both appear in ClickDatabase autoMigrations + the addMigrations chain.
- An entity column appears on ObservationEntity that is not anchored to the Data-contracts ObservationEntity Room shape (e.g. tenantId) — stop and trace it to the contract or drop it before bumping the schema.

#### [ ] P1-T01 — Native: return recognized OCR text from toJniResult/detectTextNative as a jobject (numeric array + UTF-8 jbyteArray)  `size: M`
**Slice:** Change the native side only: edit toJniResult in jni_detection_result.cpp so the text path returns a jobject carrying the existing 7-element numeric array plus the recognized UTF-8 string as a jbyteArray (sourced from TextRecognizerResult.text), and update detectTextNative in smartautoclicker.cpp to call/return it. Number/Image/Color native paths keep returning the legacy 7-element jdoubleArray unchanged. No Kotlin-side decode/field change in this task.
**Definition of Done:**
- [ ] toJniResult in core/smart/detection/src/main/cpp/jni/jni_detection_result.cpp (currently builds a 7-element array via NewDoubleArray(7), ~L23-44) is extended/supplemented so the text path returns a jobject bundling the unchanged 7-element numeric array plus a UTF-8 jbyteArray of the recognized string (NOT NewStringUTF, so CJK/Arabic bytes are preserved); the recognized text is sourced from TextRecognizerResult.text in text_matcher.cpp.
- [ ] detectTextNative in core/smart/detection/src/main/cpp/smartautoclicker.cpp returns the new jobject for text reads.
- [ ] Number, Image, and Color native detection numeric results remain byte-for-byte identical (still the legacy 7-element jdoubleArray) and the NDK build succeeds for all ABIs.
- [ ] No Kotlin DetectionResult/NativeDetector changes are made in this task (deferred to P1-T01b).
**Validation:**
```sh
./gradlew :smartautoclicker:assembleFDroidDebug
```
**Traceability:** Blueprint P1-1 (JNI surface recognized OCR text); Data contracts: Text verbatim/trim-only normalization; Addendum §2 UTF-8 decode; core/smart/detection/src/main/cpp/jni/jni_detection_result.cpp (toJniResult, NewDoubleArray(7), ~L23-44); core/smart/detection/src/main/cpp/smartautoclicker.cpp (detectTextNative); core/smart/detection/src/main/cpp/detector/matching/text/text_matcher.cpp (TextRecognizerResult.text)
**Dependencies:** none

#### [ ] P1-T01b — Kotlin: decode recognized text via UTF-8 and add DetectionResult.recognizedText  `size: M`
**Slice:** Add recognizedText:String?=null to DetectionResult, update the external detectTextNative signature in NativeDetector.kt to receive the jobject from P1-T01, decode the jbyteArray with Charsets.UTF_8, and populate recognizedText. The legacy DoubleArray.toDetectionResult() numeric path stays unchanged.
**Definition of Done:**
- [ ] DetectionResult gains a recognizedText:String? field defaulting to null; the existing DoubleArray?.toDetectionResult() numeric mapping is unchanged.
- [ ] NativeDetector.kt's detectTextNative (external fun ~L283-291) and its text-read call site (~L149) decode the recognized bytes via String(bytes, Charsets.UTF_8) and populate DetectionResult.recognizedText.
- [ ] A Robolectric/unit test asserts detectText populates recognizedText with a non-null value for a successful text read, and that Number reads leave recognizedText null with the numeric result unchanged.
**Validation:**
```sh
./gradlew :core:smart:detection:testFDroidDebugUnitTest
./gradlew :smartautoclicker:assembleFDroidDebug
```
**Traceability:** Blueprint P1-1 (JNI surface recognized OCR text); Addendum §2 (UTF-8 decode via Charsets.UTF_8, not NewStringUTF); Data contracts: Text verbatim/trim-only normalization; core/smart/detection/src/main/java/com/buzbuz/smartautoclicker/core/detection/NativeDetector.kt (detectTextNative external ~L283-291, text call site ~L149); core/smart/detection/src/main/java/com/buzbuz/smartautoclicker/core/detection/DetectionResult.kt
**Dependencies:** P1-T01

#### [ ] P1-T02 — Feature-flag the recognized-text path (recognizedTextEnabled) with a toggle test  `size: S`
**Slice:** Add a recognizedTextEnabled flag on ImageDetector/NativeDetector that, when off, restores the legacy detectText path (recognizedText null) and, when on, matches P1-T01b behavior, covered by a flag-toggle test.
**Definition of Done:**
- [ ] NativeDetector.kt and ImageDetector.kt expose a recognizedTextEnabled flag; with the flag off, detectText returns recognizedText=null and the legacy numeric path; with the flag on, recognizedText is populated as in P1-T01b.
- [ ] A unit test toggles the flag and asserts both behaviors (off -> null + unchanged numeric array; on -> populated recognizedText).
**Validation:**
```sh
./gradlew :core:smart:detection:testFDroidDebugUnitTest
```
**Traceability:** Blueprint P1-1a (JNI regression guard + feature flag); core/smart/detection/src/main/java/com/buzbuz/smartautoclicker/core/detection/ImageDetector.kt; core/smart/detection/src/main/java/com/buzbuz/smartautoclicker/core/detection/NativeDetector.kt
**Dependencies:** P1-T01b

#### [ ] P1-T02b — CI: add per-ABI native build/test matrix step for cpp/** changes  `size: S`
**Slice:** Add a CI workflow step (matrix over the configured ABIs) to .github/workflows/execute-tests.yml that builds/tests the native layer on any cpp/** change and fails on a native regression. Validation is the workflow run itself (CI-validated), since gradle unit tasks do not exercise the workflow.
**Definition of Done:**
- [ ] A step is added to .github/workflows/execute-tests.yml that runs an NDK build (e.g. :smartautoclicker:assembleFDroidDebug or an explicit per-ABI externalNativeBuild task) across the ABI matrix and is triggered/relevant on cpp/** changes.
- [ ] The step fails the workflow when a native regression is introduced in cpp/** (verified by the CI run on a branch, not by a local gradle unit task).
- [ ] This task is marked CI-validated: its acceptance is the green/red status of the added workflow step on a pushed branch.
**Validation:**
```sh
./gradlew :smartautoclicker:assembleFDroidDebug
```
**Traceability:** Blueprint P1-1a (JNI regression guard, CI ABI matrix); .github/workflows/execute-tests.yml (add cpp ABI matrix step; CI-validated — local gradle assemble only smoke-checks the native build, the workflow gate itself is validated on CI)
**Dependencies:** P1-T01

#### [ ] P1-T03 — Widen processing seam: add numberDetected/recognizedText to ProcessedConditionResult.Screen and populate in verify*  `size: M`
**Slice:** Add nullable numberDetected:Double?=null and recognizedText:String?=null to ProcessedConditionResult.Screen and populate them from DetectionResult in the four verify* methods of ConditionsVerifier (verifyNumberCondition from numberDetected; verifyTextCondition from recognizedText after P1-T01b; verifyColorCondition/verifyImageCondition leave null).
**Definition of Done:**
- [ ] ProcessedConditionResult.Screen (data class Screen) gains numberDetected:Double?=null and recognizedText:String?=null (additive, defaulted) so existing construction sites compile unchanged.
- [ ] verifyNumberCondition (ConditionsVerifier.kt) populates numberDetected from DetectionResult.numberDetected; verifyTextCondition populates recognizedText; verifyColorCondition and verifyImageCondition leave both null.
- [ ] A processing unit test asserts a Number condition result carries the raw numberDetected and a Text condition result carries recognizedText.
- [ ] Existing implementers of SmartProcessingListener.onScreenConditionProcessingCompleted compile without modification.
**Validation:**
```sh
./gradlew :core:smart:processing:testFDroidDebugUnitTest
```
**Traceability:** Blueprint P1-2a (widen processing seam); Capture seam edit (1); Addendum §2 (add numberDetected/recognizedText to Screen); core/smart/processing/src/main/java/com/buzbuz/smartautoclicker/core/processing/domain/model/ProcessedConditionResult.kt (sealed ProcessedConditionResult, data class Screen); core/smart/processing/src/main/java/com/buzbuz/smartautoclicker/core/processing/data/processor/ConditionsVerifier.kt (verifyColorCondition, verifyImageCondition, verifyNumberCondition (reads numberDetected), verifyTextCondition; onScreenConditionProcessingCompleted call sites)
**Dependencies:** P1-T01b

#### [ ] P1-T04 — Deliver capture timestamp through SmartProcessingListener  `size: M`
**Slice:** Add a deviceCapturedAtMs:Long parameter to onScreenConditionProcessingCompleted sourced from ConditionsVerifier's private currentVerificationTsMs (set to System.currentTimeMillis() at verify start) so consumers receive the verification-start capture time.
**Definition of Done:**
- [ ] SmartProcessingListener.onScreenConditionProcessingCompleted carries a deviceCapturedAtMs:Long sourced from currentVerificationTsMs (set to System.currentTimeMillis() at verifyConditions start).
- [ ] ConditionsVerifier passes the current verification timestamp at all four onScreenConditionProcessingCompleted call sites.
- [ ] A processing test asserts the hook fires per condition with a non-zero deviceCapturedAtMs equal to the verification-start time.
- [ ] The default method body keeps existing implementers source-compatible (the interface method remains a defaulted = Unit).
**Validation:**
```sh
./gradlew :core:smart:processing:testFDroidDebugUnitTest
```
**Traceability:** Blueprint P1-2a (deliver capture time via deviceCapturedAtMs listener param); Capture seam edit (3); Data contracts: deviceCapturedAt = System.currentTimeMillis() at verification start, UTC epoch millis; core/smart/processing/src/main/java/com/buzbuz/smartautoclicker/core/processing/domain/SmartProcessingListener.kt (onScreenConditionProcessingCompleted, defaulted = Unit); core/smart/processing/src/main/java/com/buzbuz/smartautoclicker/core/processing/data/processor/ConditionsVerifier.kt (currentVerificationTsMs declared L53, set System.currentTimeMillis() L57)
**Dependencies:** P1-T03

#### [ ] P1-T05 — ObservationEntity + ObservationDao + DATABASE_VERSION 21->22 + OBSERVATION_TABLE const + DAO accessor  `size: M`
**Slice:** Add ObservationEntity and ObservationDao under core:smart:database, add ObservationEntity to the ClickDatabase entities[] list, add abstract fun observationDao(): ObservationDao on ScenarioDatabase, bump DATABASE_VERSION to 22, and add an internal OBSERVATION_TABLE const in DatabaseInfo. No FK to scenario_table. No AutoMigration here.
**Definition of Done:**
- [ ] ObservationEntity has @PrimaryKey id:String (UUID), deviceId:String, scenarioId:String (cloud UUID, no ForeignKey), deviceCapturedAt:Long, value:String?, valueType:String, confidence:Int, isFulfilled:Boolean, cropPath:String?, syncState:String, retryCount:Int=0, with indices on sync_state and scenario_id. (No tenantId column unless the Data-contracts ObservationEntity Room shape explicitly lists it; the domain model omits it, so it is excluded here.)
- [ ] ObservationDao has insert(...) with @Insert(onConflict=IGNORE) returning Long, getUploadBatch(limit) (WHERE sync_state IN ('PENDING','FAILED') ORDER BY device_captured_at ASC), markSyncState, and the reference-counted getPrunableCropPaths(before).
- [ ] DatabaseInfo.kt declares OBSERVATION_TABLE='observation_table' (internal const, added to the @StringDef list) and DATABASE_VERSION=22; ClickDatabase entities[] list includes ObservationEntity::class; ScenarioDatabase declares abstract fun observationDao(): ObservationDao (matching the existing abstract fun scenarioDao()/eventDao()/conditionDao()/actionDao()/countersDao() pattern — NOT an abstract val, and NOT on ClickDatabase).
- [ ] A dup-id insert returns -1 (IGNORE no-op), asserted by a DAO test.
**Validation:**
```sh
./gradlew :core:smart:database:testFDroidDebugUnitTest
```
**Traceability:** Blueprint P1-3 (ObservationEntity + DAO + migration); Critical override (String PK, no FK, no idempotencyKey col); Data contracts: ObservationEntity Room shape; ObservationDao signatures; SyncState enum; core/smart/database/src/main/java/com/buzbuz/smartautoclicker/core/database/DatabaseInfo.kt (DATABASE_VERSION const, @StringDef table consts); core/smart/database/src/main/java/com/buzbuz/smartautoclicker/core/database/ClickDatabase.kt (entities[] list); core/smart/database/src/main/java/com/buzbuz/smartautoclicker/core/database/ScenarioDatabase.kt (abstract fun xDao() accessors); new core/smart/database/src/main/java/.../entity/ObservationEntity.kt, .../dao/ObservationDao.kt
**Dependencies:** P0-4, P0-6

#### [ ] P1-T06 — Manual Migration21to22 (observation table only) + register in SmartDatabaseModule + commit 22.json  `size: S`
**Slice:** Write a single manual Migration21to22 that creates only the observation_table (plus its indices), register it in SmartDatabaseModule.providesClickDatabase(...).addMigrations(...) in di/Hilt.kt (do NOT add an AutoMigration(21,22) to ClickDatabase), and commit the exported 22.json schema.
**Definition of Done:**
- [ ] A Migration21to22 object creates observation_table and its sync_state/scenario_id indices, touching no existing tables.
- [ ] The migration is added to the addMigrations(...) chain in core/smart/database/src/main/java/.../di/Hilt.kt (SmartDatabaseModule.providesClickDatabase, the existing addMigrations chain at ~L54), with no AutoMigration(21,22) added to ClickDatabase's autoMigrations list.
- [ ] A new Migration21to22Tests using MigrationTestHelper migrates a v21 DB with existing scenario/condition data to v22, asserting existing data is intact and observation_table is present (mirroring the Migration20to21Tests pattern).
- [ ] core/smart/database/schemas/.../22.json is committed matching the bumped version.
**Validation:**
```sh
./gradlew :core:smart:database:testFDroidDebugUnitTest --tests "com.buzbuz.smartautoclicker.core.database.migrations.Migration21to22Tests"
```
**Traceability:** Blueprint P1-3/P1-3a; Critical override (single manual migration, observation-table-only, no AutoMigration); Addendum §1/§6 (manual Migration21to22 registered in addMigrations); core/smart/database/src/main/java/com/buzbuz/smartautoclicker/core/database/di/Hilt.kt (SmartDatabaseModule.providesClickDatabase, addMigrations chain ~L54); core/smart/database/src/main/java/com/buzbuz/smartautoclicker/core/database/ClickDatabase.kt (autoMigrations list — confirm no 21->22 added); core/smart/database/schemas (commit 22.json); core/smart/database/src/test/java/.../migrations/Migration20to21Tests.kt (pattern to mirror)
**Dependencies:** P1-T05

#### [ ] P1-T07 — ObservationMapper (Entity<->domain) + Observation domain model in core:observation  `size: S`
**Slice:** Define the Observation domain model in core:observation and an ObservationMapper in core:smart:database mapping ObservationEntity<->Observation (round-trip), without touching Condition mapping or ConditionType.
**Definition of Done:**
- [ ] core:observation defines Observation{ id:String (UUID), scenarioId:String, deviceId:String, deviceCapturedAt:Long, value:String?, valueType, confidence:Int, isFulfilled:Boolean, cropPath:String? } (no tenantId — matches the entity columns persisted in P1-T05).
- [ ] ObservationMapper converts ObservationEntity<->Observation losslessly for the domain fields (round-trip unit test passes), preserving entity-only sync metadata (syncState/retryCount) on the entity side; the domain model does not carry sync metadata.
- [ ] Injected observationDao() resolves from the Hilt graph (compile/wiring check), and the mapper does not modify any existing Condition mapping code.
**Validation:**
```sh
./gradlew :core:observation:testFDroidDebugUnitTest
```
**Traceability:** Blueprint P1-3a (mapper) and P1-2 (domain model); Data contracts: Observation domain field list; id is UUID = wire id = dedup key; core/observation (module scaffolded in P0-4) — domain/Observation.kt, repo; core/smart/database/src/main/java/.../entity/ (ObservationMapper)
**Dependencies:** P0-4, P1-T05, P1-T06

#### [ ] P1-T08 — ObservationValueMapper: stringify and type the captured value  `size: S`
**Slice:** Add ObservationValueMapper in core:observation that maps a detection/processed result to (value,valueType,confidence): Number->NUMBER/Double.toString(); Text->TEXT/verbatim trim-only; State->STATE/'detected'|'not_detected' mirroring isFulfilled; confidence=Math.round(confidenceRate).toInt() clamped 0-100.
**Definition of Done:**
- [ ] Number maps to valueType='NUMBER', value=Double.toString() (locale-independent, no separators); a failed Number read maps to value=null, isFulfilled=false.
- [ ] Text maps to valueType='TEXT', value verbatim with trim only (no case-fold/NFC); empty-after-trim with isFulfilled=true is preserved and distinct from null.
- [ ] State maps to valueType='STATE', value='detected'|'not_detected' mirrored by isFulfilled (never COLOR_STATE/DETECTED/ABSENT).
- [ ] confidence = Math.round(confidenceRate).toInt() clamped to 0-100; unit tests pin the exact string per type including the failed-Number case.
**Validation:**
```sh
./gradlew :core:observation:testFDroidDebugUnitTest
```
**Traceability:** Blueprint P1-2b (value stringification & typing); Critical override (valueType vocabulary NUMBER|TEXT|STATE, confidence Int); Data contracts: Normalization rules; Confidence Int 0-100 round-half-up; core/observation/src/main/java/.../ObservationValueMapper.kt (new)
**Dependencies:** P0-4, P1-T07

#### [ ] P1-T09 — ObservationCaptureListener: build + persist one Observation per extraction event  `size: M`
**Slice:** Implement ObservationCaptureListener (a SmartProcessingListener in core:observation) that, on each onScreenConditionProcessingCompleted, builds an Observation (UUID id, injected deviceId, scenarioId, deviceCapturedAtMs, mapped value/valueType/confidence/isFulfilled via ObservationValueMapper) and persists it via the repository/ObservationDao.
**Definition of Done:**
- [ ] For each extraction event, exactly one Observation is built (fresh UUIDv4 id, deviceId from P0-6, deviceCapturedAt from the listener's deviceCapturedAtMs, value/valueType/confidence/isFulfilled from ObservationValueMapper) and inserted.
- [ ] False reads are persisted with isFulfilled=false and value=null (not dropped).
- [ ] A listener test drives a Number ProcessedConditionResult.Screen and asserts one persisted Observation with value+confidence+timestamp+isFulfilled+deviceId; cropPath is null in this phase.
- [ ] An end-to-end DAO-query test asserts that one extraction event yields exactly one observation_table row (count == 1) and that a log line is emitted, making the 'exactly one row visibly logged and present' exit criterion checkable.
**Validation:**
```sh
./gradlew :core:observation:testFDroidDebugUnitTest
```
**Traceability:** Blueprint P1-2 (domain model + persistence wiring); Architecture: ObservationCaptureListener implements SmartProcessingListener (dependency inversion); Data contracts: false reads persisted; deviceCapturedAt source; Validation note: module-scoped :core:observation gate chosen (matches sibling tasks); this task does not change shared processing/listener-interface code (that is P1-T04), so the whole-repo fast gate is not required.; core/observation/src/main/java/.../ObservationCaptureListener.kt (new), repository; core/smart/processing/src/main/java/.../domain/SmartProcessingListener.kt (interface implemented)
**Dependencies:** P0-4, P0-6, P1-T04, P1-T07, P1-T08

#### [ ] P1-T10 — TrackingScenario -> local Scenario bridge with synthetic sentinels (Number)  `size: M`
**Slice:** Add a core:observation adapter mapping a cloud TrackingScenario (readType, detectionArea:Rect, alphabet, interval) onto a single-ScreenEvent read-only Scenario with one ScreenCondition (Number/Text/Color), no Action, using synthetic sentinels and a temporary non-persisted Identifier.
**Definition of Done:**
- [ ] A TrackingScenario produces a runnable Scenario with one ScreenEvent containing exactly one read-only ScreenCondition and no Action.
- [ ] Synthetic sentinels are used: Number uses comparisonOperation=GREATER_OR_EQUALS at the lowest counterValue (fulfilled when any number is read); Text uses shouldBeDetected=true with a permissive target; the Identifier has databaseId=0L with a derived tempId and is never persisted.
- [ ] A test asserts that a non-LATIN alphabet has no effect on Number recognition (detectNumber uses defaultRecognitionModelId, ignoring alphabet).
- [ ] TrackingScenario JSON deserializes to the documented Scenario shape.
**Validation:**
```sh
./gradlew :core:observation:testFDroidDebugUnitTest
```
**Traceability:** Blueprint P1-4 (TrackingScenario <-> local Scenario bridge); Critical override (no FK, temp Identifier, Number ignores alphabet); Data contracts: TrackingScenario domain; synthetic temp Identifier databaseId=0L, TRACKING_SYNTHETIC_EVENT_ID, priority=0; core/observation/src/main/java/.../TrackingScenarioAdapter.kt (new); core domain model: ScreenEvent, ScreenCondition.Number/Text/Color, Identifier
**Dependencies:** P0-4, P1-T05

## Phase P2 — TraxIntel Cloud backend & ingest (proprietary plane)

**Objective:** Stand up the separate non-GPL Trax Cloud backend that the GPLv3 Android client talks to only over the documented /v1 REST surface: tenant/account bootstrap + argon2id auth, device registry and pairing-code enrollment, idempotent Observation batch ingest, scenario-library storage with per-device assignment, and a minimal web dashboard rendering a per-metric value-over-time chart with a latest-per-device summary. The OpenAPI /v1 contract (not shared Kotlin types) is the load-bearing integration seam; every endpoint and table here is grounded in blueprint Section 11 'Data contracts & schemas (canonical reference)' (TRAXINTEL_MVP_BLUEPRINT.md lines ~3160-3493), which the blueprint preamble (line 9) declares canonical and overriding of inline sketches. Where Section 11 is SILENT (notably account auth: the canonical accounts table at line 3408 has no password_hash and the canonical wire JSON documents no auth endpoint), this phase explicitly elevates the non-canonical 'TraxIntel Cloud' section (lines 1982-2308) as the authority for those shapes and adds the schema column to store the hash, rather than mixing canonical and overridden sources silently.

**Entry criteria:**
- P0-1 connectivity flavor split landed so the client cloud variant exists to integrate against (build-logic/convention/.../model/KlickrVariants.kt in the AutoClicker repo).
- Data contracts frozen: blueprint Section 11 'Data contracts & schemas (canonical reference)' (TRAXINTEL_MVP_BLUEPRINT.md lines ~3160-3493) is authoritative and immutable for this phase; the non-canonical 'TraxIntel Cloud' section (lines ~1982-2308) is the designated authority ONLY for the account-auth surface (register/login + argon2id password_hash) that Section 11 leaves unspecified.
- DETERMINISTIC PRECONDITION (single gate, replaces the prior circular entry/stop pair): the backend stack, repository, and persistence engine are CONFIRMED in writing by the product owner before P2 starts — concretely: (a) a separate non-GPL repo exists (no source shared with AutoClicker; today this repo contains only Python OCR-model scripts under /Users/Sophia/Documents/GitHub/AutoClicker/scripts/ocr-models), (b) the framework is fixed (blueprint suggests Ktor, line ~1960), (c) PostgreSQL 16 (Timescale optional) is provisioned (line ~1962), and (d) the test/lint/build runner triplet is named (e.g. ./gradlew test | ./gradlew check | docker build). If any of (a)-(d) is unconfirmed, P2 does NOT start (see stop conditions). When this precondition holds, every P2 validationCommand's <RUNNER> token is resolved to the chosen runner and every <BACKEND_REPO> token to the confirmed repo path.
- Tenant-isolation scoping pattern decided (row-level tenant_id filtering vs Postgres RLS) — full cross-tenant rejection enforcement is P3-4, but the pattern is fixed here because it changes every query.

**Exit criteria:**
- A versioned OpenAPI 3.x spec describes the FULL /v1 surface the MVP uses — device-facing (devices:enroll, observations:batch, devices/{id}/scenarios, devices/{id}/commands, devices/{id}/commands:ack), account-auth (auth/register, auth/login), scenario CRUD + assignment (POST/PUT/GET /v1/scenarios, GET /v1/scenarios/{id}, POST /v1/scenarios/{id}/assignments), enrollment-code mint (POST /v1/devices/enrollment-codes), device registry (GET /v1/devices, GET /v1/devices/{id}), and dashboard reads (GET /v1/observations, GET /v1/observations/latest, GET /v1/observations/{id}/crop) — and is the single source of DTO truth for both server and the client core:network module.
- An operator can register/login (argon2id), a device can enroll via a pairing code and appear in the device registry, and observations posted to /v1/observations:batch are stored tenant-scoped and idempotently.
- Re-posting an identical observation batch produces zero new stored rows and per-row status 'duplicate' (the idempotency gate, blueprint line ~3172 / ~2840).
- The minimal web dashboard logs in, lists devices (online derived from last_seen_at), renders a per-scenario value-over-time chart sourced from observations.value_number, and shows a latest-value-per-device table for a tenant.
- Every P2 task passes the backend's real test/lint/build gate — the <RUNNER>/<BACKEND_REPO> tokens are resolved (per the deterministic entry precondition) and wired into CI by P2-T11, replacing the placeholder '[Cloud backend — placeholder]' catalog entries.

**Validation gates:**
- `Backend unit/integration test suite green: <BACKEND_REPO> <RUNNER:test> (resolves the placeholder '[Cloud backend — placeholder] Backend unit/integration tests' catalog entry to the stack runner chosen in the entry precondition, e.g. ./gradlew test).`
- `Backend lint/type-check clean: <BACKEND_REPO> <RUNNER:check> (resolves '[Cloud backend — placeholder] Backend lint / type check', e.g. ./gradlew check).`
- Backend build / container image succeeds: <BACKEND_REPO> <RUNNER:build> (resolves '[Cloud backend — placeholder] Backend build / container image', e.g. docker build -t traxintel-backend:dev .).
- OpenAPI spec validates against an OpenAPI 3.x linter and round-trips with the server DTOs, including the confidence integer(0..100) <-> SQL smallint mapping (P2-T01/P2-T02 reconciliation).
- Idempotent-ingest integration test (post same batch twice -> stored count unchanged, second response all 'duplicate') passes — contract gate from blueprint line ~2840.

**Stop / blocker conditions:**
- Deterministic entry precondition unmet: backend stack/repo/Postgres/runner triplet NOT confirmed by product owner — STOP, do not start P2. (This is the single source of the stack-decision gate; the prior circular entry-criterion-4-vs-stop-condition-1 overlap is removed — there is now ONE gate, in entry criteria, and this stop condition only fires if that gate is found unmet at start.)
- Scenario-pull endpoint shape conflict resurfaces: blueprint documents three forms (GET /v1/scenarios/assigned?since=cursor at line ~1355, GET /v1/scenarios with knownRevisions, GET /v1/devices/{deviceId}/scenarios flat list at line ~3387). The canonical Section 11 flat-list form (GET /v1/devices/{deviceId}/scenarios) is authoritative — STOP and escalate if any consumer needs the cursor/revision form.
- Scenario-ASSIGNMENT path conflict: POST /v1/scenarios/{id}/assignments (line ~2235, dashboard flow) vs POST /v1/scenarios/{id}/assign (line ~2837, testing section). The /assignments form is authoritative for P2 (it is the dashboard-flow path the registry/assignment chain at line ~2235 describes); STOP and escalate if a consumer requires /assign.
- Account-auth authority conflict: Section 11 (canonical) has no password_hash column and no auth endpoint, while the non-canonical section (lines ~1994, ~2115, ~2275) defines argon2id + POST /v1/auth/register|login. This phase resolves it by elevating the non-canonical auth section as authority AND adding password_hash via P2-T02b. STOP and escalate ONLY if the product owner rejects elevating the non-canonical auth shapes (then auth must be redesigned before P2-T03).
- No ingest rate-limiting/backpressure design agreed (blueprint gap, line ~3758) — proceed with the BATCH_SIZE cap only and flag per-device rate limiting as deferred to a later phase; STOP only if a hard per-device rate limit is mandated for MVP.

#### [ ] P2-T01 — Author the /v1 OpenAPI 3.x contract covering the full MVP surface  `size: M`
**Slice:** Write a versioned OpenAPI 3.x document covering EVERY /v1 endpoint and DTO the MVP uses, deriving the device-facing shapes verbatim from canonical Section 11 and the account-auth/dashboard-read shapes from the elevated non-canonical 'TraxIntel Cloud' section, so server and client core:network share one authoritative wire shape (no shared Kotlin types).
**Definition of Done:**
- [ ] openapi/traxintel-v1.yaml exists in the backend repo and validates against an OpenAPI 3.x linter.
- [ ] Device-facing paths present with exact request/response schemas from Section 11: POST /v1/devices:enroll, POST /v1/observations:batch, GET /v1/devices/{deviceId}/scenarios, GET /v1/devices/{deviceId}/commands, POST /v1/devices/{deviceId}/commands:ack.
- [ ] Account/dashboard paths present (from the elevated non-canonical auth section): POST /v1/auth/register, POST /v1/auth/login, POST /v1/devices/enrollment-codes, GET /v1/devices, GET /v1/devices/{deviceId}, POST /v1/scenarios, PUT /v1/scenarios/{id}, GET /v1/scenarios, GET /v1/scenarios/{id}, POST /v1/scenarios/{id}/assignments, GET /v1/observations (scenarioId,deviceIds,from,to filters), GET /v1/observations/latest (scenarioId), GET /v1/observations/{id}/crop.
- [ ] Observation per-row request schema matches Section 11 exactly: {id,scenarioId,deviceCapturedAt,value(nullable),valueType,confidence,isFulfilled,hasCrop}; per-row response {id,status:accepted|duplicate,serverReceivedAt?,cropUploadUrl?}; tenantId/deviceId are NOT in the upload body.
- [ ] valueType enum is exactly NUMBER|TEXT|STATE; confidence is typed as integer with minimum:0 maximum:100, and a schema description states it maps to a SQL smallint (the round-trip mapping P2-T02 must honor); all timestamps documented ISO-8601 UTC (ISO_INSTANT, ms precision, trailing Z).
- [ ] Enroll error 410 with {error:'pairing_code_expired'} is documented. The X-TraxIntel-Client header / 426 Upgrade Required version-negotiation story is recorded as a DEFERRED gap (blueprint flags it at line ~3722 as 'the missing client/server version-negotiation story', not a settled contract) — it is NOT a required schema element here.
**Validation:**
```sh
<BACKEND_REPO> npx @redocly/cli lint openapi/traxintel-v1.yaml  (NET-NEW backend gate; concretizes the placeholder '[Cloud backend — placeholder] Backend lint / type check' once <BACKEND_REPO> is resolved per the entry precondition)
```
**Traceability:** Blueprint Section 11 'Wire JSON (Trax Cloud /v1)' (TRAXINTEL_MVP_BLUEPRINT.md lines ~3356-3404); Blueprint elevated non-canonical auth/dashboard endpoints (lines ~2115, ~2153, ~2231-2308); Blueprint architecture note 'OpenAPI contract — not shared Kotlin types — is the integration seam' (line ~377); Client consumer: core/network (to be created in P3, AutoClicker repo)
**Dependencies:** none

#### [ ] P2-T02 — Create the PostgreSQL core schema + migrations (canonical Section 11 tables)  `size: M`
**Slice:** Implement the seven canonical Trax Cloud tables (accounts, tenants, devices, tracking_scenarios, scenario_assignments, observations, device_commands) as ordered migrations exactly matching the Section 11 DDL, including the idempotency and value_number invariants. Does NOT include the auth password_hash column (P2-T02b).
**Definition of Done:**
- [ ] Migration files create all seven tables with the columns, FKs, and CHECKs from the Section 11 DDL (lines ~3408-3493).
- [ ] observations has UNIQUE (tenant_id, id) (constraint uq_obs_idem) and index idx_obs_series (tenant_id, scenario_id, device_id, device_captured_at), plus the unique idx_obs_id supporting the crop-by-id lookup (line ~2097).
- [ ] ck_obs_value_number CHECK enforces value_number non-null iff (value_type='NUMBER' AND is_fulfilled), null otherwise.
- [ ] CHECK constraints present: read_type IN (NUMBER,TEXT,STATE), value_type IN (NUMBER,TEXT,STATE), poll_interval_ms >= 5000, confidence smallint BETWEEN 0 AND 100 (the SQL side of the OpenAPI integer(0..100) mapping declared in P2-T01), device_commands.type IN (START,STOP).
- [ ] scenario_assignments PK is (scenario_id, device_id) with its authoritative tracking boolean; devices has last_seen_at and the coarse derived (non-authoritative) tracking column.
- [ ] A migration test applies all migrations to a fresh DB and asserts every table/constraint/index exists.
**Validation:**
```sh
<BACKEND_REPO> <RUNNER:test-filtered MigrationTest>  (NET-NEW; concretizes '[Cloud backend — placeholder] Backend unit/integration tests'; e.g. ./gradlew test --tests '*MigrationTest*' once the runner is resolved)
```
**Traceability:** Blueprint 'Cloud DB schema (Trax Cloud, DDL-ish)' (TRAXINTEL_MVP_BLUEPRINT.md lines ~3405-3493); Blueprint value_number/last_seen_at explanation (lines ~3487-3493); Blueprint primary store PostgreSQL 16 + Timescale (line ~1962)
**Dependencies:** none

#### [ ] P2-T02b — Add accounts.password_hash column migration (auth schema, elevated authority)  `size: S`
**Slice:** Add the accounts.password_hash column (argon2id, server-computed at registration) that canonical Section 11 omits, sourced from the elevated non-canonical auth section (line ~1994), as a follow-on migration so the auth task (P2-T03) has a grounded column to write into.
**Definition of Done:**
- [ ] A migration adds password_hash text NOT NULL to the accounts table created in P2-T02, matching the non-canonical auth DDL (line ~1994: 'argon2id, computed server-side at registration').
- [ ] The migration is ordered after P2-T02's accounts-table migration and the migration test (P2-T02) is extended to assert the column exists.
- [ ] A code comment / migration note records that this column is sourced from the non-canonical auth section because Section 11 (canonical) omits it, per the phase's auth-authority resolution.
**Validation:**
```sh
<BACKEND_REPO> <RUNNER:test-filtered MigrationTest>  (NET-NEW; e.g. ./gradlew test --tests '*MigrationTest*')
```
**Traceability:** Blueprint non-canonical auth DDL 'password_hash TEXT NOT NULL -- argon2id' (TRAXINTEL_MVP_BLUEPRINT.md line ~1994); Blueprint canonical accounts table that omits the column (line ~3408); Phase auth-authority resolution (entry criterion 2, stop condition 4)
**Dependencies:** P2-T02

#### [ ] P2-T03 — Account auth (argon2id) + tenant bootstrap via register/login  `size: M`
**Slice:** Implement POST /v1/auth/register (bootstrap one Tenant + owner Account in a single transaction, argon2id hash) and POST /v1/auth/login (verify against the stored hash, issue a tenant-scoped Account JWT), using the elevated non-canonical auth endpoints as authority.
**Definition of Done:**
- [ ] POST /v1/auth/register creates a Tenant then an owner Account in one transaction, stores password_hash as argon2id (plaintext never persisted), and returns a tenant-scoped Account JWT; duplicate email -> 409 (line ~2127).
- [ ] POST /v1/auth/login verifies the password against the stored argon2id hash and returns the Account JWT; wrong password -> 401.
- [ ] Each owner Account maps to exactly one Tenant (tenants.owner_account_id FK), created at bootstrap.
- [ ] Every authenticated request resolves the caller's tenant_id from the JWT and rejects missing/invalid tokens (401), using the tenant-isolation scoping pattern fixed in the entry criteria.
- [ ] Both endpoints conform to the schemas defined in P2-T01's OpenAPI (register/login).
- [ ] Unit tests cover: register -> tenant+account+token; duplicate email -> 409; login valid -> token; bad password -> 401; token decodes to the correct tenant_id.
**Validation:**
```sh
<BACKEND_REPO> <RUNNER:test-filtered AuthTest>  (NET-NEW; e.g. ./gradlew test --tests '*AuthTest*')
```
**Traceability:** Blueprint non-canonical 'POST /v1/auth/register' bootstrap (TRAXINTEL_MVP_BLUEPRINT.md lines ~2115-2127); Blueprint 'POST /v1/auth/login' (lines ~2275-2282); Blueprint 'Tenant-scoped auth' user story 9 (line ~57); Phase auth-authority resolution (entry criterion 2)
**Dependencies:** P2-T01, P2-T02, P2-T02b

#### [ ] P2-T04 — Enrollment-code mint + device registry + pairing-code enrollment  `size: M`
**Slice:** Implement POST /v1/devices/enrollment-codes (account-authed mint of a single-use short-lived code) and POST /v1/devices:enroll (a valid code mints a device row + device-scoped token), plus the registry reads GET /v1/devices and GET /v1/devices/{deviceId}.
**Definition of Done:**
- [ ] POST /v1/devices/enrollment-codes (account-authed) returns {code,expiresAt} bound to the caller's Tenant (line ~2153).
- [ ] POST /v1/devices:enroll with a valid code creates a devices row bound to the code's Tenant and returns {deviceId,tenantId,deviceToken,tokenExpiresAt} per the OpenAPI schema.
- [ ] The code is single-use: a second enroll with the same code is rejected; an expired code returns 410 with {error:'pairing_code_expired'}.
- [ ] The minted deviceToken is device-scoped (authorizes only that device's data) and decodes to (tenant_id, device_id).
- [ ] model/osVersion/appVersion from the enroll request are persisted; enrolled_at set; GET /v1/devices and GET /v1/devices/{deviceId} return the tenant's devices (registry).
- [ ] Integration test: mint code -> enroll -> device appears in GET /v1/devices; reuse code -> rejected; expired code -> 410.
**Validation:**
```sh
<BACKEND_REPO> <RUNNER:test-filtered EnrollTest>  (NET-NEW; e.g. ./gradlew test --tests '*EnrollTest*')
```
**Traceability:** Blueprint Enrollment wire JSON (TRAXINTEL_MVP_BLUEPRINT.md lines ~3356-3364); Blueprint enrollment-code mint 'POST /v1/devices/enrollment-codes' (line ~2153); Blueprint registry reads GET /v1/devices (line ~2282); Blueprint devices DDL (lines ~3420-3429); Client consumer P3-1 enrollment UI (feature:cloud, AutoClicker repo)
**Dependencies:** P2-T01, P2-T02, P2-T03

#### [ ] P2-T05 — Token-scoped idempotent Observation batch ingest (ON CONFLICT + per-row status)  `size: M`
**Slice:** Implement the core of POST /v1/observations:batch (device token): derive tenant/device from the token (never the body), upsert each row ON CONFLICT (tenant_id,id) DO NOTHING, and return per-row {id,status:accepted|duplicate}.
**Definition of Done:**
- [ ] tenant_id and device_id are taken from the device token, never from the body; rows are stored tenant-scoped using the fixed scoping pattern.
- [ ] Each row upserts with ON CONFLICT (tenant_id, id) DO NOTHING and returns {id,status:'accepted'|'duplicate',serverReceivedAt?}.
- [ ] Re-posting an identical batch stores zero new rows and returns every row as 'duplicate' (the idempotency gate).
- [ ] An observation whose scenarioId is not currently assigned to the device is rejected 409 (line ~2235).
- [ ] Integration test posts a batch, re-posts it, and asserts stored count unchanged and all-duplicate on the second call (the line ~2840 contract gate).
**Validation:**
```sh
<BACKEND_REPO> <RUNNER:test-filtered ObservationIngestTest>  (NET-NEW; e.g. ./gradlew test --tests '*ObservationIngestTest*')
```
**Traceability:** Blueprint Observation batch upload wire JSON (TRAXINTEL_MVP_BLUEPRINT.md lines ~3365-3386); Blueprint idempotency linchpin / ON CONFLICT DO NOTHING (line ~3172); Blueprint idempotent-ingest API test gate (line ~2840); Blueprint unassigned-scenario 409 rule (line ~2235)
**Dependencies:** P2-T01, P2-T02, P2-T04

#### [ ] P2-T06 — value_number materialization + ck_obs_value_number invariant on ingest  `size: S`
**Slice:** Materialize observations.value_number exactly for fulfilled NUMBER rows during batch ingest (parsed from value) and leave it null otherwise, satisfying ck_obs_value_number; ensure failed NUMBER reads are stored, not dropped.
**Definition of Done:**
- [ ] On ingest, value_number is parsed from value and set exactly when value_type='NUMBER' AND is_fulfilled; null for TEXT, STATE, and failed NUMBER reads, satisfying ck_obs_value_number.
- [ ] value (text) remains the canonical truth; value_number is a query-performance derivative that can never disagree (line ~3487).
- [ ] Failed NUMBER reads store value=null, is_fulfilled=false and are NOT dropped (low-confidence-not-dropped rule, line ~1148).
- [ ] Integration test posts a mixed batch (fulfilled NUMBER + failed NUMBER + TEXT + STATE) and asserts value_number is non-null only for the fulfilled NUMBER row and the CHECK never trips.
**Validation:**
```sh
<BACKEND_REPO> <RUNNER:test-filtered ValueNumberTest>  (NET-NEW; e.g. ./gradlew test --tests '*ValueNumberTest*')
```
**Traceability:** Blueprint observations DDL + value_number invariant (TRAXINTEL_MVP_BLUEPRINT.md lines ~3454-3489); Blueprint low-confidence-not-dropped capture rule (line ~1148)
**Dependencies:** P2-T05

#### [ ] P2-T07 — Batch-size cap + signed cropUploadUrl issuance on ingest  `size: S`
**Slice:** Enforce the BATCH_SIZE cap on /v1/observations:batch and issue a signed cropUploadUrl for rows with hasCrop=true (and none for hasCrop=false), completing the ingest endpoint's remaining concerns.
**Definition of Done:**
- [ ] Batches over the cap (BATCH_SIZE, default 100) are rejected/bounded with a clear error.
- [ ] Rows with hasCrop=true get a signed cropUploadUrl in the per-row response; hasCrop=false rows do not.
- [ ] The crop PUT/confirm follow-on (crop_url filled server-side after PUT, line ~2227) is wired so an accepted row with a crop can complete; per-device rate limiting is explicitly DEFERRED (stop condition 5).
- [ ] Integration test: a batch at cap+1 is rejected; a row with hasCrop=true returns a non-empty cropUploadUrl and a hasCrop=false row returns none.
**Validation:**
```sh
<BACKEND_REPO> <RUNNER:test-filtered CropUploadTest>  (NET-NEW; e.g. ./gradlew test --tests '*CropUploadTest*')
```
**Traceability:** Blueprint Observation batch upload cropUploadUrl semantics (TRAXINTEL_MVP_BLUEPRINT.md line ~3386); Blueprint crop PUT/confirm lifecycle (lines ~2227-2229); Blueprint rate-limit gap deferral (line ~3758)
**Dependencies:** P2-T05

#### [ ] P2-T08 — Scenario library CRUD + per-device assignment + device-facing pull  `size: M`
**Slice:** Implement tracking_scenarios CRUD (POST/PUT/GET /v1/scenarios, GET /v1/scenarios/{id}), the authoritative assignment write POST /v1/scenarios/{id}/assignments, and the device-facing GET /v1/devices/{deviceId}/scenarios that projects scenario_assignments.tracking into the flat scenarios array.
**Definition of Done:**
- [ ] An operator can create/read/update a TrackingScenario (name, readType, detectionArea Rect, alphabet, matchText, color, threshold, pollIntervalMs>=5000, cropCaptureEnabled) scoped to their Tenant via POST/PUT/GET /v1/scenarios + GET /v1/scenarios/{id}.
- [ ] POST /v1/scenarios/{id}/assignments (the authoritative assignment path per stop condition 3, NOT /assign) writes/updates a scenario_assignments row keyed (scenario_id, device_id) with its tracking flag (authoritative).
- [ ] GET /v1/devices/{deviceId}/scenarios (device token) returns the flat {scenarios:[...]} shape from Section 11, with tracking projected from scenario_assignments.tracking (NOT the cursor/knownRevisions variants, stop condition 2).
- [ ] pollIntervalMs < 5000 is rejected at authoring time per the CHECK with a validation error.
- [ ] All endpoints conform to P2-T01's OpenAPI schemas.
- [ ] Integration test: author scenario, assign to a device via /assignments, pull as that device -> scenario present with correct tracking flag.
**Validation:**
```sh
<BACKEND_REPO> <RUNNER:test-filtered ScenarioTest>  (NET-NEW; e.g. ./gradlew test --tests '*ScenarioTest*')
```
**Traceability:** Blueprint Scenario pull wire JSON (TRAXINTEL_MVP_BLUEPRINT.md lines ~3387-3395); Blueprint scenario-id lifecycle create/assign/pull chain (line ~2235); Blueprint tracking_scenarios + scenario_assignments DDL (lines ~3431-3452); Blueprint scenario-pull/assignment shape resolution (stop conditions 2,3)
**Dependencies:** P2-T01, P2-T02, P2-T03

#### [ ] P2-T09 — Dashboard: login + tenant-scoped device list with online derivation  `size: M`
**Slice:** Build the dashboard authentication shell and the device list: operator logs in with the Account token and sees only their Tenant's devices, with an online dot derived from last_seen_at and tracking from devices.tracking.
**Definition of Done:**
- [ ] Operator logs in via POST /v1/auth/login and the dashboard establishes tenant context; only the caller's Tenant devices are shown (GET /v1/devices).
- [ ] An online dot is derived from last_seen_at (green if > now()-90s, line ~2305); per-device tracking reflects devices.tracking.
- [ ] The dashboard calls only the documented /v1 surface defined in P2-T01.
- [ ] An end-to-end smoke test logs in for a seeded tenant and asserts GET /v1/devices returns that tenant's devices only.
**Validation:**
```sh
<BACKEND_REPO>/dashboard <RUNNER:dashboard-test DeviceList>  (NET-NEW; e.g. npm test -- DeviceList)
```
**Traceability:** Blueprint dashboard Devices view (TRAXINTEL_MVP_BLUEPRINT.md line ~2305); Blueprint user story 6 / device list (lines ~54, ~68); Blueprint GET /v1/devices read endpoint (line ~2282)
**Dependencies:** P2-T01, P2-T03, P2-T04

#### [ ] P2-T10 — Dashboard: scenario-filtered time-series chart with low-confidence styling  `size: M`
**Slice:** Build the per-scenario value-over-time chart sourced from observations.value_number via GET /v1/observations, with device multi-select and time-range filters and visually distinguished low-confidence points.
**Definition of Done:**
- [ ] Selecting a scenario renders a value-over-time line/scatter chart from GET /v1/observations?scenarioId=&deviceIds=&from=&to= (one series per device), backed server-side by observations.value_number via idx_obs_series.
- [ ] Filters: device multi-select and time range work against the query endpoint.
- [ ] Low-confidence points are visually distinguished (e.g. hollow/desaturated below a threshold) without being dropped (acceptance gate, lines ~90, ~2307).
- [ ] TEXT/STATE values render as a stepped categorical track per the dashboard spec (line ~2307).
- [ ] An end-to-end smoke test logs in and asserts GET /v1/observations returns series data for a seeded tenant/scenario.
**Validation:**
```sh
<BACKEND_REPO>/dashboard <RUNNER:dashboard-test Chart>  (NET-NEW; e.g. npm test -- Chart)
```
**Traceability:** Blueprint Observation time-series chart spec (TRAXINTEL_MVP_BLUEPRINT.md line ~2307); Blueprint GET /v1/observations query endpoint (line ~2282); Blueprint acceptance 'distinguishes low-confidence points' (line ~90); Blueprint value_number as dashboard query source (lines ~3487-3489)
**Dependencies:** P2-T01, P2-T06, P2-T08, P2-T09

#### [ ] P2-T11 — Dashboard: latest-value-per-device table + crop viewer  `size: S`
**Slice:** Add the latest-value-per-device summary table (GET /v1/observations/latest) beside the chart and the crop viewer that opens an observation's cropped PNG via the presigned GET /v1/observations/{id}/crop URL.
**Definition of Done:**
- [ ] A latest-value-per-device summary table shows the most recent Observation (value + confidence + age) per device for the selected scenario, from GET /v1/observations/latest?scenarioId= (line ~2307).
- [ ] Clicking an observation point opens its crop via the presigned GET /v1/observations/{id}/crop URL (tenant-scoped lookup via idx_obs_id, line ~2308).
- [ ] Both endpoints conform to P2-T01's OpenAPI schemas.
- [ ] An end-to-end smoke test asserts GET /v1/observations/latest returns one row per device for a seeded tenant/scenario.
**Validation:**
```sh
<BACKEND_REPO>/dashboard <RUNNER:dashboard-test Latest>  (NET-NEW; e.g. npm test -- Latest)
```
**Traceability:** Blueprint latest-value table + crop viewer (TRAXINTEL_MVP_BLUEPRINT.md lines ~2307-2308); Blueprint GET /v1/observations/latest and /crop read endpoints (line ~2282); Blueprint acceptance 'shows latest-per-device' (line ~90)
**Dependencies:** P2-T01, P2-T08, P2-T10

#### [ ] P2-T12 — Backend CI gate + container build wiring (concretize placeholder catalog entries)  `size: S`
**Slice:** Wire the backend repo's test, lint/type-check, OpenAPI validation, and container-image build into CI so the three '[Cloud backend — placeholder]' catalog entries are replaced with the resolved <RUNNER>/<BACKEND_REPO> commands and become runnable gates for every subsequent backend change.
**Definition of Done:**
- [ ] A CI workflow in the backend repo runs the test suite, the lint/type-check, and OpenAPI validation (P2-T01) on every push/PR using the runners resolved in the entry precondition.
- [ ] The container image (or deployable bundle) builds successfully in CI and is the artifact validated before deploy.
- [ ] The three placeholder catalog entries ('[Cloud backend — placeholder] Backend unit/integration tests | lint/type check | build/container image') are concretely replaced with the chosen runner commands and documented in the backend README, so every P2 task's <RUNNER>/<BACKEND_REPO> tokens resolve to real commands.
- [ ] CI fails if migrations don't apply cleanly or the idempotent-ingest integration test (P2-T05) regresses.
**Validation:**
```sh
<BACKEND_REPO> <RUNNER:build>  (NET-NEW; concretizes '[Cloud backend — placeholder] Backend build / container image'; e.g. docker build -t traxintel-backend:dev .)
```
**Traceability:** Blueprint 'backend is a separate repository' (TRAXINTEL_MVP_BLUEPRINT.md line ~377); Blueprint Ktor/containerize recommendation (line ~1960); The three '[Cloud backend — placeholder]' entries in the validation command catalog provided to this phase
**Dependencies:** P2-T01, P2-T02, P2-T05

## Phase P3 — Device connectivity & sync (core:network)

**Objective:** Stand up the cloud-gated core:network module (Retrofit/OkHttp/kotlinx.serialization), device enrollment with persisted token storage, a WorkManager batched offline-tolerant observation uploader driven by a SyncState machine on the Observation entity, and the Hilt+WorkManager wiring — ending in a single device->cloud end-to-end observation upload (the thinnest full vertical slice). All variant task names use the two-dimension CONNECTIVITY x VERSION shape introduced in P0 (e.g. FDroidLocal / PlayStoreCloud).

**Entry criteria:**
- P0 complete: CONNECTIVITY flavor dimension (LOCAL default / CLOUD) composing with the existing VERSION dimension (fDroid/playStore), and the cloudImplementation dependency-scoping extension exist; four module skeletons (core:observation, core:network, core:scheduling, feature:cloud) scaffolded and all four flavor combinations compile (P0-1, P0-2, P0-4). Two-dimension variant naming is in effect repo-wide (e.g. testFDroidLocalDebugUnitTest, assemblePlayStoreCloudDebug).
- P0-3 version-catalog entries for Retrofit/OkHttp/kotlinx-serialization-converter and androidx.work + androidx.hilt:hilt-work + hilt-compiler resolve in gradle/libs.versions.toml.
- P0-6 stable client deviceId provisioning (DeviceIdentityDataSource in core:observation) exists and is injectable.
- P1 complete on-device: ObservationEntity + ObservationDao + migration 21->22 registered (P1-3/P1-3a) with syncState:String and retryCount columns, schema JSON committed under core/smart/database/schemas; Observation domain model + ObservationValueMapper produce persisted Observations (P1-2/P1-2b); TrackingScenario<->Scenario bridge (P1-4) and the DetectionTrigger burst path (P1-5b) work. DatabaseInfo.DATABASE_VERSION = 22 post-P1.
- core:network and feature:cloud are wired into the app ONLY via cloudImplementation(project(...)); the LOCAL runtime classpath (e.g. fDroidLocalReleaseRuntimeClasspath) links neither.
- The cloud /v1 backend (or a stub/mock honoring the OpenAPI contract: POST /v1/devices:enroll, POST /v1/observations:batch) is reachable for end-to-end validation.

**Exit criteria:**
- A CLOUD-flavor build performs device enrollment via pairing code against POST /v1/devices:enroll (canonical colon route), sending the {pairingCode, model, osVersion, appVersion} request body, persisting the returned deviceToken/tenantId/tokenExpiresAt and reconciling the server-returned deviceId with the client deviceId; an expired code (410 pairing_code_expired) is rejected with a clear terminal error and no persistence.
- Device token persists across process death in DataStore and is attached as a device-scoped Authorization: Bearer header plus the X-TraxIntel-Client: <appVersion>/<dbVersion>/<protocol=1> header on every /v1 request (omitting the bearer for the enroll call).
- A WorkManager @HiltWorker uploader batches PENDING/FAILED Observations (BATCH_SIZE=100, ordered by device_captured_at ASC) to POST /v1/observations:batch, advances SyncState PENDING->UPLOADING->{SYNCED|FAILED} (and FAILED->UPLOADING on re-run), treats accepted|duplicate as SYNCED (zero server dupes on retry), backs off on retryable errors (5xx/IO/timeout/429), and on terminal 401 revoked stops, clears the token, and requires re-pair.
- End-to-end: one Observation captured on-device uploads to the cloud, lands SYNCED locally, and the server accepts it exactly once even across a forced retry. Sync is a no-op unless both KEY_ACCOUNT_BOUND and cloud_sync_enabled (default OFF) are true.
- The LOCAL flavor (e.g. fDroidLocalRelease / assembleFDroidLocalDebug) still assembles with no androidx.work / Retrofit / OkHttp linked, verified by a runtime-classpath inspection; the CLOUD flavor assembles and Hilt KSP is clean.
- testFDroidLocalDebugUnitTest and testPlayStoreCloudDebugUnitTest both pass.

**Validation gates:**
- `./gradlew testFDroidLocalDebugUnitTest`
- `./gradlew testPlayStoreCloudDebugUnitTest`
- `./gradlew :smartautoclicker:assembleFDroidLocalDebug`
- `./gradlew :smartautoclicker:assemblePlayStoreCloudDebug`
- `./gradlew :smartautoclicker:lintFDroidLocalDebug`
- `./gradlew :smartautoclicker:dependencies --configuration fDroidLocalReleaseRuntimeClasspath`

**Stop / blocker conditions:**
- GPLv3 boundary breach: any androidx.work, Retrofit, OkHttp, or cloud DTO type leaks into the LOCAL build (fDroidLocalReleaseRuntimeClasspath links core:network or feature:cloud) — stop and re-gate the dependency edge before proceeding.
- core:network references GPL domain entities (Observation/Scenario/Condition) instead of owning its own DTOs/serialization — stop; the arm's-length /v1 boundary is violated.
- The /v1 contract cannot be honored by the available backend/stub (enroll or observations:batch shape diverges from the canonical Data-contracts wire JSON, line 3356/3359) — stop and reconcile the OpenAPI contract before coding against it. If the §3 slash form POST /v1/devices/enroll (line 3705) and the canonical colon form POST /v1/devices:enroll (line 3356) disagree, the colon form is authoritative.
- Idempotency cannot be guaranteed (server dedups on (tenant_id, id) but client mutates Observation.id between attempts, or a non-UUID id is sent) — stop; duplicate-free upload is the contract gate.
- Token-refresh scope creep: anyone attempts to add token refresh — MVP has no refresh; expiry/401/410 => re-pair only. Stop and keep scope to enroll + persist + re-pair.
- DB schema change required beyond what P1-3 already shipped (e.g. a new column) — stop; coordinate a migration bump + committed schema JSON under core/smart/database/schemas rather than editing in place.
- A required variant task does not resolve (e.g. a single-dimension name like assemblePlayStoreDebug is used) — stop; all P3 tasks must use two-dimension CONNECTIVITY x VERSION variant names.

#### [ ] P3-T01 — Add Retrofit/OkHttp/kotlinx.serialization client + /v1 ApiService in core:network  `size: M`
**Slice:** In the cloud-only core:network module, configure an OkHttp/Retrofit client with a kotlinx.serialization converter and declare the typed /v1 Retrofit ApiService (devices:enroll, observations:batch) plus request/response DTOs owned by core:network (never the GPL domain entities).
**Definition of Done:**
- [ ] A Retrofit instance is built with an OkHttp client and kotlinx-serialization-converter (aliases from P0-3 / gradle/libs.versions.toml) and a configurable /v1 base URL.
- [ ] The enroll route is pinned to the canonical colon form POST /v1/devices:enroll (Wire JSON line 3356, the Data-contracts authority); the blueprint §3 slash form POST /v1/devices/enroll (line 3705) is a known inconsistency and is explicitly NOT used.
- [ ] A Retrofit ApiService interface declares suspend functions for POST /v1/devices:enroll and POST /v1/observations:batch matching the canonical wire JSON. Enroll request body = {pairingCode, model, osVersion, appVersion} (line 3359); enroll response = {deviceId, tenantId, deviceToken, tokenExpiresAt}. Batch request per-row = {id, scenarioId, deviceCapturedAt, value, valueType, confidence:Int, isFulfilled, hasCrop}; batch response per-row = {id, status:accepted|duplicate, serverReceivedAt?, cropUploadUrl?}.
- [ ] All DTOs are @Serializable types defined inside core:network; no GPL domain entity (Observation/Scenario/Condition) is imported or serialized.
- [ ] Timestamps serialize as ISO-8601 UTC via the pinned DateTimeFormatter.ISO_INSTANT convention; tenantId/deviceId are NOT placed in the batch body.
- [ ] A JVM unit test round-trips an enroll request+response and a batch request+response payload through the serializer asserting field names/shapes (including the {model, osVersion, appVersion} enroll-request fields).
**Validation:**
```sh
./gradlew :core:network:testPlayStoreCloudDebugUnitTest
./gradlew :smartautoclicker:assemblePlayStoreCloudDebug
```
**Traceability:** Blueprint P2-1 (core:network REST/auth client); Data contracts: Wire /v1 JSON (Trax Cloud) line 3356/3359; Architecture: GPLv3 boundary — core:network owns its own DTOs/serialization; gradle/libs.versions.toml; core/network/ (scaffolded in P0-4)
**Dependencies:** P0-3, P0-4

#### [ ] P3-T02 — Add device token store with bearer + X-TraxIntel-Client OkHttp interceptors  `size: M`
**Slice:** Add a DataStore-backed device token store (token, tenantId, deviceId, expiry) in core:network and OkHttp interceptors that attach the device-scoped bearer header and the X-TraxIntel-Client header to every /v1 request.
**Definition of Done:**
- [ ] A token store persists deviceToken + tenantId + tokenExpiresAt in a DataStore prefs file (mirroring the pattern in core/common/settings/src/main/java/com/buzbuz/smartautoclicker/core/settings/engine/data/SettingsDataSource.kt), exposing read/write + a Flow; values survive process death.
- [ ] An OkHttp interceptor injects Authorization: Bearer <deviceToken> on /v1 requests when a token is present and omits it for the enroll call.
- [ ] A second interceptor sets X-TraxIntel-Client: <appVersion>/<dbVersion>/<protocol> on every request, where <appVersion> = versionName in smartautoclicker/build.gradle.kts defaultConfig (currently 4.0.0-beta02), <dbVersion> = DatabaseInfo.DATABASE_VERSION (core/smart/database/src/main/java/com/buzbuz/smartautoclicker/core/database/DatabaseInfo.kt, = 22 post-P1), and <protocol> = literal 1 (blueprint §5 line 3722). These inputs are injected, not hard-coded inline at the call site.
- [ ] Tokens are treated as revocable, not secret (no extra encryption requirement beyond DataStore); no token-refresh logic exists.
- [ ] Unit test: after writing a token, a built Request carries the Bearer + X-TraxIntel-Client headers with the correct appVersion/dbVersion/protocol values; a fresh store instance reads back the persisted token (process-death simulation).
**Validation:**
```sh
./gradlew :core:network:testPlayStoreCloudDebugUnitTest
```
**Traceability:** Blueprint P2-1 (token store; device-scoped bearer header); Blueprint §5 line 3722 (X-TraxIntel-Client: <appVersion>/<dbVersion>/<protocol=1>); core/common/settings/.../engine/data/SettingsDataSource.kt (DataStore pattern reference); core/smart/database/.../DatabaseInfo.kt (DATABASE_VERSION); smartautoclicker/build.gradle.kts (versionName); core/network/ (P0-4)
**Dependencies:** P3-T01

#### [ ] P3-T03 — Implement device enrollment via pairing code with server-id reconciliation  `size: M`
**Slice:** Implement enrollment that POSTs a pairing code (plus device descriptor) to /v1/devices:enroll, persists the returned token/tenantId, reconciles the server deviceId with the client deviceId from P0-6, sets KEY_ACCOUNT_BOUND, and surfaces a clear terminal rejection on 410 pairing_code_expired.
**Definition of Done:**
- [ ] A repository/use-case in core:network calls POST /v1/devices:enroll with {pairingCode, model, osVersion, appVersion} and on success persists deviceToken/tenantId/tokenExpiresAt via the P3-T02 token store.
- [ ] The server-returned deviceId is reconciled with the client deviceId (DeviceIdentityDataSource, P0-6) so the persisted identity is the server-reconciled one.
- [ ] 410 pairing_code_expired (and invalid code) maps to a distinct terminal error result (no retry, no backoff), surfaced as a clear failure to callers, persisting nothing.
- [ ] On enrollment success KEY_ACCOUNT_BOUND is set so downstream sync can gate on it.
- [ ] Unit tests against a mocked ApiService cover: successful enroll persists token + reconciled id + sets account-bound; 410 returns the terminal expired error and persists nothing.
**Validation:**
```sh
./gradlew :core:network:testPlayStoreCloudDebugUnitTest
./gradlew testPlayStoreCloudDebugUnitTest
```
**Traceability:** Blueprint P3-1 (device enrollment via pairing code); Data contracts: POST /v1/devices:enroll line 3356; request body line 3359; 410 Gone pairing_code_expired; server-reconciled deviceId; Addendum: terminal vs retryable errors (410 expired => re-pair); core:observation DeviceIdentityDataSource (P0-6); core/common/settings (KEY_ACCOUNT_BOUND)
**Dependencies:** P3-T02, P0-6

#### [ ] P3-T04 — Add SyncState transition DAO queries + repository (getUploadBatch/markSyncState)  `size: M`
**Slice:** Expose the SyncState machine over the existing ObservationEntity: add/confirm DAO queries getUploadBatch(limit) and markSyncState(ids,state,retry) and a core:observation repository that drives PENDING->UPLOADING->{SYNCED|FAILED} and FAILED->UPLOADING, observable as a Flow.
**Definition of Done:**
- [ ] ObservationDao exposes getUploadBatch(limit) returning rows WHERE sync_state IN ('PENDING','FAILED') ORDER BY device_captured_at ASC LIMIT :limit, and markSyncState(ids, state, retry) updating sync_state + retryCount.
- [ ] A core:observation repository method exposes the upload batch and applies legal SyncState transitions only (PENDING->UPLOADING->SYNCED|FAILED, FAILED->UPLOADING); SYNCED is terminal (append-only, no delete-on-sync).
- [ ] syncState is observable as a Flow for UI/heartbeat consumers.
- [ ] Reuses the existing ObservationEntity/ObservationDao in core:smart:database from P1-3 — no schema column change, no DB version bump.
- [ ] DAO unit tests assert getUploadBatch ordering + filter, and that markSyncState updates state and increments retryCount.
**Validation:**
```sh
./gradlew :core:smart:database:testFDroidLocalDebugUnitTest
./gradlew :core:observation:testFDroidLocalDebugUnitTest
```
**Traceability:** Blueprint P2-2 (syncState transitions; getUploadBatch/markSyncState); Data contracts: SyncState enum + ObservationDao query specs; Addendum §1 line 3691 / SyncState section line 3201 (string state machine, supersedes the Int 0/1 model at lines 175/3052); core/smart/database/.../dao/ObservationDao.kt (P1-3); core:observation repository (P1-2)
**Dependencies:** P1-3, P1-2

#### [ ] P3-T05 — Wire HiltWorkerFactory + per-flavor Application for WorkManager (cloud-only)  `size: M`
**Slice:** Add the WorkManager/Hilt-Work infrastructure using per-flavor Application classes so androidx.work stays out of the LOCAL build, with the CLOUD Application providing the HiltWorkerFactory and a no-op @HiltWorker that enqueues and resolves an injected dependency. Add a checkable runtime-classpath assertion that the LOCAL build links no androidx.work/Retrofit/OkHttp.
**Definition of Done:**
- [ ] A BaseSmartAutoClickerApplication lives in src/main (refactored from the existing smartautoclicker/src/main/java/com/buzbuz/smartautoclicker/application/SmartAutoClickerApplication.kt); a LocalApplication (src/local) carries no WorkManager; a CloudApplication (src/cloud) implements Configuration.Provider and supplies the HiltWorkerFactory.
- [ ] The src/cloud manifest removes the default WorkManagerInitializer startup node so on-demand initialization is used.
- [ ] A no-op @HiltWorker CoroutineWorker in core:scheduling enqueues, runs, and resolves an injected dependency under the CLOUD flavor; the foreground-service lifecycle is unaffected.
- [ ] A concrete, checkable validation confirms the LOCAL runtime classpath links no androidx.work/Retrofit/OkHttp — running ./gradlew :smartautoclicker:dependencies --configuration fDroidLocalReleaseRuntimeClasspath produces output with no androidx.work, retrofit, or okhttp coordinate (asserted, not just assembled).
- [ ] Hilt KSP codegen is clean on the CLOUD flavor assemble.
**Validation:**
```sh
./gradlew :smartautoclicker:assemblePlayStoreCloudDebug
./gradlew :smartautoclicker:assembleFDroidLocalDebug
./gradlew :smartautoclicker:dependencies --configuration fDroidLocalReleaseRuntimeClasspath
./gradlew testPlayStoreCloudDebugUnitTest
```
**Traceability:** Blueprint P1-5a (WorkManager + Hilt-Work infra; per-flavor Application); Addendum §10 (per-flavor Application keeps androidx.work out of GPLv3 LOCAL build); smartautoclicker/src/main/java/com/buzbuz/smartautoclicker/application/SmartAutoClickerApplication.kt; core:scheduling (P0-4)
**Dependencies:** P0-3, P0-4

#### [ ] P3-T06 — Add batched observation upload worker: happy-path + retryable backoff driving SyncState  `size: M`
**Slice:** Implement the @HiltWorker uploader body that pulls a PENDING/FAILED batch (BATCH_SIZE=100), marks rows UPLOADING in-flight, POSTs to /v1/observations:batch, applies per-row accepted|duplicate->SYNCED, and uses WorkManager backoff (Result.retry) on retryable errors (5xx/IO/timeout/429 Retry-After). Terminal-401 handling is split out to P3-T07.
**Definition of Done:**
- [ ] The worker fetches a batch via getUploadBatch(100), marks rows UPLOADING in-flight, and POSTs them to /v1/observations:batch using the authenticated client (P3-T02).
- [ ] Per-row status accepted|duplicate -> SYNCED; on retryable error (5xx/IO/timeout) the affected rows return to FAILED/PENDING and the worker returns Result.retry() (WorkManager backoff).
- [ ] Observation.id (client UUID) is never mutated between attempts, so server dedup on (tenant_id, id) yields zero duplicates across retries; ambiguous timeouts are safe to re-enqueue.
- [ ] Batches never exceed BATCH_SIZE=100 (server max); 429 Retry-After is honored as a retryable backoff.
- [ ] Unit tests with a mocked ApiService + in-memory DAO cover: full batch SYNCED on 2xx; duplicate status counted as SYNCED; retryable 5xx/IO/timeout/429 keeps rows re-uploadable and returns retry; a re-run after a simulated mid-flight failure produces no duplicate POST id set.
**Validation:**
```sh
./gradlew :core:network:testPlayStoreCloudDebugUnitTest
./gradlew testPlayStoreCloudDebugUnitTest
```
**Traceability:** Blueprint P2-2 (offline queue + batched idempotent upload; BATCH_SIZE=100; WorkManager backoff); Data contracts: POST /v1/observations:batch; idempotency via (tenant_id,id); 429 Retry-After; Addendum: retryable (5xx/IO/timeout) error handling; core:scheduling worker (P3-T05); core:observation repository (P3-T04)
**Dependencies:** P3-T04, P3-T05, P3-T01, P3-T02

#### [ ] P3-T07 — Handle terminal 401-revoked: stop worker, clear token, require re-pair  `size: S`
**Slice:** Add the distinct terminal-error path to the uploader: on a 401 revoked response, stop the worker (no retry), clear the persisted device token, and clear KEY_ACCOUNT_BOUND so the device must re-pair before any further sync.
**Definition of Done:**
- [ ] A 401 revoked response from /v1/observations:batch (or any authenticated /v1 call surfaced through the worker) is treated as terminal: the worker stops without scheduling backoff (Result.failure/success-no-retry per WorkManager semantics, not Result.retry).
- [ ] On terminal 401 the device token is cleared from the P3-T02 token store and KEY_ACCOUNT_BOUND is cleared so the uploader becomes a no-op until re-enrollment.
- [ ] No token-refresh is attempted (MVP: expiry/revocation => re-pair only).
- [ ] Rows in the in-flight batch are left in a recoverable state (returned to PENDING/FAILED, not stuck in UPLOADING) so they upload after re-pair.
- [ ] Unit test with a mocked ApiService returns 401 revoked and asserts: worker does not retry, token store is cleared, KEY_ACCOUNT_BOUND is cleared, and in-flight rows are left re-uploadable.
**Validation:**
```sh
./gradlew :core:network:testPlayStoreCloudDebugUnitTest
./gradlew testPlayStoreCloudDebugUnitTest
```
**Traceability:** Addendum §4 line 3716 / §7 line 3730 (terminal 401 revoked => stop workers, clear token, require re-pair); Blueprint P2-2 (terminal vs retryable error handling); core:network token store (P3-T02); core:scheduling worker (P3-T06); core/common/settings (KEY_ACCOUNT_BOUND)
**Dependencies:** P3-T06, P3-T02

#### [ ] P3-T08 — Gate sync on enrollment + cloud_sync_enabled toggle (no-op when off)  `size: S`
**Slice:** Make the uploader a no-op unless the device is enrolled (KEY_ACCOUNT_BOUND true) AND the cloud_sync_enabled setting is on; add the cloud_sync_enabled setting defaulting OFF and route the worker to short-circuit cleanly when either gate is false.
**Definition of Done:**
- [ ] A cloud_sync_enabled setting is added (in core:network or feature:cloud settings, following the SettingsDataSource pattern) defaulting OFF per Addendum §3 line 3706.
- [ ] The uploader checks both KEY_ACCOUNT_BOUND AND cloud_sync_enabled before doing any work; when either is false it returns immediately (Result.success no-op) and performs no network call and no SyncState transition.
- [ ] Toggling cloud_sync_enabled on does not by itself enroll; enrollment (P3-T03) remains the account-bound source of truth.
- [ ] Unit tests cover: not account-bound -> worker is a no-op (no ApiService call); account-bound but sync disabled -> no-op; both true -> worker proceeds to fetch a batch.
**Validation:**
```sh
./gradlew :core:network:testPlayStoreCloudDebugUnitTest
./gradlew testPlayStoreCloudDebugUnitTest
```
**Traceability:** Addendum §3 line 3706 (cloud_sync_enabled default OFF; sync gated on account-bound AND enabled); Blueprint P3-1 (sync gating); core/common/settings (KEY_ACCOUNT_BOUND, settings pattern); core:scheduling worker (P3-T06)
**Dependencies:** P3-T06, P3-T03

#### [ ] P3-T09 — End-to-end device->cloud single-observation upload with forced-retry zero-duplicate assertion  `size: M`
**Slice:** Wire enrollment + the gated uploader together so a freshly captured Observation flows device->cloud: enroll the device, capture one Observation via the P1-5b burst path, run the worker, confirm it lands SYNCED locally and is accepted exactly once server-side, and assert that a forced mid-flight retry produces zero net duplicates.
**Definition of Done:**
- [ ] After enrolling against /v1/devices:enroll (P3-T03) and enabling sync (P3-T08), capturing one Observation (via the P1-5b burst path) and enqueuing the uploader transitions that Observation to SYNCED and the server/stub records exactly one accepted row.
- [ ] Forcing a retry (kill the worker mid-flight then re-run) results in a duplicate server status on the second attempt and still a single stored row server-side — zero net duplicates.
- [ ] An integration-style Robolectric/JVM test exercises enroll -> persist Observation -> run worker -> assert SYNCED + single server-side id, using a mock /v1 backend; it also exercises the forced-retry path asserting no second accepted id.
- [ ] Full fast gate passes on both representative flavor variants and the CLOUD flavor app assembles.
**Validation:**
```sh
./gradlew testFDroidLocalDebugUnitTest
./gradlew testPlayStoreCloudDebugUnitTest
./gradlew :smartautoclicker:assemblePlayStoreCloudDebug
```
**Traceability:** Blueprint: Thinnest end-to-end vertical slice (Phase B endpoint: P2-1 + P2-2 idempotent upload); Blueprint P3-1 story #1 (device binds to Tenant then uploads); Data contracts: SYNCED = accepted|duplicate; zero server dupes; core:network uploader (P3-T06), terminal-401 (P3-T07), gating (P3-T08), enrollment (P3-T03); core:scheduling burst trigger (P1-5b)
**Dependencies:** P3-T03, P3-T06, P3-T07, P3-T08, P1-5b

## Phase P4 — Remote orchestration & fleet control

**Objective:** Make the fleet remotely controllable: the device pulls TrackingScenarios and START/STOP commands from Trax Cloud (FCM wake-signal on playStoreCloud, periodic polling on fDroidCloud), applies them through a consent-aware TrackingController seam, sends a 30s heartbeat (in-service loop while tracking, OneTimeWork chain when idle-enrolled) carrying tracking + setup-state so the dashboard knows which devices are online, tracking, or awaiting consent, and the dashboard exposes fleet views plus remote command controls. Phase ends with an operator pushing one scenario to N>=3 devices from the dashboard and remote-arming them, with each device acting within one poll cycle and the dashboard reflecting per-device tracking and pending-consent state. The remote command contract (endpoint URL + ack body shape) must be resolved to a single canonical form before the pull worker is built, since the source blueprint flags it as an unresolved three-way conflict.

**Entry criteria:**
- P2-1 done: core:network Retrofit /v1 client + DeviceCredentialsDataSource exist and authenticated GETs round-trip against a stub.
- P2-4 done: scenario pull+apply path (GET /v1/devices/{deviceId}/scenarios -> P1-4 adapter -> P1-5b trigger) emits Observations for an assigned scenario once a session is live.
- P3-1 done: device enrollment via pairing code works; persisted deviceId is the server-reconciled identity; KEY_ACCOUNT_BOUND is set; DeviceCredentialsDataSource.isEnrolledFlow reflects enrollment.
- P3-2 done: AccessibilityService-connection / media-projection live state is observable to non-UI code (the signal the arm-if-live START branch reads); if P3-2 does not deliver this, it must be added before P4-T02.
- P3-3 done: device_commands table + command issue path exist server-side (dashboard write target for remote start/stop).
- P3-4 done: tenant isolation enforced so device/command/assignment queries are scoped to the authenticated tenant.
- P1-5b done: DetectionTrigger drives a service-bound detection burst via SmartProcessingRepositoryImpl (setScenarioId then startScreenRecord/startDetection) through the foreground service holding the projection token.
- P1-5a done: cloud-only HiltWorkerFactory wiring is present (androidx.work + hilt-work catalog entries from P0-3).
- CONNECTIVITY flavor dimension (P0-1) and cloudImplementation/cloudKsp scoping (P0-2) are in place; core:network, core:scheduling, feature:cloud modules are scaffolded (P0-4) and link only in CLOUD variants.
- Cloud backend repo (separate non-GPL /v1 service) is reachable for the fleet acceptance test, and its OpenAPI spec is the source of truth for the command/heartbeat/assignment contract.

**Exit criteria:**
- P4-T00 has fixed the command contract to ONE canonical shape (endpoint URL + ack body) consistent with blueprint §11/§12, and the FCM-token update route is either added to the /v1 OpenAPI spec or T05 is repointed to a defined route; core:network DTOs match.
- A TrackingController interface in core:network is implemented by core:scheduling over SmartProcessingRepositoryImpl + LocalServiceProvider; STOP tears down detection unattended; START arms immediately when projection is live (setScenarioId(assigned) + start via P1-5b), else posts a consent notification and acks PENDING_CONSENT.
- CommandPullWorker pulls the canonical commands endpoint, discards expired (expiresAt < now) commands, applies each through TrackingController, and acks every command (APPLIED|PENDING_CONSENT|NOOP|EXPIRED) via the canonical ack body so the server stops resending.
- fDroidCloud builds run command pull via a periodic enrollment-gated CommandPullWorker (Constraints CONNECTED, ExistingPeriodicWorkPolicy.KEEP) plus a fetchNow() one-shot; playStoreCloud additionally receives an FCM wake nudge (TraxFcmService) that calls fetchNow(). Both paths converge on CommandPullWorker -> TrackingController -> ackCommand.
- A 30s heartbeat (driven by the in-service loop while a session is active and a WorkManager OneTimeWork self-rescheduling chain when idle-enrolled, explicitly NOT a periodic WorkManager worker) posts POST /v1/devices/heartbeat with {trackingActive} plus setup-state {enrolled, syncEnabled, a11yEnabled, projectionLive} and capture-health {pollSuccessRate, lastFailureReason}, and consumes {trackingEnabled, pendingCommands} to self-correct and trigger fetchNow() when pendingCommands>0.
- Dashboard shows a fleet device list (online via last_seen_at > now()-90s, per-device tracking state, and awaiting-setup/awaiting-consent derived from projectionLive/a11yEnabled) and exposes per-device and fleet-wide START/STOP that write device_commands.
- Acceptance (owned by P4-T09): a scenario assigned to N>=3 devices is pulled and (sessions live) all N emit Observations within one sync cycle; a fleet STOP halts all within one poll cycle and the dashboard flips each device's tracking state to false; a fleet START arms live-projection devices immediately and shows pending-consent (via projectionLive=false heartbeat) for the rest.
- testFDroidDebugUnitTest and testPlayStoreDebugUnitTest both green; fDroidCloud and playStoreCloud variants assemble; LOCAL variants still link no network/FCM/WorkManager (GPLv3 preserved); backend dashboard tests green.

**Validation gates:**
- `./gradlew testFDroidDebugUnitTest`
- `./gradlew testPlayStoreDebugUnitTest`
- `./gradlew :smartautoclicker:assembleFDroidDebug`
- `./gradlew :smartautoclicker:assemblePlayStoreDebug`
- `./gradlew :smartautoclicker:lintFDroidDebug`
- `cd backend && pytest -q (backend dashboard + command/heartbeat tests; substitute the cloud repo's real runner — pytest|npm test|go test ./... — once the stack is fixed, but a concrete invocation MUST be runnable, not a placeholder)`
- `./gradlew testFDroidDebugUnitTest --tests "*FleetAcceptance*" (end-to-end fleet harness owned by P4-T09)`

**Stop / blocker conditions:**
- The command contract (P4-T00) is not resolved to a single canonical endpoint+ack shape before CommandPullWorker (P4-T03) is built: do not build T03 against an ambiguous or mixed shape (data-contracts URL + per-command reason body is forbidden because {ids:[...]} cannot carry per-command reasons); stop and resolve in the OpenAPI spec first.
- The two delivery paths (FCM vs polling) diverge in behavior or applied semantics rather than both converging on CommandPullWorker -> TrackingController -> ackCommand; stop and re-converge before proceeding.
- A remote START attempts to start the mediaProjection foreground service from the background or persist projection consent across sessions (forbidden on Android 12+); START must remain arm-immediately-if-live else notify-to-consent.
- TrackingController.requestStart is implemented by passing a scenarioId into SmartProcessingRepository.startDetection (which takes no scenarioId); the real path is setScenarioId(Identifier) then startScreenRecord/startDetection per P1-5b — stop and re-ground if the start path is mis-wired.
- firebase-messaging or any GMS/FCM artifact, androidx.work, Retrofit/OkHttp, or feature:cloud UI links into any fDroidLocal* (LOCAL) variant — GPLv3 isolation is broken; stop and fix flavor scoping.
- A pulled command is applied without being acked, or an expired (expiresAt < now) command is applied — breaks the idempotent+expiring command contract and causes the server to resend.
- The dashboard PENDING_CONSENT / awaiting-setup state (P4-T07/T08) has no producing signal because the heartbeat (P4-T06) omits projectionLive/a11yEnabled — stop and reconcile producer/consumer before shipping the dashboard tasks.
- Cloud backend command/heartbeat/assignment endpoints are unavailable or their /v1 contract diverges from the OpenAPI spec used by core:network, blocking the end-to-end fleet acceptance test (backend is a separate non-GPL repo; coordinate before integration).

#### [ ] P4-T00 — Resolve command + FCM-token contract to a single canonical /v1 shape in the OpenAPI spec and core:network DTOs  `size: M`
**Slice:** Resolve the blueprint's flagged three-way conflict (core:network commands/pending + commands/{id}/ack-with-reason-body vs data-contracts /v1/devices/{deviceId}/commands + commands:ack with {ids:[...]}) by picking ONE canonical endpoint URL and ONE ack-body shape that can carry per-command outcome reasons, citing §11/§12; and decide the FCM-token update route (add to /v1 spec or fold into heartbeat). Output is the updated OpenAPI spec + matching core:network DTOs that every downstream P4 task consumes.
**Definition of Done:**
- [ ] A single canonical commands-pull endpoint URL and a single ack endpoint URL are chosen and documented in the OpenAPI spec, with §11/§12 cited as the deciding source.
- [ ] The ack body can carry per-command outcome reason (APPLIED|PENDING_CONSENT|NOOP|EXPIRED) — either per-command commands/{id}/ack {reason} or a batch body of {id, reason} pairs; a bare {ids:[...]} batch (which cannot encode reasons) is explicitly rejected.
- [ ] The FCM-token update is resolved: either a defined PATCH route is added to the /v1 spec or the token is carried in the heartbeat body; whichever is chosen is reflected in the spec and referenced by P4-T05.
- [ ] core:network request/response DTOs (CommandPullResponse, CommandAckRequest, command model with expiresAt) compile and match the chosen shapes; no DTO references a route absent from the spec.
- [ ] Unit test asserts ack-request DTO serializes a per-command reason for at least the four ack reasons, and that the command DTO exposes expiresAt for the EXPIRED path.
**Validation:**
```sh
./gradlew :core:network:testFDroidDebugUnitTest
```
**Traceability:** Blueprint §3783 UNRESOLVED command-contract conflict (core:network commands/pending+commands/{id}/ack vs data-contracts /v1/devices/{deviceId}/commands+commands:ack {ids:[...]}); §11 canonical /v1 contract; §12 data contracts; FCM-token stub §1615 'onNewToken { /* PATCH device fcm token */ }'; core/network/src/main/java/com/buzbuz/smartautoclicker/core/network/; OpenAPI /v1 spec consumed by core:network
**Dependencies:** P2-1

#### [ ] P4-T01 — Define TrackingController consent-aware command seam in core:network  `size: S`
**Slice:** Add a TrackingController interface (plus a CommandOutcome sealed result carrying ackReason APPLIED|PENDING_CONSENT|NOOP) in core:network so the command pull path depends only on the interface, not on core:scheduling or the local service.
**Definition of Done:**
- [ ] TrackingController exposes suspend requestStart(scenarioId: String): CommandOutcome and suspend stop(scenarioId: String?): CommandOutcome.
- [ ] CommandOutcome exposes a stable ackReason string used verbatim in the canonical ack request body defined in P4-T00.
- [ ] core:network compiles with no dependency edge onto core:scheduling, SmartProcessingRepositoryImpl, or LocalServiceProvider (interface only).
- [ ] KDoc states START is consent-gated (arm-if-live else notify) and STOP is always unattended, and that scenarioId here is the assignment identifier passed to setScenarioId on the implementation side (NOT a startDetection parameter).
**Validation:**
```sh
./gradlew :core:network:testFDroidDebugUnitTest --tests "*TrackingController*"
```
**Traceability:** Blueprint 'Remote start/stop: FCM + polling fallback' (TrackingController seam); Architecture 'Module graph (strictly inward)'; core/network/src/main/java/com/buzbuz/smartautoclicker/core/network/; core/network/src/main/java/com/buzbuz/smartautoclicker/core/network/di/Hilt.kt
**Dependencies:** P2-1, P4-T00

#### [ ] P4-T02 — Implement TrackingController in core:scheduling over LocalServiceProvider via setScenarioId + start/stop  `size: M`
**Slice:** Implement TrackingController in core:scheduling: STOP routes to LocalServiceProvider.getLocalService { it?.stop() } (unattended); START, when AccessibilityService is connected and projection is live (P3-2 signal), marks/assigns the scenario via the real path (setScenarioId(Identifier) then the P1-5b service-bound start) and returns APPLIED; otherwise posts a high-priority consent notification and returns PENDING_CONSENT. Must NOT pass a scenarioId into startDetection (the API takes none).
**Definition of Done:**
- [ ] stop() tears down detection/projection via LocalServiceProvider.getLocalService { it?.stop() } and returns APPLIED, or NOOP when LocalServiceProvider.isServiceStarted() is false / already stopped.
- [ ] requestStart() with a live projection session assigns the scenario via SmartProcessingRepository.setScenarioId(Identifier) and triggers the P1-5b service-bound start (startScreenRecord/startDetection with no scenarioId arg) and returns APPLIED.
- [ ] requestStart() while already tracking that same assigned scenario returns NOOP (idempotent), and no second start is issued.
- [ ] requestStart() with no live projection / a11y not connected posts a 'Tap to start remote tracking for <scenario>' notification (no background mediaProjection start) and returns PENDING_CONSENT.
- [ ] Unit test asserts the three branches (live->APPLIED via setScenarioId, no-projection->PENDING_CONSENT, already-tracking->NOOP) using a fake LocalServiceProvider/SmartProcessingRepository; test verifies startDetection is never called with a scenarioId argument.
**Validation:**
```sh
./gradlew :core:scheduling:testFDroidDebugUnitTest --tests "*TrackingController*"
```
**Traceability:** Blueprint CONSENT & FOREGROUND-SERVICE BLOCKER for remote START; §184/§322-323 tracking driven by marking scenario active in in-service loop (setScenarioId), NOT a startDetection scenarioId param; core/scheduling/src/main/java/com/buzbuz/smartautoclicker/core/scheduling/; smartautoclicker/src/main/java/com/buzbuz/smartautoclicker/localservice/LocalServiceProvider.kt; core/smart/processing/src/main/java/com/buzbuz/smartautoclicker/core/processing/domain/SmartProcessingRepository.kt (setScenarioId line 61, startDetection line 89 takes no scenarioId)
**Dependencies:** P4-T01, P1-5b, P3-2

#### [ ] P4-T03 — Add CommandPullWorker pulling + acking remote commands through TrackingController  `size: M`
**Slice:** Add an enrollment-gated @HiltWorker CommandPullWorker in core:network that calls the canonical commands-pull endpoint (P4-T00), discards commands past expiresAt (acking EXPIRED via the canonical ack body), applies START/STOP via TrackingController, and acks every command with its outcome reason using the canonical ack endpoint/body.
**Definition of Done:**
- [ ] doWork() returns success early when DeviceCredentialsDataSource.isEnrolledFlow is false (no pull, zero API calls on unenrolled devices).
- [ ] Commands with expiresAt < now are acked EXPIRED via the canonical ack body and skipped, never applied.
- [ ] Each remaining command is applied through TrackingController and acked exactly once with APPLIED|PENDING_CONSENT|NOOP using the canonical per-command (or {id,reason}-pair) ack body from P4-T00.
- [ ] A null/error response body returns Result.retry(); a 2xx returns Result.success().
- [ ] Unit test with a fake TraxCloudApi + fake TrackingController asserts: expired command acked EXPIRED not applied, START applied+acked once, STOP applied+acked once, unenrolled short-circuits with zero API calls, and each ack carries the correct reason via the canonical body.
**Validation:**
```sh
./gradlew :core:network:testFDroidDebugUnitTest --tests "*CommandPullWorker*"
```
**Traceability:** Blueprint 'Remote start/stop: FCM + polling fallback' (CommandPullWorker code listing); canonical command contract resolved in P4-T00; device_commands (type CHECK START|STOP, expires_at, acked_at); core/network/src/main/java/com/buzbuz/smartautoclicker/core/network/; core/network/src/main/java/com/buzbuz/smartautoclicker/core/network/di/Hilt.kt
**Dependencies:** P4-T00, P4-T01, P4-T02, P3-2

#### [ ] P4-T04 — Schedule periodic enrollment-gated command polling + fetchNow one-shot (fDroidCloud fallback)  `size: M`
**Slice:** Add a CommandSyncScheduler in core:network that enqueues CommandPullWorker as a periodic UniquePeriodicWork (Constraints CONNECTED, ExistingPeriodicWorkPolicy.KEEP) and a fetchNow() expedited one-shot, mirroring the existing observation uploader's WorkManager wiring; enqueued from the cloud-only Application on enrollment.
**Definition of Done:**
- [ ] CommandSyncScheduler.schedulePeriodic(context) enqueues a unique periodic CommandPullWorker with CONNECTED constraint and ExistingPeriodicWorkPolicy.KEEP.
- [ ] CommandSyncScheduler.fetchNow(context) enqueues a one-shot expedited CommandPullWorker (the wake/heartbeat-driven path).
- [ ] Periodic enqueue is wired from the cloud-only Application after enrollment and is absent from LOCAL builds (no WorkManager link in fDroidLocal*).
- [ ] Robolectric/WorkManager-test-harness test asserts the periodic request uses KEEP and CONNECTED, and fetchNow enqueues a one-shot expedited request.
**Validation:**
```sh
./gradlew :core:network:testFDroidDebugUnitTest --tests "*CommandSyncScheduler*"
```
**Traceability:** Blueprint 'Polling fallback (fDroid/CLOUD, no GMS)' (periodic enrollment-gated CommandPullWorker, Constraints(CONNECTED), ExistingPeriodicWorkPolicy.KEEP); core/network/src/main/java/com/buzbuz/smartautoclicker/core/network/; smartautoclicker/src/cloud/ (cloud-only Application)
**Dependencies:** P4-T03, P1-5a

#### [ ] P4-T05 — Add FCM wake-signal TraxFcmService in playStoreCloud source set  `size: M`
**Slice:** Add firebase-messaging-ktx via playStoreImplementation and a TraxFcmService (FirebaseMessagingService) in the playStoreCloud source set whose onMessageReceived calls CommandSyncScheduler.fetchNow() and onNewToken updates the device FCM token via the route resolved in P4-T00; place google-services.json under src/cloud/.
**Definition of Done:**
- [ ] firebase-messaging-ktx catalog alias is added and linked only via playStoreImplementation (links only in playStore* variants).
- [ ] TraxFcmService lives under smartautoclicker/src/playStoreCloud/ and is declared in a playStoreCloud manifest; onMessageReceived enqueues an immediate command pull via CommandSyncScheduler.fetchNow() (push is a wake signal, REST is source of truth).
- [ ] onNewToken updates the device FCM token via the route decided in P4-T00 (defined PATCH route or heartbeat-carried token) — no call to an undefined endpoint.
- [ ] google-services.json resides under smartautoclicker/src/cloud/ so both playStoreCloud and fDroidCloud resolve config; existing src/playStore/google-services.json untouched.
- [ ] fDroidCloud and fDroidLocal builds compile without firebase-messaging (no GMS dependency leaks into fDroid).
**Validation:**
```sh
./gradlew :smartautoclicker:assemblePlayStoreDebug
```
**Traceability:** Blueprint 'FCM path (playStore + CLOUD, has GMS)' (TraxFcmService listing); 'Firebase / FCM project setup' (google-services.json under src/cloud/); firebaseMessaging catalog entry ~line 1294; FCM-token route resolved in P4-T00 (stub §1615); gradle/libs.versions.toml; smartautoclicker/src/playStoreCloud/ (new source set); smartautoclicker/src/cloud/google-services.json
**Dependencies:** P4-T00, P4-T04, P0-1

#### [ ] P4-T06a — Build 30s heartbeat lifecycle: in-service loop while tracking, OneTimeWork chain when idle-enrolled  `size: M`
**Slice:** Implement the heartbeat cadence and lifecycle in core:network/core:scheduling: a fixed 30s cadence driven by the in-service loop while a detection session is active, and a self-rescheduling WorkManager OneTimeWork chain when idle-but-enrolled. Explicitly NOT a periodic WorkManager worker (the 15-min floor is too coarse). This task owns scheduling/lifecycle and request serialization only; reconciliation logic is P4-T06b.
**Definition of Done:**
- [ ] Heartbeat fires on a fixed 30s cadence while enrolled: from the in-service loop when a session is active, and from a self-rescheduling OneTimeWork chain when idle-enrolled; no periodic WorkManager worker is used.
- [ ] Request body serializes {trackingActive} plus setup-state {enrolled, syncEnabled, a11yEnabled, projectionLive} and capture-health {pollSuccessRate, lastFailureReason} from current local state.
- [ ] Heartbeat is enrollment-gated (no-op / chain not scheduled when not enrolled) and absent from LOCAL builds.
- [ ] Unit test asserts: cadence source switches between in-service loop and OneTimeWork chain by session-active state; the full body (trackingActive + setup-state + capture-health) is serialized; unenrolled -> no request and no chain rescheduled.
**Validation:**
```sh
./gradlew :core:network:testFDroidDebugUnitTest --tests "*Heartbeat*"
```
**Traceability:** Blueprint §5 heartbeat mechanism (in-service loop while active + OneTimeWork chain when idle, NOT periodic worker — 15-min floor too coarse); §11 POST /v1/devices/heartbeat {trackingActive}->{trackingEnabled,pendingCommands}; §3/§4 addendum setup-state {enrolled,syncEnabled,a11yEnabled,projectionLive} + capture-health {pollSuccessRate,lastFailureReason}; core/network/src/main/java/com/buzbuz/smartautoclicker/core/network/; core/scheduling/src/main/java/com/buzbuz/smartautoclicker/core/scheduling/
**Dependencies:** P4-T03, P3-1, P3-2

#### [ ] P4-T06b — Heartbeat response-driven self-correction + pendingCommands pull trigger  `size: S`
**Slice:** Consume the heartbeat response {trackingEnabled, pendingCommands}: treat trackingEnabled as authoritative and self-correct local tracking state (start/stop via TrackingController) to match the server, and call CommandSyncScheduler.fetchNow() when pendingCommands>0.
**Definition of Done:**
- [ ] When response trackingEnabled disagrees with local trackingActive, local state is reconciled via TrackingController (server is authoritative): trackingEnabled=false stops tracking, trackingEnabled=true follows the consent-gated START path.
- [ ] pendingCommands>0 in the response enqueues an immediate command pull via CommandSyncScheduler.fetchNow().
- [ ] Reconciliation is enrollment-gated and absent from LOCAL builds.
- [ ] Unit test asserts: trackingEnabled=false with local trackingActive=true triggers stop; trackingEnabled=true with local false triggers the START path; pendingCommands>0 triggers exactly one fetchNow; pendingCommands=0 triggers none.
**Validation:**
```sh
./gradlew :core:network:testFDroidDebugUnitTest --tests "*Heartbeat*"
```
**Traceability:** Blueprint §11 heartbeat response {trackingEnabled, pendingCommands} (server authoritative, pendingCommands>0 -> immediate pull); TrackingController self-correction seam; core/network/src/main/java/com/buzbuz/smartautoclicker/core/network/
**Dependencies:** P4-T06a, P4-T01, P4-T04

#### [ ] P4-T07 — Dashboard fleet device list with online, per-device tracking, and awaiting-setup/consent state  `size: M`
**Slice:** Build the dashboard fleet view: a device list showing online status (last_seen_at > now()-90s), authoritative per-device tracking state, and an awaiting-setup/awaiting-consent indicator derived from the heartbeat setup-state (projectionLive/a11yEnabled), scoped to the authenticated tenant, backed by the cloud devices/scenario_assignments tables.
**Definition of Done:**
- [ ] Device list renders each enrolled device with an online indicator computed from last_seen_at > now()-90s.
- [ ] Per-device tracking state reflects scenario_assignments.tracking (authoritative), not only devices.tracking (coarse).
- [ ] An awaiting-setup/awaiting-consent state is derived from the heartbeat setup-state (projectionLive=false and/or a11yEnabled=false) produced by P4-T06a.
- [ ] List is tenant-scoped; a device from another tenant never appears (P3-4 isolation).
- [ ] A device that stops heartbeating for >90s flips to offline; one that resumes flips back online.
- [ ] Backend dashboard tests pass for the device-list query and the online/tracking/awaiting-setup projection.
**Validation:**
```sh
cd backend && pytest backend/tests/dashboard/test_device_list.py -q  (substitute the cloud repo's real runner — pytest|npm test|go test ./... — but it MUST be a concrete runnable invocation, not a placeholder)
```
**Traceability:** Blueprint MVP Scope Contract 'Web dashboard: device list (online/tracking)'; 'Heartbeat' online rule; §3/§4 setup-state projectionLive/a11yEnabled feeding awaiting-setup; Tenant isolation (P3-4); Cloud backend repo (separate non-GPL service speaking /v1); Cloud DB devices.last_seen_at, scenario_assignments.tracking
**Dependencies:** P4-T06a, P3-4

#### [ ] P4-T08 — Dashboard remote start/stop controls (per-device and fleet-wide)  `size: M`
**Slice:** Add dashboard START/STOP controls that enqueue idempotent, expiring device_commands per device and a fleet-wide action over a selected device set, then reflect APPLIED vs PENDING_CONSENT per-device state from heartbeat-reported status.
**Definition of Done:**
- [ ] Per-device START/STOP writes a device_commands row (type CHECK START|STOP) with an expires_at and tenant-scoped device_id.
- [ ] A fleet-wide START/STOP fans out one command per selected device in a single tenant-scoped operation.
- [ ] Issuing the same command while a device is already in that state is a no-op end-to-end (device acks NOOP; no duplicate effect).
- [ ] Dashboard distinguishes APPLIED vs PENDING_CONSENT (device received START but projectionLive=false in heartbeat, awaiting operator consent) from the device's reported state.
- [ ] Backend dashboard tests pass for command insert, fleet fan-out, and the tracking-state reflection after ack/heartbeat.
**Validation:**
```sh
cd backend && pytest backend/tests/dashboard/test_commands.py -q  (substitute the cloud repo's real runner — pytest|npm test|go test ./... — but it MUST be a concrete runnable invocation, not a placeholder)
```
**Traceability:** Blueprint MVP Scope Contract item 7 'Remote fleet start/stop'; P4-4 'Fleet start/stop controls'; device_commands schema (type CHECK, expires_at, acked_at); PENDING_CONSENT ack semantics fed by projectionLive heartbeat (P4-T06a); Cloud backend repo (separate non-GPL service speaking /v1); Cloud DB device_commands, scenario_assignments.tracking
**Dependencies:** P4-T07, P3-3

#### [ ] P4-T09 — End-to-end fleet acceptance test (N>=3 pull-and-emit, fleet STOP/START within one cycle, dashboard reflects state)  `size: M`
**Slice:** Add the owning end-to-end acceptance harness for the phase exit criterion: assign a scenario to N>=3 devices, verify all pull and (sessions live) emit Observations within one sync cycle; issue a fleet STOP and verify all halt within one poll cycle with the dashboard flipping tracking to false; issue a fleet START and verify live-projection devices arm immediately and non-live devices show pending-consent (projectionLive=false heartbeat).
**Definition of Done:**
- [ ] The harness drives N>=3 simulated/instrumented devices through assignment -> CommandPullWorker -> TrackingController and asserts all N emit Observations within one sync cycle when sessions are live.
- [ ] A fleet STOP halts all N within one poll cycle and the dashboard device list flips each device tracking state to false.
- [ ] A fleet START arms live-projection devices (APPLIED) immediately and shows pending-consent for non-live devices (PENDING_CONSENT via projectionLive=false heartbeat).
- [ ] The test exercises both fDroidCloud polling and playStoreCloud FCM-nudged paths converging on CommandPullWorker.
- [ ] Test is wired as a named gate (*FleetAcceptance*) and runs green in testFDroidDebugUnitTest plus the backend dashboard suite.
**Validation:**
```sh
./gradlew testFDroidDebugUnitTest --tests "*FleetAcceptance*"
cd backend && pytest backend/tests/integration/test_fleet_acceptance.py -q  (substitute the cloud repo's real runner — pytest|npm test|go test ./... — concrete invocation required, not a placeholder)
```
**Traceability:** Phase exitCriteria acceptance clause (N>=3 pull-and-emit within one cycle; fleet STOP/START within one poll cycle; dashboard reflects per-device tracking + pending-consent); stopConditions backend-availability coordination; core/network/, core/scheduling/ (device-side harness); Cloud backend repo integration tests (separate non-GPL /v1 service)
**Dependencies:** P4-T03, P4-T04, P4-T05, P4-T06b, P4-T08

## Phase P5 — Vision hardening, scheduling & MVP acceptance

**Objective:** Harden the end-to-end capture path for production: enforce the scheduled/headless execution model strictly inside the MediaProjection foreground-service constraint, raise OCR accuracy to a measured golden-screenshot floor, pin number normalization/locale-independence, sequence the onboarding/permission wizard, codify the capture-health error taxonomy used by the heartbeat, and prove the MVP acceptance matrix against the scope acceptance criteria.

**Entry criteria:**
- P1-5b merged: the in-service coroutine cadence loop drives detection bursts through the live foreground-service projection token (never acquires consent in background) and persists one Observation per burst.
- P2-2 merged: offline queue + batched idempotent upload with observable syncState Flow is green.
- P2-4 / P3-2 merged: scenario pull/apply and remote start/stop command pull route through the P1-5b DetectionTrigger.
- P1-1 + P1-2a/P1-2b merged: recognizedText surfaced from JNI, ProcessedConditionResult.Screen widened, ObservationValueMapper typing rules (NUMBER|TEXT|STATE) in place.
- P2-5 merged: the sync-enable toggle owner (core:common:settings KEY_SYNC_ENABLED) is persisted and readable, so the wizard consent toggle and heartbeat setup-state can read/write it.
- P3-1 device enrollment + token store available so heartbeat/setup-state can be populated.
- The CONNECTIVITY flavor dimension is merged: KlickrVariants.kt declares KlickrDimension.CONNECTIVITY with LOCAL/CLOUD flavors, and the cloudImplementation/cloudKsp helpers gate core:network + feature:cloud into the cloud variants only (blueprint lines 338-371, 1653-1704). This is the prerequisite that makes the four-segment variant names (fDroidLocal*/fDroidCloud*/playStoreLocal*/playStoreCloud*) and the cloud unit-test targets exist.
- core:common:permissions PermissionsController and permission models (PermissionAccessibilityService, PermissionOverlay, PermissionPostNotification) are present and usable from the onboarding flow.

**Exit criteria:**
- Scheduled tracking runs only via the in-service cadence loop bound to a live projection token; with the foreground service down or projection absent, scheduled and remote-START paths no-op and record the correct capture-health failure reason, never attempting background consent.
- OCR golden-screenshot accuracy gate passes at OCR_ACCURACY_FLOOR = 0.90 (exact-match) over a corpus of >= 40 labeled cases for Number and Text, runs as the instrumented connected-androidTest suite, and fails on regression below the floor; its CI-coverage status (connected vs JVM) is explicitly recorded per the stop condition.
- Number normalization is locale-independent (Double.toString, no separators/units/rounding) and verified across non-US default locales; confidence is Int 0-100 round-half-up computed once on-device.
- First-run 4-step wizard (Enroll -> consent+sync toggle default OFF -> AccessibilityService -> MediaProjection) enforces ordering and gating at each of the three transitions, and a remote START on an un-setup device is acked PENDING_CONSENT with a notification.
- A single CaptureFailureReason taxonomy (no_projection, engine_busy, a11y_disabled, ocr_low_confidence, network) is defined once, emitted at the real failure sites, and surfaced in the heartbeat capture-health payload.
- The MVP acceptance matrix maps every scope acceptance criterion to an executable check (unit/Robolectric where possible, documented manual step otherwise) and all rows pass on both distribution flavors, with cloud rows exercised on the cloud connectivity variant.

**Validation gates:**
- `./gradlew testFDroidLocalDebugUnitTest`
- `./gradlew testPlayStoreCloudDebugUnitTest`
- `./gradlew :smartautoclicker:assembleFDroidLocalDebug`
- `./gradlew :smartautoclicker:assemblePlayStoreCloudDebug`
- `./gradlew lint`

**Stop / blocker conditions:**
- If the CONNECTIVITY flavor dimension / cloudImplementation+cloudKsp wiring (the prerequisite that makes core:network + feature:cloud linkable and creates the cloud unit-test variant) is NOT yet merged, STOP the cloud-dependent tasks (P5-T07, P5-T08c) and the four-segment validation gates: the variant names and cloud test targets they reference will not exist. Proceed only with the local/on-device tasks until the dimension lands.
- If hardening reveals the scheduler can acquire or retain a MediaProjection token outside a live foreground session (background consent path), STOP and escalate: this violates the load-bearing P1 constraint and the entire scheduling design must be revisited before any further P5 work.
- If the OCR golden-screenshot accuracy floor (OCR_ACCURACY_FLOOR = 0.90, >= 40 cases) cannot be met for Number or Text with the bundled recognition models, STOP and escalate to product: the accuracy floor (or model packaging) is a scope decision, not an engineering workaround.
- If the cloud /v1 heartbeat or commands contract has not been finalized (capture-health field names, PENDING_CONSENT ack semantics), STOP the heartbeat/error-taxonomy wire-mapping tasks and proceed only with the on-device taxonomy enum and local emission; do not invent wire field names.
- The golden-screenshot accuracy tests run as connected/instrumented (androidTest) under core/smart/detection (the module has only androidTest + main + debug source sets, no JVM src/test), so they are NOT covered by the testFDroidLocalDebugUnitTest CI gate and require a device/emulator matrix decision before being treated as a merge blocker; flag this explicitly rather than asserting CI coverage.

#### [ ] P5-T01 — Enforce projection-bound scheduling guard with no background consent  `size: M`
**Slice:** Add an explicit precondition guard in the in-service cadence loop and DetectionTrigger path so a scheduled burst only fires when the foreground service holds a live MediaProjection token and a scenarioId is set; any other state is a logged no-op that never requests consent.
**Definition of Done:**
- [ ] The DetectionTrigger implementation in smartautoclicker/.../localservice/LocalService.kt returns a typed skip result (not just a log) when LocalServiceProvider.getLocalService() is null, the projection token is absent, or setScenarioId(...) was never called.
- [ ] No code path reachable from core:scheduling or the cadence loop calls MediaProjection consent / startActivityForResult; a unit test asserts the trigger returns the skip result and persists nothing when the service is down.
- [ ] A burst fires exactly once per cadence tick when the service is up, token live, and scenarioId set (assert single startDetection invocation with the short autoStopDuration).
- [ ] The 5s minimum interval enforcement from P1-5b remains in force and is covered by an assertion.
**Validation:**
```sh
./gradlew testFDroidLocalDebugUnitTest --tests "*DetectionTrigger*"
```
**Traceability:** Blueprint P1-0 / P1-5b (service-driven trigger, no background consent); Addendum §10 (in-service cadence loop, WorkManager only for coarse tick); smartautoclicker/src/main/java/com/buzbuz/smartautoclicker/localservice/LocalService.kt; smartautoclicker/src/main/java/com/buzbuz/smartautoclicker/localservice/LocalServiceProvider.kt; core:scheduling (worker body)
**Dependencies:** P1-5b, P2-4, P3-2

#### [ ] P5-T02 — Define CaptureFailureReason taxonomy and emit at real failure sites  `size: S`
**Slice:** Introduce a single CaptureFailureReason enum (no_projection, engine_busy, a11y_disabled, ocr_low_confidence, network) in core:observation and emit it at the actual skip/failure sites in the scheduling/trigger and capture paths.
**Definition of Done:**
- [ ] A CaptureFailureReason enum exists in core:observation with exactly the five values no_projection, engine_busy, a11y_disabled, ocr_low_confidence, network (names match the heartbeat wire vocabulary at blueprint line 3714).
- [ ] The projection-bound skip from P5-T01 emits no_projection; an attempt while a burst is already running emits engine_busy; a disabled AccessibilityService emits a11y_disabled.
- [ ] ocr_low_confidence is emitted when a read completes below the configured confidence floor; network is reserved for the sync engine and referenced by it.
- [ ] A unit test maps each simulated failure condition to its expected CaptureFailureReason value.
**Validation:**
```sh
./gradlew :core:observation:testFDroidLocalDebugUnitTest
```
**Traceability:** Blueprint Data contracts (heartbeat capture-health lastFailureReason ∈ {no_projection,engine_busy,a11y_disabled,ocr_low_confidence,network}, line 3714); Addendum errors section (terminal vs retryable, line 3716); core:observation; smartautoclicker/src/main/java/com/buzbuz/smartautoclicker/localservice/LocalService.kt
**Dependencies:** P5-T01

#### [ ] P5-T03 — Pin number normalization and locale-independence rules  `size: S`
**Slice:** Lock ObservationValueMapper number stringification to Double.toString with no locale separators/units/rounding and verify it under non-US default locales; pin confidence to Int 0-100 via Math.round round-half-up computed once.
**Definition of Done:**
- [ ] A parameterized unit test runs the Number mapping under at least Locale.US, a comma-decimal locale (e.g. Locale.GERMANY/FRANCE), and an RTL locale, asserting byte-identical value strings with a '.' decimal and no grouping separators.
- [ ] Failed Number read maps to value=null and isFulfilled=false (not dropped), with a distinct assertion.
- [ ] confidence is asserted as Int in 0..100 produced by Math.round(confidenceRate).toInt() (round-half-up) and is never a fraction.
- [ ] Text mapping is asserted as trim-only (no case-fold/NFC); empty-after-trim with isFulfilled=true is a legal, distinct case.
**Validation:**
```sh
./gradlew :core:observation:testFDroidLocalDebugUnitTest --tests "*ObservationValueMapper*"
```
**Traceability:** Blueprint P1-2b + Data contracts Normalization (Number raw Double, no locale; confidence Int 0-100 round-half-up; Text trim-only); core:observation (ObservationValueMapper)
**Dependencies:** P1-2b

#### [ ] P5-T04 — OCR golden-screenshot accuracy floor gate  `size: M`
**Slice:** Add an instrumented golden-screenshot OCR accuracy test that runs the bundled Number/Text recognition over a labeled corpus and fails when measured exact-match accuracy drops below OCR_ACCURACY_FLOOR = 0.90.
**Definition of Done:**
- [ ] A TestOcrCases fixture (mirroring TestImage.expectedResults) and a labeled corpus of >= 40 golden cases live under core/smart/detection/src/androidTest/assets/, spread across the canonical categories (resource counters, price strings, step counters, kiosk KPI/state strings) so no single category dominates (blueprint lines 2805-2808).
- [ ] The test computes per-case correctness and an aggregate, asserts cases.size >= 40, and asserts aggregate accuracy >= OCR_ACCURACY_FLOOR (0.90 exact-match); flipping a known-good asset to a wrong label makes the test fail.
- [ ] OCR_ACCURACY_FLOOR = 0.90 and the >= 40-case minimum are declared as named constants in the test/fixture file with the blueprint rationale, giving a single source of truth for the floor.
- [ ] The test header explicitly records that it runs as instrumented connected-androidTest (the module has no JVM src/test, only androidTest/main/debug), so it is NOT covered by the testFDroidLocalDebugUnitTest gate, per the phase stop condition.
**Validation:**
```sh
./gradlew :core:smart:detection:connectedFDroidLocalDebugAndroidTest
```
**Traceability:** Blueprint OCR extraction accuracy via golden screenshots (OCR_ACCURACY_FLOOR = 0.90, corpus >= 40 cases, assets/ + TestOcrCases, lines 2805-2810); core/smart/detection/src/androidTest/java/com/buzbuz/smartautoclicker/core/detection/data/TestImages.kt; core/smart/detection/src/androidTest/java/com/buzbuz/smartautoclicker/core/detection/data/TestResults.kt; core/smart/detection/src/androidTest/assets (new OCR corpus + TestOcrCases fixture)
**Dependencies:** P1-1

#### [ ] P5-T05 — Enforce first-run 4-step onboarding/permission sequencing  `size: M`
**Slice:** Wire the first-run wizard to enforce the ordered Enroll -> consent+sync toggle (default OFF) -> AccessibilityService -> MediaProjection sequence, gating each of the three transitions on the prior step using the existing PermissionsController and permission models.
**Definition of Done:**
- [ ] The wizard advances only in order: enrollment must complete before the consent/sync toggle screen, which precedes AccessibilityService enablement (PermissionAccessibilityService), which precedes the MediaProjection grant.
- [ ] The sync toggle defaults OFF and its persisted value (KEY_SYNC_ENABLED, owned by P2-5) is written by the wizard and read back by the sync engine.
- [ ] Unit/Robolectric tests assert that each of the three gate transitions rejects skip/reorder: (a) the consent/sync step is unreachable before enrollment completes, (b) the AccessibilityService step is unreachable before the consent step is passed, (c) the MediaProjection step is unreachable while AccessibilityService is disabled.
- [ ] MediaProjection consent is requested only from the foreground wizard step, never from a background/worker path (cross-checked against the P5-T01 guard).
**Validation:**
```sh
./gradlew testFDroidLocalDebugUnitTest --tests "*Wizard*"
```
**Traceability:** Addendum binding decisions (first-run 4-step wizard, sync default OFF); Blueprint P3-1 (enrollment), P2-5 (KEY_SYNC_ENABLED); core/common/permissions/src/main/java/com/buzbuz/smartautoclicker/core/common/permissions/PermissionsController.kt; core/common/permissions/.../model/PermissionAccessibilityService.kt; feature:cloud (enrollment UI)
**Dependencies:** P3-1, P2-5, P5-T01

#### [ ] P5-T06 — Remote START on un-setup device acked PENDING_CONSENT with notification  `size: M`
**Slice:** Ensure a remote START command arriving when no live session/projection exists is acked as PENDING_CONSENT and posts a user-facing notification, without ever attempting background consent.
**Definition of Done:**
- [ ] When the command pull (P3-2) yields START and P5-T01's guard reports no live projection/session, the command is acked PENDING_CONSENT (not STOPPED, not silently dropped) and a high-priority 'tap to start tracking' notification is posted (blueprint line 3712).
- [ ] Once the user grants consent and a session goes live, the pending START resumes tracking on the next cadence/poll cycle without a new command.
- [ ] A unit test asserts the PENDING_CONSENT ack and notification trigger for the no-session case and the resume behavior for the session-live case.
- [ ] The notification path uses PermissionPostNotification gating where applicable and does not crash when notifications are denied.
**Validation:**
```sh
./gradlew testPlayStoreCloudDebugUnitTest --tests "*Command*"
```
**Traceability:** Blueprint P3-2 (remote START with no live session acked PENDING_CONSENT, line 3712); Addendum (remote START on un-setup device acked PENDING_CONSENT + notification); core:scheduling; core:network (commands); core/common/permissions/.../model/PermissionPostNotification.kt
**Dependencies:** P3-2, P5-T01, P5-T05

#### [ ] P5-T07 — Surface capture-health and setup-state in heartbeat payload  `size: M`
**Slice:** Populate the heartbeat capture-health (pollSuccessRate, lastFailureReason) from the P5-T02 taxonomy and setup-state (enrolled, syncEnabled, a11yEnabled, projectionLive) so the cloud heartbeat reflects on-device status; cloud-flavor only.
**Definition of Done:**
- [ ] The heartbeat builder in core:network computes pollSuccessRate as fulfilled Observations / scheduled polls over a rolling last-hour window (blueprint line 3714) and sets lastFailureReason to the most recent CaptureFailureReason (or null) using the P5-T02 enum's wire names.
- [ ] setup-state booleans enrolled/syncEnabled/a11yEnabled/projectionLive are sourced from enrollment state, KEY_SYNC_ENABLED, AccessibilityService status, and the live projection token respectively.
- [ ] A unit test asserts the serialized heartbeat body contains the expected capture-health and setup-state values for a representative state and that lastFailureReason is omitted/null when there is no failure.
- [ ] The heartbeat code is gated to the cloud connectivity flavor (cloudImplementation), so the local variants do not link it; the cloud variant compiles and the test runs on the cloud unit-test target.
**Validation:**
```sh
./gradlew testPlayStoreCloudDebugUnitTest --tests "*Heartbeat*"
```
**Traceability:** Blueprint Data contracts (heartbeat POST /v1/devices/heartbeat, setup-state + capture-health, lines 3710-3714); Blueprint connectivity flavor / cloudImplementation gating (lines 189, 368-371); core:network; core:observation (CaptureFailureReason from P5-T02)
**Dependencies:** P5-T02, P3-1

#### [ ] P5-T08a — Author the MVP acceptance matrix definition and sign-off artifact  `size: S`
**Slice:** Create the MVP acceptance matrix structure: one row per scope acceptance criterion, each with a criterion id, the responsible task/file, and a designated check kind (automated unit/Robolectric, automated connected-androidTest, or documented manual), committed as the single MVP sign-off reference.
**Definition of Done:**
- [ ] Every MVP scope acceptance criterion has exactly one matrix row with: a stable criterion id, the responsible task id + primary file/module, the check kind, and the expected pass result.
- [ ] Each row names the concrete realizing check (test class/method or manual procedure id) that the per-area verification tasks (P5-T08b/P5-T08c) implement; no criterion is left unmapped.
- [ ] Manual-only rows (e.g. live MediaProjection burst, dashboard chart rendering) state the exact device steps and the pass/fail observation, and are clearly marked non-CI.
- [ ] The matrix is committed as the MVP sign-off artifact reference and links each automated row to its CI gate (local unit gate, cloud unit gate, or connected-androidTest).
**Validation:**
```sh
./gradlew testFDroidLocalDebugUnitTest --tests "*AcceptanceMatrix*"
```
**Traceability:** Phase focus: MVP acceptance test matrix mapped to scope acceptance criteria; Blueprint thinnest-vertical-slice (Phase A/B) and per-ticket DoD gates
**Dependencies:** P5-T01, P5-T02, P5-T03, P5-T04, P5-T05, P5-T06, P5-T07

#### [ ] P5-T08b — Realize local/on-device acceptance rows (capture, scheduling, locale, onboarding, taxonomy)  `size: M`
**Slice:** Implement the automatable matrix rows that exercise on-device/local behavior — capture-path, projection-bound scheduling constraint, locale normalization, onboarding sequencing, and the error taxonomy — as unit/Robolectric tests running under the fDroidLocal unit gate.
**Definition of Done:**
- [ ] The capture, scheduling-constraint, locale, onboarding, and error-taxonomy rows from P5-T08a are realized as unit/Robolectric tests that run under testFDroidLocalDebugUnitTest and pass.
- [ ] Each test references its matrix criterion id (in name or annotation) so the matrix-to-test mapping is verifiable.
- [ ] Rows that are genuinely manual-only (e.g. live MediaProjection burst) are NOT faked as automated; the test suite documents the deferral to the manual procedure in P5-T08a.
- [ ] Running testFDroidLocalDebugUnitTest yields all-pass for these rows on the fDroidLocal variant.
**Validation:**
```sh
./gradlew testFDroidLocalDebugUnitTest
```
**Traceability:** Phase focus: MVP acceptance rows (local/on-device); core:observation, core:scheduling, core/smart/detection, core/smart/processing, core/common/permissions
**Dependencies:** P5-T08a

#### [ ] P5-T08c — Realize cloud acceptance rows (sync, heartbeat, remote command)  `size: M`
**Slice:** Implement the automatable matrix rows that exercise cloud behavior — offline-tolerant sync, heartbeat capture-health/setup-state, and remote START/PENDING_CONSENT — as unit/Robolectric tests running under the cloud connectivity unit gate, since the cloud code links only in the cloud variant.
**Definition of Done:**
- [ ] The sync, heartbeat, and remote-command rows from P5-T08a are realized as unit/Robolectric tests that run under testPlayStoreCloudDebugUnitTest (the cloud connectivity variant where core:network/feature:cloud link) and pass.
- [ ] Each test references its matrix criterion id so the matrix-to-test mapping is verifiable; no cloud row is asserted on a local variant where the code is absent.
- [ ] Cloud rows that are manual-only (e.g. dashboard rendering against a live backend) are clearly deferred to the P5-T08a manual procedure, not faked.
- [ ] Running testPlayStoreCloudDebugUnitTest yields all-pass for these rows on the cloud variant.
**Validation:**
```sh
./gradlew testPlayStoreCloudDebugUnitTest
```
**Traceability:** Phase focus: MVP acceptance rows (cloud); Blueprint connectivity flavor / cloudImplementation gating (lines 189, 368-371); core:network, feature:cloud, core:observation
**Dependencies:** P5-T08a, P5-T07

## Risks

| ID | Risk | Likelihood | Impact | Mitigation |
|----|------|-----------|--------|------------|
| R1 | Headless MediaProjection / capture-session limits: no fully unattended, reboot-surviving headless projection exists on stock Android. MediaProjection requires a per-session user grant and Android 14+ can re-prompt or tear down the session. The 'remote start within one poll cycle' gate and kiosk fleets are most exposed. | High | High | Keep the projection session alive for the lifetime of a tracking session and gate frame PROCESSING (not start/stop) per poll, so '5s interval' means 'sample one frame every 5s from a live session'. Capture the one-time MediaProjection grant + AccessibilityService enablement at enrollment and rely on OEM/MDM for persistence (deployment constraint). Treat projection loss as first-class via the existing setProjectionErrorHandler (SmartProcessingRepositoryImpl.kt:141/152) -> emit an Observation with isFulfilled=false + low/zero confidence so a torn-down session is a syncable dashboard signal. Residual: High; escalate via Q4. |
| R2 | OCR accuracy variance + Text-value JNI extension: accuracy depends on font/contrast/scaling/alphabet. The recognized string never crosses JNI today (detectTextNative returns a 7-element DoubleArray; the string is a transient local in text_recognizer.cpp:181 consumed by TextMatcher then discarded). No lastRecognizedText()/per-instance C++ storage exists; both must be newly written. UTF-8 vs modified-UTF-8, local-ref leaks, and null returns can crash natively. | High | Med | Surface first-class Int confidence 0-100 and dashboard low-confidence distinction + optional audit crop. Adopt the jobject/NativeTextResult wrapper carrying a jbyteArray decoded with Charsets.UTF_8 (NOT NewStringUTF) because CJK/Arabic/Cyrillic are explicit targets. Text reads already select alphabet per-condition (ConditionsVerifier.kt:241) so no build work to choose the Text recognizer; only Number is pinned to the first-loaded model. Manage local refs explicitly and null-guard the accessor. Residual: Med, mostly non-Latin alphabets. |
| R2b | ProcessedConditionResult.Screen carries no value field: it has only isFulfilled, haveBeenDetected, condition, confidenceRate, position, size (ProcessedConditionResult.kt:37-44). verifyNumberCondition reads detectionResult.numberDetected (ConditionsVerifier.kt:201) but discards it; the Screen built at 218-225 never copies it. Any value-bearing observation, Number included, is blocked until this is fixed. | Med | High | Extend Screen with nullable numberDetected: Double? = null + recognizedText: String? = null (D3b) and populate both verify methods; null defaults keep legacy Image/Color callers compiling. Mandatory and independent of Text/JNI work. Residual: Low for Number (pure additive Kotlin), Med for Text (gated on R2); impact rated High because without it the product ships no values. |
| R3 | Google Play rejection: the app is a screen-reading AccessibilityService using MediaProjection + SYSTEM_ALERT_WINDOW, the most-scrutinized permission cluster. accessibilityservice.xml declares canRetrieveWindowContent, canPerformGestures, canRequestFilterKeyEvents all true. The hard policy story is window-content reading + capture + cloud upload. | High | High | Lean on the flavor split so the connected build need not ride the Play listing (playStoreLocalRelease keeps the compliant listing). Narrow the connected flavor to drop unused canPerformGestures (read-only MVP). Add prominent in-app disclosure + privacy policy. Realistic path is distributing the cloud product outside Play (direct APK/MDM); a product-owner decision via Q2. Residual: High. |
| R4 | Battery, foreground-service & WorkManager bootstrap: live projection + continuous 5s frame acquisition across always-on fleets is power-hungry and collides with Doze/standby. No WorkManager exists today (no androidx.work, no hilt-work) so it is a net-new dependency AND net-new bootstrap; WorkManager's 15-minute periodic floor is coarser than the 5s requirement. | Med | Med | Do NOT use WorkManager periodic for the 5s loop; use it for coarse reliable jobs (sync flush, command pull, session re-arm, heartbeat) and drive 5s sampling from the live foreground service. Budget the HiltWorkerFactory + WorkManager.initialize bootstrap (via per-flavor Application classes) as its own small integration risk. Reuse the existing foreground-service lifecycle (LocalService/startForegroundMediaProjectionServiceCompat). Make interval and crop-capture owner-tunable. |
| R5 | GPLv3 boundary mistakes: every source file carries the GPLv3 (v3-or-later) header. The connected build links new core:network/feature:cloud and talks to a proprietary backend; risk is static combination of proprietary code into the GPLv3 binary or pulling a GPL-incompatible dependency. | Med | High | Flavor gating is the license firewall: network deps enter only via cloudImplementation (mirrors proven playStoreImplementation in DependencyHandlerScopeExt.kt/CrashlyticsConventionPlugin.kt). Make 'no network classes in fDroidLocalRelease' a hard CI acceptance gate. The connected client is itself GPLv3 (intended); the backend (Trax Cloud) is a separate program over a /v1 network boundary, not a derivative work; keep it a protocol not a linked library and copy no GPLv3 code server-side. Vet every new dep; Retrofit/OkHttp/kotlinx-serialization are permissive. |
| R6 | Migration 21->22 & append-only integrity: a new Observation table requires bumping DATABASE_VERSION 21->22 (verified at 21) and wiring Observation::class into the hardcoded @Database entities list plus an ObservationDao, indexed by scenarioId/deviceCapturedAt. | Low | Med | Add-only table is the simplest migration class. Ship ONE manual object Migration21to22 : Migration(21,22) in SmartDatabaseModule.providesClickDatabase addMigrations(...), NOT an AutoMigration, and do not also append an AutoMigration. Keep the DAO append-only (insert + read, prune only post-sync). Acceptance gate only requires existing scenarios/conditions survive. |
| R7 | Offline sync idempotency / duplicates / prune-vs-ack ordering: classic at-least-once delivery. The distinct gap is that the client must not prune a local Observation until it holds a confirmed per-record server ack; optimistic prune on a lost/rejected write is silent data loss the server cannot fix. | Med | Med | Use a single client-generated String UUIDv4 as Room @PrimaryKey, wire id, cloud PK, AND idempotency/dedup key; server ON CONFLICT (tenant_id, id) DO NOTHING handles duplicate delivery. Ack-gated prune handles lost delivery: treat ambiguous/timeout as not-acked and retry; row stays PENDING. Reuse BackupEngine/BackupRepository channelFlow/progress patterns. |
| R8 | Authoring-density vs runtime-density mismatch: detectionArea is stored in absolute coordinates; ScalingManager.startScaling() maps detection-quality to current display size but does NOT reconcile author-device vs target-device resolution, so a region can land on wrong/off-screen pixels across a heterogeneous fleet, silently degrading every read. | Med | Med | Flag Observations from devices whose dimensions differ from the author device as suspect/low-confidence (backup format already records screenWidth/screenHeight and raises a screenCompatWarning). Document an 'author on a matching-resolution device' constraint. A true proportional author-rect->target-rect transform is out of MVP scope (Q6). Residual: Med, bounded for homogeneous fleets, sharp for heterogeneous. |
| R9 | Legal-basis / consent gap for the fleet case where the monitored device's user is not the account owner (employee/kiosk monitoring): flagged as the highest-risk GDPR scenario with no product flow designed. | Med | High | Surface hosting region in the privacy policy and require explicit operator attestation of lawful basis at enrollment. Treat employee/kiosk monitoring legal-basis design as a product-owner-owned gate before targeting that segment; do not market to it until a consent flow exists. Escalate as an unowned cross-cutting gap. |

## Decision Records

### D1 — MVP is read-only ('capture, don't act'): no ActionExecutor in tracking mode.
- Rationale: Shrinks the risk surface dramatically: no gesture liability, a simpler Play-policy story (read-only justifies dropping canPerformGestures), and a smaller blast radius. Highly reversible because the action/gesture engine stays in the codebase and can be layered on post-MVP.
- Alternatives considered: Ship read-and-act in MVP (rejected: multiplies Play-policy scrutiny and liability for no MVP value).

### D2 — Reuse the existing detection pipeline, capturing at SmartProcessingListener.onScreenConditionProcessingCompleted and reusing ScreenCondition.Number/Text/Color + ConditionsVerifier.
- Rationale: Extend-not-rebuild: the frame-acquisition -> scaling -> OCR -> verification stack is battle-tested, so a single new observation seam is far cheaper and safer than a parallel capture path.
- Alternatives considered: Build a new standalone capture/OCR pipeline (rejected: duplicates a working stack and inherits none of its tuning). Trade-off accepted: inherits MediaProjection/AccessibilityService coupling (R1) and a result object with no value field (forces D3b).

### D3 — Surface OCR text via a targeted JNI extension carrying a NativeTextResult jobject with a jbyteArray decoded as Charsets.UTF_8, capturing the string in per-instance C++ Detector storage; not a result-type rewrite.
- Rationale: Confines the blast radius to the Text path while the proven 7-element DoubleArray (Image/Color/Number, used by the legacy auto-clicker) stays byte-identical. The byte[]/UTF-8 wrapper is chosen over NewStringUTF/a separate jstring accessor because CJK/Arabic/Cyrillic are explicit targets and modified-UTF-8 would corrupt them.
- Alternatives considered: Separate jstring accessor via NewStringUTF (rejected per Addendum 2: breaks non-Latin). Full ProcessedConditionResult type rewrite (rejected: large blast radius across the legacy auto-clicker).

### D3b — Extend ProcessedConditionResult.Screen with nullable numberDetected: Double? = null + recognizedText: String? = null and populate both verify methods.
- Rationale: This is the mandatory link that unblocks every value-bearing Observation (Number included). Null defaults keep all legacy Image/Color/Number producers and the legacy auto-clicker compiling unchanged: additive, low blast radius.
- Alternatives considered: New dedicated result subtype for tracking (rejected: forks the listener contract and duplicates verify logic). Trade-off accepted: widening a domain type shared with the legacy auto-clicker.

### D4 — Add a new flavor DIMENSION KlickrDimension.CONNECTIVITY (LOCAL default / CLOUD) composing with the existing VERSION dimension (e.g. fDroidLocalRelease, playStoreCloudRelease); network deps enter only via cloudImplementation and CI hard-fails if network classes appear in fDroidLocalRelease.
- Rationale: The connectivity flavor split is the chosen license firewall: it keeps the F-Droid/local build provably network-free and GPLv3-clean while allowing a connected build, mirroring the proven playStoreImplementation pattern (DependencyHandlerScopeExt.kt/CrashlyticsConventionPlugin.kt). The separate non-GPL backend is reached only over /v1 REST.
- Alternatives considered: Separate fork for the cloud product (rejected: divergence/maintenance cost). Runtime feature flag with network deps always linked (rejected: defeats the license firewall because GPLv3 binary would statically combine network code).

### D5 — WorkManager for coarse reliability; in-service timer for the 5s cadence.
- Rationale: WorkManager's 15-minute periodic floor cannot meet a 5s sampling requirement, so the 5s loop is driven from the live foreground service while WorkManager handles sync flush, command pull, session re-arm, and heartbeat. Net-new HiltWorkerFactory + WorkManager.initialize bootstrap via per-flavor Application classes; local's core:scheduling is present but inert by design (so fDroidLocal cannot run scheduled tracking).
- Alternatives considered: WorkManager periodic for 5s sampling (rejected: 15-min floor makes it impossible). AlarmManager exact alarms for 5s (rejected: battery/Doze hostility and OS throttling vs reusing the already-live foreground service).

### D6 — Package names stay com.buzbuz.smartautoclicker.* (brand rename only).
- Rationale: Avoids churning the R file and the obfuscation pipeline; applicationId stays dynamically randomized. The obfuscation shouldRandomize gate already excludes all but F-Droid, so the cloud flavor's stable component identity is preserved by construction.
- Alternatives considered: Full package rename to a TraxIntel namespace (rejected: large mechanical churn across R-file and obfuscation tooling for zero MVP value).

### D7 — Observations are append-only (insert + read + post-sync prune only); pruning is gated on a confirmed per-record server ack.
- Rationale: Keeps Migration 21->22 trivial (add-only table) and sync idempotent. Ack-gated pruning prevents silent data loss on lost/rejected writes that the server cannot repair (R7); retention pruning is handled by a retention worker rather than foreign-key CASCADE.
- Alternatives considered: Mutable rows with in-place status churn + optimistic prune on send (rejected: optimistic prune is silent data loss). Foreign key to scenario_table with CASCADE (rejected per Addendum: scenarioId is the cloud scenario UUID String, not a local Long, so CASCADE is void).

### D8 — One Observation schema keyed by a single client-generated String UUIDv4 used as Room @PrimaryKey, wire id, cloud PK, and idempotency/dedup key; valueType in {NUMBER, TEXT, STATE}; confidence Int 0-100 via Math.round(...).toInt() once at capture; syncState in {PENDING, UPLOADING, SYNCED, FAILED}.
- Rationale: A single UUID minted once per listener fire (persisted before upload) collapses primary-key, wire-id, and idempotency into one column, so server ON CONFLICT (tenant_id, id) DO NOTHING gives exactly-once semantics with no separate idempotencyKey column or Long autoincrement.
- Alternatives considered: Long autoincrement PK + separate idempotencyKey column (rejected per Addendum: redundant and lets local and wire ids diverge). COLOR_STATE valueType (rejected: server-side rejection; State uses value='detected'|'not_detected').

## Rollback

- Disable the connected product without touching the compliant listings: stop publishing the *Cloud flavors and continue shipping fDroidLocalRelease/playStoreLocalRelease unchanged; the CI gate guarantees the local builds never linked core:network/feature:cloud, so no code removal is required.
- Revert read-only to no-op: because D1 keeps the action/gesture engine in the codebase, an operator can simply not enable tracking mode; no migration or schema change is needed to back out tracking.
- Back out a bad /v1 protocol shape on an unmanaged fleet: the server returns 426 Upgrade Required (hard) or clientStatus:'deprecated' (soft); the client refuses to open a DB newer than its compiled DATABASE_VERSION and shows an update wall instead of crashing. Operator action: flip the server flag to force-upgrade or deprecate the broken protocol version.
- Handle revoked/expired credentials operationally: on 401 (token revoked) stop workers, clear the queue, and surface 're-pair'; on 410 (expired pairing code) re-issue a code. Operator action: revoke the device token server-side to halt a misbehaving device within rate-limit windows (tokens are revocable-not-secret, no refresh flow in MVP).
- Stop a runaway or misconfigured device remotely: issue a remote STOP command (halts within one command-poll cycle) or revoke its token; the in-service 5s timer ceases sampling while the projection session is released.
- Roll back the database: Migration 21->22 is purely additive (add-only Observation table), so downgrading code is safe for existing scenarios/conditions; if the Observation table must be removed, an operator runs a forward Migration22to23 that drops observation_table (never an in-place edit of 21->22). Existing scenario/condition data is untouched either way.
- Disable crop capture and uploads fleet-wide: crop capture defaults OFF; operator leaves it off (or pushes the owner-tunable setting to OFF) to eliminate PNG storage/upload, with 14-day crop retention pruning any already-captured crops.
- Pause ingestion under load: server returns 429 + Retry-After and the client backs off with rows staying PENDING (never pruned), so an operator can throttle or pause the ingest endpoint without data loss and resume later.

## Evidence Ledger

Append one row whenever a task is completed. A task box may be checked only after its row exists here.

| Date | Task ID(s) | Jira | Files changed | Validation commands | Result | Confluence sync |
|------|-----------|------|---------------|---------------------|--------|-----------------|
| | | | | | | |

## Final Gate Checklist

- [ ] Phase P0 — all tasks complete (P0-T01…P0-T08), exit criteria + validation gates passed, evidence recorded
- [ ] Phase P1 — all tasks complete (P1-T01…P1-T10), exit criteria + validation gates passed, evidence recorded
- [ ] Phase P2 — all tasks complete (P2-T01…P2-T12), exit criteria + validation gates passed, evidence recorded
- [ ] Phase P3 — all tasks complete (P3-T01…P3-T09), exit criteria + validation gates passed, evidence recorded
- [ ] Phase P4 — all tasks complete (P4-T00…P4-T09), exit criteria + validation gates passed, evidence recorded
- [ ] Phase P5 — all tasks complete (P5-T01…P5-T08c), exit criteria + validation gates passed, evidence recorded
- [ ] ACMRI lint passes: `python3 scripts/acmri_lint.py documentation/TRAXINTEL_MVP_ACMRI_2026-06-29.md`
- [ ] All risks resolved or explicitly deferred with rationale
- [ ] Open product questions (what TraxIntel tracks, distribution channel, backend hosting) answered or deferred
- [ ] Privacy policy + Play data-safety disclosures drafted (or off-Play distribution chosen)
- [ ] GPLv3 source-availability obligation met for the distributed app binary

# TASKS.md

Task board and handoff log. Status values: `todo`, `doing`, `written` (code exists, never compiled or run), `done` (verified), `blocked`. Tiers are defined in `AGENTS.md` section 8.

`written` is not `done`. A task moves to `done` only after CI builds it and, where it says so, the owner has checked it on a phone.

## Open questions for the owner

- Q2: Final app name and applicationId (currently "ScreenOff Recorder", `dev.screenoff.recorder`). Changing the applicationId later means a fresh install.
- Q3: Storage cap and default quality he actually wants (defaults are 4 GB and HD).
- Q5: Is the volume-key trigger wanted enough to accept an accessibility service?
- Q6: Which GitHub repository this goes in, so CI can build the APK.

Answered: Q4. Audio on by default, sound is the main point (owner, 2026-11-11). Q1. Devices are a Xiaomi on HyperOS 3.0 / Android 16, and Samsung A20 and A21 on Android 12.

## Phase 0: Foundation

| ID | Task | Lane | Tier | Status |
|---|---|---|---|---|
| T-001 | Gradle project, version catalog, wrapper, `:core` and `:app` modules, CI workflow | CONTRACT | T2 | done (emulator) |
| T-002 | First CI run green: fix whatever the compiler and dependency resolution report | ENGINE | T1 | done (emulator) |
| T-003 | Manifest, permissions, resources, icons | ENGINE | T3 | done (emulator) |

## Phase 1: Record with the screen off

| ID | Task | Lane | Tier | Status |
|---|---|---|---|---|
| T-010 | `RecorderMachine` and its tests | ENGINE | T1 | done |
| T-011 | `RecorderService`: foreground start, event loop, effects, wake lock | ENGINE | T1 | done (emulator) |
| T-012 | `CameraController` on CameraX Recorder, no Preview, quality fallback | ENGINE | T1 | done (emulator) |
| T-013 | `ConfigStore` | ENGINE | T2 | done (emulator) |
| T-014 | `TriggerActivity`, `Triggers`, `Permissions` | ENGINE | T1 | done (emulator) |
| T-015 | Home screen with permission flow and battery prompt | UI | T2 | written |

Acceptance (owner, on device): start from the app, turn the screen off for 10 minutes, stop, the clips play and add up to about 10 minutes. The notification is visible the whole time.

## Phase 2: Folder and loop

| ID | Task | Lane | Tier | Status |
|---|---|---|---|---|
| T-020 | `StorageGateway`: staging, publish to app folder or chosen folder, delete | ENGINE | T1 | done (emulator) |
| T-021 | `SegmentIndex` (JSON) and startup reconcile | ENGINE | T2 | done (emulator) |
| T-022 | `EvictionPolicy` and its tests | ENGINE | T1 | done |
| T-023 | Rotation and eviction wired into the service | ENGINE | T1 | done (emulator) |
| T-025 | Settings screen | UI | T2 | written |
| T-026 | Library screen: list, open, lock, delete | UI | T2 | written |

Acceptance (owner): with a 1 GB cap and 1 minute segments, recording runs past the cap, the oldest clips disappear, kept clips stay, the total stays under the cap. Repeat with a chosen folder.

## Phase 3: Triggers

| ID | Task | Lane | Tier | Status |
|---|---|---|---|---|
| T-030 | Quick Settings tile with live state | ENGINE | T2 | written |
| T-031 | Home-screen widget | ENGINE | T2 | written |
| T-032 | Launcher shortcuts | ENGINE | T3 | written |
| T-033 | Notification actions: Stop, Keep this clip | ENGINE | T2 | written |
| T-034 | Setting to enable or disable each trigger surface | ENGINE + UI | T2 | todo |
| T-035 | Device check on HyperOS and on Samsung Android 12: does each surface start recording from the home screen and from the lock screen | owner | n/a | todo |
| T-036 | Experimental volume-key trigger via accessibility service, off by default | ENGINE | T1 | blocked on Q5 |

## Phase 4: Security cam mode

| ID | Task | Lane | Tier | Status |
|---|---|---|---|---|
| T-040 | `MotionDetector` and its tests | ENGINE | T2 | done |
| T-042 | `ImageAnalysis` wired in, ARMED state, post-roll | ENGINE | T1 | done (emulator) |
| T-043 | Motion settings in the UI | UI | T3 | written |
| T-044 | Fallback when the camera cannot run video and analysis together (analysis only while armed, rebind on motion) | ENGINE | T1 | done (emulator) |
| T-045 | Tune sensitivity defaults on the real phones | owner + ENGINE | T2 | todo |

Acceptance (owner): phone on a shelf in motion mode, nothing recorded for 5 quiet minutes, walking past creates one clip that ends about 10 seconds after leaving.

## Phase 5: Hardening

| ID | Task | Lane | Tier | Status |
|---|---|---|---|---|
| T-050 | Auto-stop: low battery, thermal, storage, recorder error | ENGINE | T1 | written |
| T-051 | Battery-optimization prompt | UI | T3 | written |
| T-053 | README for the owner | CONTRACT | T3 | written |
| T-054 | T1 review of all deletion, service-start and permission code after the first green build | ENGINE | T1 | todo |
| T-055 | Robolectric or instrumented tests for `SegmentIndex` and `StorageGateway` | ENGINE | T2 | todo |
| T-060 | Stable signing so new builds install over old ones (key supplied by the owner as a CI secret, never committed) | CONTRACT | T2 | todo |

## Later (not scheduled)

- MediaCodec + MediaMuxer writer: gapless rotation, motion pre-roll.
- Direct write into the chosen folder (no copy).
- Detection zones, schedule, timestamp overlay.

## Handoff log

Newest entry first. Format is in `AGENTS.md` section 10.

### 2026-11-11, Claude (chat session), ENGINE + CONTRACT
Done: T-002 (CI green on the first compile), T-044 (motion fallback: analysis alone while armed, video alone while recording, used when the camera cannot bind both). CI now has three jobs: build, smoke (emulator, API 31 and 36), publish.
Verified on emulators by `scripts/smoke.sh`: install, open the UI, start through `TriggerActivity`, record 70 s with the screen off, two rotations at 30 s, stop, three playable-size clips in the index, staging empty, then motion mode (split fallback path) producing clips, no crash, no error notification.
"done (emulator)" in the tables means exactly that. It is not a phone result.
Not verified: anything on the real phones. The combined video + analysis path (emulator cameras only exercise the fallback). Tile, widget, launcher shortcuts, notification actions, chosen folder (SAF), eviction on device, Compose screens beyond launching, HyperOS background limits.
Flake seen once: on API 36 the first start did not happen in one of four runs, before app logging existed. Cause not confirmed. The test now pauses 3 s after HOME and polls. If it comes back, read the `ScreenOff` log tag.
Signing: CI keeps one debug keystore in the Actions cache (`debug-keystore-v1`) or takes it from the `DEBUG_KEYSTORE_B64` secret when set. If the cache is evicted and no secret is set, the next APK has a new key and needs an uninstall first.
Download: https://github.com/AlexandreC1/screenoffNAC/releases/latest/download/screenoff-recorder.apk (only updated when smoke passes).
Next: owner runs the README checklist on the Xiaomi. Then T-035, T-045.

### 2026-11-10, Claude (chat session), ENGINE + CONTRACT
Done: T-010, T-022, T-040 (26 unit tests in `:core` pass, compiled and run with Kotlin 2.0.21 and JUnit 4.13.2).
Written, not compiled: everything else marked `written`. The sandbox had no Android SDK and no access to Google Maven, so `:app` has never been built. Expect compile errors on the first CI run.
Contract changes: stack changed from Flutter + Kotlin plugin to pure Kotlin + Compose at the owner's request ("best stack, not the familiar one"). Room, DataStore and Pigeon dropped. See AGENTS section 2.
Delegation: the five `ui/*.kt` files were written by a mid-tier subagent from a written brief, then reviewed (deletion path and start path checked by reading).
New dependencies: all in `gradle/libs.versions.toml`. CameraX 1.6.2 was confirmed current. AGP 8.13.0, Kotlin 2.2.20, Compose BOM 2025.09.00, core-ktx 1.17.0, lifecycle 2.9.4, activity-compose 1.11.0, documentfile 1.1.0, coroutines 1.10.2 are from memory and unverified.
Not verified: Gradle sync, compilation of `:app`, every on-device behavior. APIs most worth a second look: `ProcessCameraProvider.awaitInstance`, `LifecycleResumeEffect`, whether launcher shortcuts can reach the non-exported `TriggerActivity`, whether the translucent trampoline counts as visible on HyperOS.
Next: T-002. Push to GitHub, read the CI log, fix, repeat until the APK builds. Then the owner runs the Phase 1 acceptance test.

# AGENTS.md

Shared instructions for every coding agent working in this repo (Claude Code, ChatGPT/Codex, and any subagent they spawn). Read this file, then `ARCHITECTURE.md`, then `TASKS.md`, before changing anything.

## 1. What we are building

Working name: `screenoff_recorder` (rename is the owner's call).

A personal Android app for one user (the owner, Nick) that:

1. Records video while the screen is off, from a foreground service.
2. Starts and stops from a trigger the owner picks (Quick Settings tile, widget, launcher shortcut, notification action; volume-key combo is experimental).
3. Saves into a folder the owner allocates.
4. Loops: recording is split into segments and the oldest segments are deleted when a storage cap is reached.
5. Optional security cam mode: record only when motion is detected.

It is a personal tool, not a Play Store product. No accounts, no network, no ads, no analytics.

## 2. Stack (decided, do not relitigate without the owner)

The owner does not read the code, so the stack is chosen for reliability, not familiarity. Pure native, no Flutter, no bridge.

| Layer | Choice |
|---|---|
| Language | Kotlin 2.2 |
| UI | Jetpack Compose with Material 3. No navigation library, no ViewModel library, no icon pack |
| Camera | CameraX 1.6.2 (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-video`) |
| Logic | `:core`, a pure Kotlin JVM module with no Android types: state machine, eviction policy, motion detector |
| Config | SharedPreferences behind `ConfigStore` (typed, exposed as a StateFlow) |
| Clip index | One JSON file behind `SegmentIndex` (atomic write). No database |
| Build | Gradle 8.14.3 wrapper, AGP 8.13.0, version catalog in `gradle/libs.versions.toml`, compileSdk/targetSdk 36, minSdk 26 |
| CI | GitHub Actions: runs `:core:test`, builds the debug APK, uploads it as an artifact |

Rule of thumb: every dependency is a thing that can break a build nobody is watching. Prefer the platform and the standard library. No code generation (no KSP, no Room, no Hilt).

Key consequence of the design: the tile, widget and shortcuts start the service when no UI exists. All recording logic and state live in the service and in `:core`. The UI only reads StateFlows and calls `Triggers`.

## 3. Repo layout

```
AGENTS.md  CLAUDE.md  ARCHITECTURE.md  TASKS.md  README.md
core/                                   pure Kotlin, unit tested
  src/main/kotlin/dev/screenoff/core/   Model, RecorderMachine, EvictionPolicy, MotionDetector
  src/test/kotlin/dev/screenoff/core/   JUnit 4 tests
app/src/main/
  AndroidManifest.xml
  kotlin/dev/screenoff/recorder/
    App.kt                              App, Graph (singletons), RecorderBus (live state), Actions
    data/                               ConfigStore, SegmentIndex, StorageGateway
    service/                            RecorderService, CameraController, Notifier
    triggers/                           Triggers, Permissions, TriggerActivity, tile, widget
    ui/                                 MainActivity, Home, Library, Settings, Format
  res/
.github/workflows/build.yml
```

## 4. Lanes (who owns what)

Two agents work in parallel, so ownership is by directory. Do not edit outside your lane without a note in the handoff log.

| Lane | Owns | Default agent |
|---|---|---|
| ENGINE | `core/**`, `app/**/service/**`, `app/**/data/**`, `app/**/triggers/**`, `AndroidManifest.xml` | Claude |
| UI | `app/**/ui/**`, `app/src/main/res/**` | ChatGPT/Codex |
| CONTRACT | `core/**/Model.kt`, `app/**/App.kt`, the public functions of `Triggers`, Gradle files, `ARCHITECTURE.md` | Whoever holds the task, one at a time |

The owner can swap lanes at any time. If lanes are swapped, update this table in the same commit.

Contract rule: the UI talks to the engine only through `Graph.config`, `Graph.index`, `Graph.storage`, `RecorderBus.status`, `Triggers` and `Permissions`. A change to any of those signatures is its own commit, lands before the code that uses it, and is recorded in the handoff log.

## 5. Hard rules

1. The foreground-service notification is always visible while the camera is in use. Never try to hide, disguise, or suppress it. No stealth features (fake icons, hiding from the launcher, disguised app names).
2. No network permission in the manifest. Nothing leaves the device.
3. Never delete a segment marked locked. Only delete a file through `StorageGateway.delete` with a URI taken from `SegmentIndex`. Never list a folder and delete what is in it.
4. Recording starts only through `Triggers.start`, called while an activity of this app is visible (`MainActivity` or `TriggerActivity`, see ARCHITECTURE section 5). Do not start the camera service from a broadcast receiver or from `BOOT_COMPLETED`.
5. Do not add dependencies without listing them, with the reason, in the handoff log.
6. Do not invent facts about the owner, his device, or his preferences. If something is unknown, record it under Open questions in `TASKS.md` and pick the safe default.
7. No secrets, keystores, or personal data in the repo.

## 6. Commits and PRs

- Small commits, one task ID per commit: `T-022: loop eviction with cap and lock flag`.
- No AI attribution anywhere: no `Co-Authored-By`, no "Generated with" lines, no agent names in commit messages or PR bodies.
- Do not use em dashes in documentation or UI strings.
- Do not push, force-push, or rewrite history unless the owner asks.

## 7. Commands

```
./gradlew :core:test            # pure logic tests, no Android SDK needed
./gradlew :app:assembleDebug    # debug APK in app/build/outputs/apk/debug/
./gradlew :app:lintDebug        # optional
```

CI runs the first two on every push and uploads the APK as the artifact `screenoff-recorder-debug-apk`.

A task is not done until the relevant command passes. If you cannot run it in your environment, say so in the handoff log. Do not claim a pass you did not see.

No Android SDK in your sandbox: you can still compile and test `:core` with any Kotlin compiler and JUnit 4, because it has no Android types. Do that at minimum.

## 8. Model tiers and delegation

Every task in `TASKS.md` carries a tier. Use the cheapest tier that does not lower quality.

| Tier | Use for | Examples |
|---|---|---|
| T1 (top model) | Design, concurrency, lifecycle, anything touching the service state machine, camera binding, or deletion logic. Reviews of T2/T3 work | Service state machine, loop eviction, trigger eligibility |
| T2 (mid model) | Well-specified implementation with tests | Compose screens, tile and widget, tests for an existing design |
| T3 (small model) | Mechanical work | Strings, resource XML, docs sync, test fixtures, version bumps |

Delegation rules:

- A T1 agent may hand T2/T3 tasks to subagents on lower tiers. Give the subagent the task ID, the exact files, and the acceptance criteria. Review the diff before marking done.
- Escalate upward when a lower-tier attempt fails twice on the same task. Do not loop.
- Never delegate hard-rule-sensitive code (deletion, service start, permissions) below T1 without a T1 review.

## 9. Budget rule

At roughly 75% of your session's token or context budget:

1. Stop starting new tasks.
2. Finish the current task if it is close, otherwise revert it to a compiling state.
3. Write a handoff entry (section 10).
4. Either stop, or continue only by delegating remaining T2/T3 tasks to lower-tier subagents.

If you cannot measure your budget, treat "three tasks completed in this session" or "context is getting compacted" as the signal.

## 10. Handoff protocol

Before ending a session, append to the Handoff log in `TASKS.md`:

```
### YYYY-MM-DD, <agent>, <lane>
Done: T-0xx, T-0yy
In progress: T-0zz (state, what is left)
Contract changes: none | describe
New dependencies: none | name, version, reason
Not verified: what you could not build or test, and why
Next: suggested next task
```

Update task statuses in the same commit. The other agent reads the last two entries before starting.

## 11. Definition of done

- Acceptance criteria in `TASKS.md` are met.
- Tests exist for logic that can be tested off-device (eviction, motion, state machine, config).
- On-device behavior that cannot be tested in CI is listed under "Not verified" for the owner to test.
- No hard rule is broken. Docs match the code.

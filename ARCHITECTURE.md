# ARCHITECTURE.md

Design for `screenoff_recorder`. Changes to this file follow the CONTRACT lane rules in `AGENTS.md`.

## 1. Platform constraints that shape the design

From the Android documentation (links in section 10). Re-check them if targetSdk changes.

- Camera use in the background requires a foreground service with `foregroundServiceType="camera"` (plus `microphone` when audio is on), the `FOREGROUND_SERVICE_CAMERA` permission, and a granted `CAMERA` runtime permission.
- `CAMERA` is a while-in-use permission. On Android 14+ a camera foreground service cannot be created while the app is in the background: the start must happen while the app has a visible activity, apart from a short list of exemptions.
- Apps targeting Android 15+ cannot launch a camera foreground service from a `BOOT_COMPLETED` receiver. There is no "resume recording after reboot" without the owner tapping something.
- Once started correctly, the service keeps camera access after the screen turns off.

Design consequence: every start path runs while one of our activities is visible (section 5).

Target devices (stated by the owner): Xiaomi on HyperOS 3.0 / Android 16 (primary), Samsung A20 and A21 on Android 12.

## 2. Modules and components

`:core` (pure Kotlin, no Android types, unit tested):

| Component | Responsibility |
|---|---|
| `Model.kt` | `RecorderConfig`, `Segment`, `RecorderStatus`, enums. `VideoQuality` carries the encoder bitrate |
| `RecorderMachine` | State machine. Takes events, returns effects. No I/O |
| `EvictionPolicy` | Chooses which clips to delete so the next segment fits |
| `MotionDetector` | Frame-difference detector over a luma grid |

`:app`:

| Component | Responsibility |
|---|---|
| `Graph` | Process singletons: `config`, `index`, `storage`, and the single file thread (`io`, `ioDispatcher`) |
| `RecorderBus` | `StateFlow<RecorderStatus>`. IDLE whenever the service is not running |
| `RecorderService` | `LifecycleService`, foreground. Runs the machine, executes its effects, wake lock, battery and thermal auto-stop |
| `CameraController` | CameraX binding to the service lifecycle. No Preview use case |
| `Notifier` | Ongoing notification (Stop, Keep this clip) and error notifications |
| `ConfigStore` | SharedPreferences, typed, exposed as StateFlow |
| `SegmentIndex` | JSON file of finalized clips, exposed as StateFlow |
| `StorageGateway` | Staging files, publishing into the library, delete, FileProvider URIs |
| `Triggers`, `Permissions` | The only start/stop entry points, and the runtime permission list |
| `TriggerActivity` | Invisible trampoline for tile, widget, shortcuts |
| `RecorderTileService`, `RecorderWidget`, `res/xml/shortcuts.xml` | Trigger surfaces |
| `ui/*` | Compose: Home, Library, Settings. Reads StateFlows, calls `Triggers` |

## 3. State machine (`RecorderMachine`)

```
IDLE --Start(continuous)--> RECORDING            effects: OpenCamera, StartSegment
IDLE --Start(motion)------> ARMED                effects: OpenCamera
ARMED --Motion-----------> RECORDING             effects: StartSegment
RECORDING --Tick, segment length reached--> (rotate) StopSegment, then on finalize StartSegment
RECORDING --Tick, quiet for postRoll (motion)--> StopSegment, then on finalize ARMED
RECORDING --Stop or Fail--> FINALIZING           effects: StopSegment
FINALIZING --SegmentFinalized--> IDLE            effects: CloseCamera, Finish(error?)
ARMED --Stop or Fail--> IDLE                     effects: CloseCamera, Finish(error?)
```

- Motion arriving while a post-roll stop is finalizing starts the next clip immediately instead of re-arming.
- A finalize that nobody asked for (camera lost) ends with an error.
- FINALIZING has a 15 second watchdog.
- The first error wins and is reported once, in `Finish(error)`.
- The service feeds every event through one `Channel` consumed on the main thread, so the machine never sees concurrent calls. Effects that suspend (opening the camera, eviction) hold the queue, which is what serializes them.

## 4. Recording pipeline

- CameraX `Recorder` + `VideoCapture`, H.264 in MP4. Quality selector with fallback to the nearest supported quality. Bitrate is set explicitly (SD 2, HD 5, FHD 10 Mbps) so storage use is predictable.
- Rotation is stop then start on a new file. This leaves a short gap between segments. Accepted for v1. A MediaCodec + MediaMuxer writer would remove the gap and allow motion pre-roll (see TASKS, Later).
- Motion mode keeps `VideoCapture` and `ImageAnalysis` both bound while ARMED and only starts a recording on motion, so there is no rebind delay at the moment that matters. Analysis runs at about 5 frames per second on a 320x240 stream.
- MP4 is only playable once finalized, so a crash loses the current segment. Short segments bound that loss. Default segment length: 3 minutes.
- Partial wake lock from camera open to finish.
- Auto-stop: battery at or below the threshold while unplugged, thermal status severe, storage full with only locked clips left, recorder error.

## 5. Triggers

`Triggers.start(context, mode)` is the only start path and must be called while an activity of this app is visible. Two callers:

- `MainActivity` (the Start button).
- `TriggerActivity`: translucent, no animation, excluded from recents, not exported, shown over the lock screen. It acts in `onResume` (so it is really visible), then finishes. If a permission is missing it opens the app instead.

| Surface | Mechanism |
|---|---|
| Quick Settings tile | `onClick`: stop directly if running, else `startActivityAndCollapse` to `TriggerActivity` (PendingIntent form on API 34+) |
| Home-screen widget | `PendingIntent.getActivity` to `TriggerActivity`, action TOGGLE |
| Launcher long-press shortcuts | `res/xml/shortcuts.xml`: Record now, Motion mode, Stop |
| Notification actions | Stop and Keep this clip, sent straight to the running service |
| Volume-key combo | Not built. Needs an accessibility service and key events are unreliable with the display off. Blocked on the owner (TASKS Q5) |

Stop never needs the trampoline because the service is already running.

## 6. Storage and loop

- Every segment is recorded to `…/Android/data/<app>/files/Movies/staging/` with a plain `File`, which is the most reliable CameraX output path.
- On finalize the file is published by `StorageGateway.publish`:
  - no folder chosen: renamed into `…/files/Movies/continuous|motion/` (instant);
  - folder chosen with the system picker (SAF tree URI): copied into `continuous/` or `motion/` under that folder, then the staging file is deleted. If the folder is unreachable the clip is kept in the app folder and the owner is notified.
- File name: `yyyyMMdd_HHmmss_<mode>.mp4`.
- Publishing runs on the single file thread. Before starting the next segment, and before the service stops, pending publishes are awaited, so the index is complete when eviction runs and no clip is cut off.
- Note: the default app folder is deleted by Android when the app is uninstalled. Choosing a folder avoids that.

Eviction (`EvictionPolicy.plan`, run before each new segment):

```
toFree = max(used + next - cap, freeFloor + next - deviceFree, 0)
victims = oldest unlocked clips until their sizes cover toFree
if not covered: recording stops with "Storage is full and only kept clips remain"
```

- `next` = (video bitrate + audio bitrate) x segment length, plus 15%.
- Locked clips count toward the cap and are never evicted. The service checks the lock flag again before deleting.
- Deletion only ever targets URIs stored in `SegmentIndex`. Nothing lists a folder and deletes its contents, except the private staging folder at startup (crash debris, unplayable by definition).
- At app start, when idle: staging is cleared and index rows whose file is gone are dropped.

## 7. Motion detection (`MotionDetector`)

1. Average the luma plane into a 32x24 grid (sampled, not every pixel).
2. Background per cell is an exponential moving average: fast for still cells, slow for changed cells.
3. A cell is changed when it differs from the background by more than `cellThreshold`.
4. Motion is declared after 2 consecutive analyzed frames with at least `minChangedCells` changed cells. The first 5 frames are warm-up.
5. If more than 60% of cells change at once it is treated as a lighting or exposure shift: the background is reset and nothing triggers.

Sensitivity 0..100 maps linearly to `cellThreshold` 40..8 and `minChangedCells` 4%..0.4% of the grid.

## 8. Config

`RecorderConfig` in `core/Model.kt` is the single definition, with defaults and `sanitized()` ranges. The service snapshots the config when a recording starts, so changes apply to the next start.

Defaults are starting points, not owner decisions. Audio defaults to off on purpose: recording other people's voices is regulated in many places, so the owner opts in.

## 9. Permissions and manifest

`CAMERA`, `RECORD_AUDIO` (requested at runtime only when audio is enabled), `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CAMERA`, `FOREGROUND_SERVICE_MICROPHONE`, `WAKE_LOCK`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. No `INTERNET`. `allowBackup` is false.

## 10. Research notes (Nov 2026)

- The category standard is a foreground service plus a "no preview" mode. Apps warn users to disable battery optimization so the OS does not kill the recorder with the screen off.
- Common trigger surfaces are widgets and volume buttons. Motion detection and scheduled recording are the usual premium features.
- FadCam is an open-source, ad-free reference (dashcam-style, crash-safe recording) worth reading for device quirks. Read for ideas only. Do not copy code without checking its license.

Sources:

- https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- https://developer.android.com/develop/background-work/services/fg-service-types
- https://developer.android.com/about/versions/15/changes/foreground-service-types
- https://developer.android.com/jetpack/androidx/releases/camera
- https://f-droid.org/en/packages/com.fadcam/
- https://sites.google.com/view/twelve-stars-software/company/manual-moca2-en

## 11. Known risks

1. Nothing in `:app` has been compiled yet (see TASKS handoff log). Library versions in `gradle/libs.versions.toml` were chosen from memory except CameraX, and may need a bump on the first CI run.
2. OEM battery managers killing the service, HyperOS in particular. Mitigation: exemption prompt on Home, README steps for Autostart and "No restrictions". The notification must make a stop obvious.
3. `VideoCapture` + `ImageAnalysis` together unsupported on a low-end camera (the Samsung A20 class). Today that fails with a clear error. Planned fallback: analysis only while ARMED, rebind on motion.
4. SAF copy doubles the write for a chosen folder. Acceptable for 3 minute segments. Direct file-descriptor output is a later optimization.
5. Segment gap on rotation (section 4).
6. Debug builds from CI are signed with a different key on every run unless a shared debug keystore is configured, so updating may require uninstalling first (TASKS T-060).

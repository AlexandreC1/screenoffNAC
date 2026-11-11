# ScreenOff Recorder

A personal Android app that records video with the screen off, loops over a storage cap, and can act as a motion-triggered security camera. Nothing leaves the phone: the app has no internet permission.

Status: first code drop. The pure logic is tested. The Android app has not been compiled yet, so the first build will probably need fixes. See `TASKS.md`.

## Getting an APK without touching the code

1. Put this folder in a GitHub repository and push.
2. Open the repository's Actions tab. The `build` workflow runs the tests and builds the app.
3. When it is green, open the run and download the artifact `screenoff-recorder-debug-apk`.
4. Copy the APK to the phone and install it (allow "install unknown apps" for your file manager).

If the workflow is red, give the log to the coding agent. That is task T-002.

Or build locally with Android Studio: open the folder, let it sync, Run.

## First run

1. Open the app and press Start. Grant Camera and Notifications. Microphone is only asked if you turn audio on in Settings.
2. On the Home tab, press Allow on the battery card.
3. Xiaomi HyperOS also needs, in the system Settings app, under Apps, ScreenOff Recorder:
   - Autostart: on
   - Battery saver: No restrictions
   Without these HyperOS is likely to stop the recording some minutes after the screen turns off.
4. Samsung: Settings, Apps, ScreenOff Recorder, Battery, Unrestricted.

## Starting without opening the app

- Quick Settings tile: pull down the shade, edit the tiles, add "Recorder". Tap to start, tap to stop.
- Widget: long-press the home screen, Widgets, ScreenOff Recorder.
- Shortcuts: long-press the app icon for Record now, Motion mode, Stop.
- The notification has Stop and Keep this clip.

## How it behaves

- A notification is always shown while the camera is in use. That is deliberate and cannot be turned off.
- Recording is cut into segments (3 minutes by default). When the storage cap is reached the oldest clips are deleted. Clips you mark Keep are never deleted by the loop.
- Motion mode waits with the camera on and only writes a clip when something moves. Keep the phone plugged in for this.
- Clips go to the app's own folder unless you choose a folder in Settings. The app's own folder is removed if you uninstall the app, so choose a folder for anything you care about.
- Recording stops by itself when the battery is low, the phone overheats, or storage is full and only kept clips remain. A notification says why.

## Please check on the phone and report back

1. Start from the app, screen off 10 minutes, stop. Do the clips play and add up to 10 minutes?
2. Does the tile start recording from the home screen? From the lock screen?
3. Same for the widget and the shortcuts.
4. Small cap (1 GB), 1 minute segments: do old clips disappear and kept clips stay?
5. Motion mode on a shelf: quiet for 5 minutes, then walk past.
6. Leave it recording for an hour with the screen off. Is it still recording?

## A note on recording people

You are responsible for how you use this. Recording people, and especially their voices, without consent is illegal in many places.

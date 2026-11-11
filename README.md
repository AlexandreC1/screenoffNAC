# ScreenOff Recorder

A personal Android app that records video with the screen off, loops over a storage cap, and can act as a motion-triggered security camera. Nothing leaves the phone: the app has no internet permission.

Status: builds and records on emulators. Not yet confirmed on a real phone. See `TASKS.md`.

## Getting the app

On the phone, open this link and install the file:

https://github.com/AlexandreC1/screenoffNAC/releases/latest/download/screenoff-recorder.apk

Every push runs the unit tests, builds the app, and records with it on Android 12 and Android 16 emulators. The link is only updated when all of that passes.

If the phone says the app cannot be installed over an existing one, uninstall the old one first (the signing key changed).

## First run

1. Open the app and press Start. Grant Camera, Microphone and Notifications. Without Microphone the app will not start recording.
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

## If motion mode does not record

While armed, the Home screen and the notification show "Movement X of Y needed". Wave a hand in front of the camera: X should jump above Y and a clip should start.

- "Waiting for camera images" for more than a few seconds: the camera is not delivering images. The app switches to a fallback by itself after 4 seconds.
- X stays small when you move: raise Motion sensitivity in Settings.
- Either way: Settings, Share diagnostics, and send the file to whoever fixes the app.

## Please check on the phone and report back

1. Start from the app, screen off 10 minutes, stop. Do the clips play and add up to 10 minutes?
2. Does the tile start recording from the home screen? From the lock screen?
3. Same for the widget and the shortcuts.
4. Small cap (1 GB), 1 minute segments: do old clips disappear and kept clips stay?
5. Motion mode on a shelf: quiet for 5 minutes, then walk past.
6. Leave it recording for an hour with the screen off. Is it still recording?

## A note on recording people

You are responsible for how you use this. Recording people, and especially their voices, without consent is illegal in many places.

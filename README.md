# Stream Switcher

A minimal Android app for switching between your live camera, a picked image,
or a picked video clip while recording — built for presenters who need to show
maps, charts, or short clips mid-recording without a third-party app.

## How it works

- **Camera / Image / Video** buttons switch the full-screen display instantly.
- **Image/Video** opens the system photo picker (works on any Android 8+ device,
  no storage permission dialogs beyond the one-time media permission).
- **Record** asks for one-time screen-capture consent (standard Android system
  dialog, not a custom permission), then records *exactly what's on screen*
  (camera, image, or video, whichever is active) plus your microphone, into a
  single continuous MP4 saved to `Movies/StreamSwitcher/` — visible in your
  gallery and ready to upload.
- **Flip** toggles front/back camera while in Camera mode.

## Why this architecture

Rather than manually compositing camera frames + images + video frames with
OpenGL (complex and fragile), the app uses Android's `MediaProjection` API to
record its own on-screen output. Whatever you see is exactly what gets
recorded — switching sources is instant and glitch-free because there's no
frame compositing pipeline to keep in sync.

## Build & run

1. Open the `StreamSwitcher/` folder in Android Studio (Koala or newer).
2. Let Gradle sync (it will fetch the wrapper automatically).
3. Run on your Pixel 7a (`minSdk 26`, tested target `compileSdk/targetSdk 35`).
4. Grant camera, microphone, and media permissions when prompted.

No special root access is needed for this app itself — it uses only public
Android APIs. (Root was only relevant for the earlier camera-characteristics
inspection, not for this app.)

## Using it for live streaming (not just recording)

Right now this app is a **recorder** — it saves an MP4 you can upload
afterward, same as your existing "record and upload" workflow.

If you also want to go **live** straight from this app (replacing the YouTube
app for live sessions, not just recording), the same UI can be extended to
push an RTMP stream directly to your YouTube stream key instead of / in
addition to saving a local file. That's a moderate addition (an RTMP
publishing library plus your stream key entry screen) — say the word and I'll
add it.

## Possible next steps

- Pin a specific physical camera (e.g. the ultrawide or macro sensor found on
  the Pixel 7a's back logical camera) via `Camera2Interop` instead of the
  default logical camera.
- Add a "hold last frame" option so video playback freezes on its last frame
  instead of the picker needing to be re-opened.
- Add on-screen text/lower-third overlays (e.g. your name/title) burned into
  the recording.
- Add direct RTMP live push to YouTube (see above).

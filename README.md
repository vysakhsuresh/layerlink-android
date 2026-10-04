# LayerLink Android Host

Native Android host for LayerLink screen-sharing sessions. It captures the device screen with
`MediaProjection`, publishes it over WebRTC, and negotiates the connection through the **same
Firebase Realtime Database signaling** the web sharer/viewer pages already use (see
`layerlink-sharer.body.html` / `layerlink-viewer.body.html` in the `layerbit-site` repo) — so a
browser running `tools/layerlink-viewer.html?session=<id>` can watch this app's broadcast with
no changes on the web side, and vice versa this app could watch a browser host (not implemented
here; this app only plays the host/sharer role).

## How it interops with the existing web app

Both the web pages and this app read/write the exact same Realtime Database shape under
`https://layerlink-58948-default-rtdb.firebaseio.com/sessions/{sessionId}`:

| Path | Written by | Meaning |
|---|---|---|
| `offer` | host (browser or this app) | `JSON.stringify({type, sdp})` of the WebRTC offer |
| `offerCandidates/{pushId}` | host | one stringified ICE candidate per pushed child |
| `answer` | viewer (browser) | `JSON.stringify({type, sdp})` of the WebRTC answer |
| `answerCandidates/{pushId}` | viewer | one stringified ICE candidate per pushed child |

The same ICE-candidate queueing behavior the web pages' changelog calls out (v1.1.0: queue
remote candidates until the remote description is set) is replicated in `LayerLinkHostSession`.

ICE servers are **no longer hardcoded to match the web pages** — they are resolved at runtime
(`IceConfig` / `IceConfigStore`): a relay set on the device, else a JSON file hosted next to the
viewer page, else STUN only. That change exists because the relay both sides shared had stopped
authenticating, which silently broke every cross-country share. **See [`relay/README.md`](relay/README.md)
for what broke, what to do about it, and the matching patch for the two web pages** — until they
read the same hosted config, they still carry the dead list.

The web app never calls `firebase.auth()`, so its database rules are open read/write. Rather
than adding a new Firebase Android app registration and `google-services.json` (which would
need Firebase console access this session doesn't have), this app talks to the same database
directly over its plain **REST + Server-Sent-Events API** (`FirebaseSignalingClient`), relying
on that same already-public access. No Firebase Android SDK dependency is needed.

## Project layout

Two modules: `:core` holds everything reusable across future apps in the same family
(screen-share engine, Firebase signaling, the floating Stop overlay, shared design tokens);
`:app` holds only what's specific to LayerLink itself (UI, branding, notification copy).

```
core/src/main/java/com/layerbit/core/
  webrtc/IceConfig.kt                 STUN/TURN list + the transport matrix a relay expands into
  webrtc/IceConfigStore.kt            Resolves it: device relay -> hosted JSON -> STUN-only floor
  webrtc/RelayProbe.kt                "Does this relay actually work?", via a relay-only ICE agent
  webrtc/WebRtcInitializer.kt         One-time process-wide native WebRTC init
  webrtc/ScreenShareHostSession.kt    Screen capture + PeerConnection + signaling wiring.
                                       signalingClient/viewerBaseUrl default to LayerLink's own
                                       Firebase project + viewer page but can be overridden per app.
  webrtc/SdpSuspendExt.kt             Coroutine wrappers around WebRTC's callback SDP APIs
  webrtc/SessionState.kt              Idle / Requesting / Waiting / Live / Closed / Error
  signaling/FirebaseSignalingClient.kt REST + SSE client for the sessions/{id} protocol
  signaling/RtcJson.kt                JSON <-> SessionDescription/IceCandidate, matching the web wire format
  util/SessionIdGenerator.kt          6-char session id generator
  util/IntentExt.kt                   API 33+-safe getParcelableExtra
  overlay/FloatingStopController.kt   Draggable "Stop" bubble + confirm overlay while broadcasting
core/src/main/res/
  values/colors.xml                   Shared brand palette
  layout, drawable/                   Overlay bubble/confirm UI + the shared "card" panel background

app/src/main/java/com/layerbit/layerlink/
  MainActivity.kt                     UI: start/stop, share link, local preview
  service/ScreenShareService.kt       Foreground service (type "mediaProjection") owning capture,
                                       constructs ScreenShareHostSession + FloatingStopController
```

## Requirements

- Android Studio (Ladybug or newer) with an Android SDK for **compileSdk/targetSdk 34**.
- JDK 17.
- `minSdk` is 26 (MediaProjection capture requires a foreground service of type
  `mediaProjection`, which is enforced from Android 10+; this app targets 26+ for a modern
  Kotlin/coroutines baseline).

No `google-services.json` is required — see above.

## Building

```
./gradlew :app:assembleDebug
```

This repository's sandbox had no Android SDK and no access to `dl.google.com` (Google's Maven
repo), so the Gradle build itself could not be executed or verified end-to-end here — only the
Gradle wrapper was generated and the source was written and reviewed by hand against the
`org.webrtc` / AndroidX / OkHttp APIs. Open the project in Android Studio and sync/build to pull
dependencies and catch anything environment-specific.

## Running

1. Install and launch the app, grant the notification permission prompt (Android 13+).
2. Tap **Initialize Broadcast** and accept the system screen-capture prompt.
3. Share the generated `layerbit.co.in/tools/layerlink-viewer.html?session=...` link (copy or
   share sheet) with a viewer — any browser running the existing LayerLink viewer page.
4. Status moves Idle → Starting → Awaiting Viewer → Connection Live once the viewer's answer
   and ICE candidates arrive.
5. Tap **Stop Broadcast** to end the session; this also deletes the `sessions/{id}` node from
   Firebase.

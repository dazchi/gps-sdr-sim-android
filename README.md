# LiveGpsSdrSim

An Android app that turns a phone (or tablet) + a HackRF One connected over USB-OTG
into a live GPS L1 C/A signal simulator. A retractable **Controls** side panel
selects one of three modes:

- **Raw LLH TX** — enter a fixed latitude, longitude and height, hit **Start TX**,
  and radiate a synthesized GPS signal for that static location.
- **Live** — seed a starting position, then **drive it around in real time** with an
  on-screen joystick. A speed slider (km/h) with Walk / Run / Motorcycle / Car
  presets sets the top speed, and motion uses trapezoidal acceleration /
  deceleration so the simulated receiver ramps smoothly up to speed and glides to
  a stop instead of teleporting.
- **Map** — a full-screen map. Tap to drop numbered waypoints, connect them by a
  straight line or **road routing** (GraphHopper, with your own API key), then
  **Play** to drive the simulated position along the route at the chosen speed.
  Load/export the route as GPX, and jump the map to the phone's real location.

This project is a **port** of the desktop C program
[`gps-sdr-sim`](https://github.com/osqzss/gps-sdr-sim) by
Takuji Ebinuma (osqzss), specifically the
[`dazchi/gps-sdr-sim`](https://github.com/dazchi/gps-sdr-sim) fork which adds
live-position streaming and HackRF transmit support on top of the original.
All of the actual GPS signal generation, ephemeris handling, satellite
selection and I/Q sample synthesis is that codebase — this project's
contribution is the Android UI, the USB transport, and the plumbing that
lets it run on-device.

> **Legal:** transmitting on the GPS L1 band (1575.42 MHz) is regulated
> and, in most jurisdictions, illegal outside a shielded chamber or wired
> setup terminated into a dummy load or a receiver-under-test. Do not
> radiate. Do not connect an antenna. You alone are responsible for how
> you use this.

---

## How it works

```
┌────────────────────────────────────────────────────┐
│  Kotlin + Material UI (DrawerLayout)               │
│   ├── Controls drawer: mode select, TX, map opts   │
│   ├── Raw form / Live joystick / full-screen Map   │
│   └── Live motion loop + route playback (10 Hz)    │
│         └── position → NativeGen.pushPosition()     │
├────────────────────────────────────────────────────┤
│  HackrfTxController                                │
│   ├── hackrf_android (USB Host transport)          │
│   └── producer thread → hackrf_android TX queue    │
├────────────────────────────────────────────────────┤
│  JNI bridge (native-lib.cpp)                       │
├────────────────────────────────────────────────────┤
│  libgpssim.so — the ported gpssim.c, NDK-built     │
│    ├── location + ephemeris → I/Q samples          │
│    ├── position FIFO (fed live via pushPosition)   │
│    └── ring buffer drained by the producer thread  │
└────────────────────────────────────────────────────┘
```

- **Signal generation stays in C.** `gpssim.c` is compiled as-is into an
  `.so` via the NDK, called from Kotlin through JNI.
- **HackRF transport is pure Java.** [`hackrf_android`](https://github.com/demantz/hackrf_android)
  is a re-implementation of libhackrf on top of Android's `UsbManager` /
  `UsbRequest` APIs — no libusb, no root required.
- **Samples flow generator → ring buffer → producer thread → hackrf_android
  TX queue → USB → HackRF.** The ring buffer decouples the generator's
  bursty CPU rhythm from the HackRF's real-time sample clock.
- **Live positions bypass the network.** The upstream fork streams live
  positions over a TCP socket; on Android the generator runs the same
  socket/live mode (`-S`) but the TCP listener is compiled out and positions
  are pushed straight into the generator's position FIFO from Kotlin via
  `NativeGen.pushPosition()` — no port bound, no local socket. Both the Live
  joystick and Map route-playback feed this same path at 10 Hz: the joystick
  integrates a velocity vector; playback advances a cursor along the route.
- **The Map is native, not the web frontend.** The upstream fork ships a
  Leaflet/Node route planner; here the map is [`osmdroid`](https://github.com/osmdroid/osmdroid),
  waypoint geometry and GPX are pure Kotlin (`RouteGeometry.kt`, `GpxIo.kt`), and
  road routing calls the GraphHopper Directions API directly (`GraphHopperRouter.kt`).
  This is why the app needs the `INTERNET` permission (map tiles + routing) and
  `ACCESS_FINE_LOCATION` (the "center on my location" button).

## Requirements

- Android 9.0 (API 28) or newer.
- HackRF One (or Jawbreaker / RAD1O) connected via a USB-OTG cable.
- A phone that supplies bus power to the HackRF, or an OTG cable with an
  external power injector — the HackRF draws too much current for many
  older phones.

## Build

Standard Android Studio: **File → Open** the project directory, let Gradle
sync (it pulls the NDK + CMake and builds `libgpssim.so`), then Run. Or
from the command line:

```bash
./gradlew installDebug
```

## Usage

1. Plug the HackRF into your phone via OTG. Android should offer to open
   this app for the USB device — the `USB_DEVICE_ATTACHED` intent-filter
   in the manifest handles this.
2. Tap **Controls** (top-left) to open the side panel and pick a mode:
   - **Raw LLH TX** — enter latitude, longitude and height (metres) for the
     fixed location the fake receiver should report.
   - **Live** — enter a starting latitude, longitude and height. These fields
     seed the run; once TX is live the joystick drives the position.
   - **Map** — tap the map to add numbered waypoints (tap a waypoint to select
     it; the red 🗑 FAB deletes it, the orange ✕ FAB clears all). Toggle
     **Road routing (GraphHopper)** and paste your API key for road-snapped
     routes, or leave it off for straight lines. Set an **Altitude** and a
     **Speed**, then use **Play / Pause / Stop**. The blue dot is the phone's
     real GPS (the location FAB centers on it); the orange dot is the simulated
     position moving along the route.
3. Configure the RF chain (shared by all modes, in the drawer):
   - **Retime ephemeris to now (`-T now`)** — on by default. Rewrites the
     bundled ephemeris's clock/orbit epoch to the phone's current UTC so a
     real receiver accepts it. Turn off to transmit at the file's native epoch.
   - **TX RF Amp** — the HackRF's +14 dB amplifier stage. Leave OFF for
     wired-to-a-receiver setups.
   - **TX VGA Gain** — 0–47 dB. Start at 0 and only increase if a
     receiver-under-test can't acquire lock.
4. Start transmitting — **Start TX** (Raw/Live) or **Play** (Map). The **LOG**
   pane (auto-scrolling) reports the ephemeris path, generator progress and any
   underrun warnings.
5. In **Live** mode, push the joystick to move: direction sets heading,
   deflection sets fraction of the top speed. Use the **speed slider** (km/h)
   or a **Walk / Run / Motorcycle / Car** preset to set the top speed; the live
   readout shows current speed and position. Height stays editable during TX.
6. Amp and gain can be adjusted while TX is running; mode switching and route
   editing lock while a transmission is in flight.

## Attribution

- **`gpssim.c` / `gpssim.h` / `hackrf.h`** — Copyright © the
  [`gps-sdr-sim`](https://github.com/osqzss/gps-sdr-sim) authors, MIT-style
  license. See those files' headers for the exact terms.
- **`hackrf_android.aar`** — Copyright © Dennis Mantz,
  [`demantz/hackrf_android`](https://github.com/demantz/hackrf_android).
- **HackRF hardware and `libhackrf` protocol** — Great Scott Gadgets.
- **Map rendering** — [`osmdroid`](https://github.com/osmdroid/osmdroid). Tiles
  are served from **Esri World Street Map** (keyless, for development use;
  point at a licensed/self-hosted source for anything heavier).
- **Road routing** — the [GraphHopper](https://www.graphhopper.com/) Directions
  API (you supply your own key). Routes ultimately derive from OpenStreetMap
  data, © OpenStreetMap contributors.

The Kotlin/JNI glue and Android UI in this project are new work built on
top of those.

## Known limitations

- **Map playback is basic.** Routes play at a constant speed; the upstream
  planner's extras — repeat/bounce/loop, random stops, jitter, per-point
  elevation from a terrain service — aren't ported yet.
- **Road routing needs your own GraphHopper key**, and map tiles come from
  Esri (OSM's own tile servers block non-browser apps). See Attribution.
- **No true velocity/Doppler model.** The generator recomputes ranges from each
  pushed static position, so the receiver infers velocity from the position
  rate rather than from a modelled Doppler. Fine for typical lock/track
  testing; not for high-dynamics or RTK-grade work.
- **No foreground service.** Swiping the app away or long backgrounding
  will kill the TX stream. Wrap the transmit path in a `Service` with a
  persistent notification if you need it to survive.
- **`SCHED_FIFO` isn't granted** to unrooted apps. The generator thread
  falls back to normal scheduling; if you see underruns during long
  transmissions, that's the reason.
- **Ephemeris is bundled** (`app/src/main/assets/brdc0010.22n`, a 2022
  file), but the **Retime ephemeris (`-T now`)** toggle rewrites its epoch to
  the phone's current UTC by default, so receivers still accept it. Loading a
  fresher RINEX from storage or a network-fetched broadcast RINEX (for accurate
  orbits, not just a retimed clock) remains a natural follow-up. Note `-T now`
  trusts the phone's system clock — keep it on automatic network time.

# LiveGpsSdrSim

An Android app that turns a phone (or tablet) + a HackRF One connected over USB-OTG
into a live GPS L1 C/A signal simulator. You **Connect** the HackRF once, then a
retractable **Controls** side panel selects one of three modes:

- **LLH TX** — enter a latitude, longitude and height and **Start TX** for a static
  position, or **drive it in real time** with an on-screen joystick (speed in km/h
  with Walk / Run / Motorcycle / Car presets, and trapezoidal acceleration so the
  receiver ramps up and glides to a stop). Type a new LLH and **Set position** to
  override/teleport at any time. The readout shows the currently transmitted LLH.
- **Map** — a full-screen map. Tap (or type lat/lon) to drop numbered, draggable
  waypoints; connect them by a straight line or **road routing** (GraphHopper),
  optionally closing the loop. **Play / Pause / Stop** drives the simulated
  position along the route with **Off / Bounce / Loop** repeat (finite laps or ∞),
  **Dynamics** (speed jitter + random stops), auto **SRTM altitude**, a live
  **Route Info** panel, GPX load/export, and long-press to set the sim position.
- **Settings** — the shared TX chain (**Retime ephemeris `-T now`**, **RF Amp**,
  **VGA Gain**, all persisted) plus the **GraphHopper API key** with a **Test**
  button, and the LOG.

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
│   ├── Controls drawer: Connect, mode, map opts     │
│   ├── LLH joystick / full-screen Map / Settings    │
│   └── motion loop + route playback (10 Hz)         │
│         └── position → NativeGen.pushPosition()     │
├────────────────────────────────────────────────────┤
│  HackrfTxController (Connect / startTx / stopTx)   │
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
  waypoint geometry, GPX, road routing and elevation are pure Kotlin
  (`RouteGeometry.kt`, `GpxIo.kt`, `GraphHopperRouter.kt`, `ElevationClient.kt`).
  Road routing calls the GraphHopper Directions API (segment-split with a
  rate-limit auto-retry), and altitude comes from OpenTopoData SRTM. This is why
  the app needs `INTERNET` (map tiles + routing + elevation) and
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
2. Tap **Controls** (top-left) to open the side panel. Under **Device**, tap
   **Connect** to open the HackRF (grant USB permission); the button then toggles
   to **Disconnect**. Start TX / Play require a connected device.
3. Pick a mode:
   - **LLH TX** — enter a latitude, longitude and height. **Start TX** transmits
     that position; push the joystick to move (direction = heading, deflection =
     fraction of the top speed via the km/h slider or a preset). Type a new LLH
     and **Set position** to override/teleport while transmitting. The readout
     shows the currently transmitted LLH and speed.
   - **Map** — tap the map (or type lat/lon → **Add**) to drop numbered waypoints;
     drag a pin to move it, tap to select then the red 🗑 FAB to delete (orange
     ✕ clears all). Toggle **Road routing (GraphHopper)** and pick foot/bike/car,
     or leave it off for straight lines. Choose a **Repeat** mode (Off / Bounce /
     Loop with optional lap count), **Auto altitude (SRTM)**, **Show track
     points**, **Dynamics** (speed jitter + random stops), and a **Speed** (slider
     or number box), then **Play / Pause / Stop**. **Route Info** shows distance,
     ETA, progress and the live **Sim LLH**. Long-press the map to set the sim
     position directly. Blue dot = the phone's real GPS; orange dot = the
     simulated position.
   - **Settings** — the shared TX chain and the GraphHopper key (below).
4. In **Settings**, configure (all values persist across launches):
   - **Retime ephemeris to now (`-T now`)** — on by default. Rewrites the
     bundled ephemeris's clock/orbit epoch to the phone's current UTC so a
     real receiver accepts it. Turn off to transmit at the file's native epoch.
   - **TX RF Amp** — the HackRF's +14 dB amplifier stage. Leave OFF for
     wired-to-a-receiver setups.
   - **TX VGA Gain** — 0–47 dB. Start at 0 and only increase if a
     receiver-under-test can't acquire lock.
   - **GraphHopper API key** — paste your key and tap **Test**; only a validated
     key is saved. The **LOG** (also mirrored in the drawer) reports progress.
5. Amp / VGA gain can be adjusted while TX is running (Settings stays reachable);
   switching TX mode and editing the route lock while a transmission is in flight.

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
- **Elevation** — [OpenTopoData](https://www.opentopodata.org/) SRTM 30 m
  (public instance; rate-limited).

The Kotlin/JNI glue and Android UI in this project are new work built on
top of those.

## Known limitations

- **Road routing needs your own GraphHopper key**, and map tiles come from
  Esri (OSM's own tile servers block non-browser apps). See Attribution.
- **No true velocity/Doppler model.** The generator recomputes ranges from each
  pushed static position, so the receiver infers velocity from the position
  rate rather than from a modelled Doppler. Fine for typical lock/track
  testing; not for high-dynamics or RTK-grade work.
- **Movement pauses if the Activity is destroyed.** A foreground service now
  owns the HackRF connection and the TX stream, so transmission survives
  backgrounding and swipe-away (a persistent notification with a Stop action
  reflects this). But the joystick and route-playback loops still run in the
  Activity, so if the system tears it down mid-run the receiver keeps getting
  the last position rather than a moving one until you reopen the app. Moving
  those loops into the service is the natural next step.
- **`SCHED_FIFO` isn't granted** to unrooted apps. The generator thread
  falls back to normal scheduling; if you see underruns during long
  transmissions, that's the reason.
- **Ephemeris is bundled** (`app/src/main/assets/brdc0010.22n`, a 2022
  file), but the **Retime ephemeris (`-T now`)** toggle rewrites its epoch to
  the phone's current UTC by default, so receivers still accept it. Loading a
  fresher RINEX from storage or a network-fetched broadcast RINEX (for accurate
  orbits, not just a retimed clock) remains a natural follow-up. Note `-T now`
  trusts the phone's system clock — keep it on automatic network time.

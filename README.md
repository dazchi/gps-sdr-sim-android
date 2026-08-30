# LiveGpsSdrSim

An Android app that turns a phone (or tablet) + a HackRF One connected over USB-OTG
into a live GPS L1 C/A signal simulator. It has two modes, selectable via tabs:

- **Raw LLH TX** — enter a fixed latitude, longitude and height, hit **Start TX**,
  and radiate a synthesized GPS signal for that static location.
- **Live** — seed a starting position, then **drive it around in real time** with an
  on-screen joystick. A speed slider (km/h) with Walk / Run / Motorcycle / Car
  presets sets the top speed, and motion uses trapezoidal acceleration /
  deceleration so the simulated receiver ramps smoothly up to speed and glides to
  a stop instead of teleporting.

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
│  Kotlin + AppCompat UI                             │
│  (Raw/Live tabs, joystick, speed slider, TX button)│
├──────────────────────────┬─────────────────────────┤
│  HackrfTxController       │  Live motion loop       │
│   ├── hackrf_android      │   ├── joystick → target │
│   │   (USB Host transport)│   │   velocity vector   │
│   └── producer thread     │   ├── trapezoidal ramp  │
│       → TX queue          │   └── 10 Hz pushPosition│
├──────────────────────────┴─────────────────────────┤
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
  `NativeGen.pushPosition()` — no port bound, no `INTERNET` permission. The UI's
  joystick drives a velocity vector, integrates it into a position at 10 Hz, and
  pushes each update.

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
2. Pick a mode with the tab bar:
   - **Raw LLH TX** — enter latitude, longitude and height (metres) for the
     fixed location the fake receiver should report.
   - **Live** — enter a starting latitude, longitude and height. These fields
     seed the run; once TX is live the joystick drives the position.
3. Configure the RF chain (shared by both tabs):
   - **Retime ephemeris to now (`-T now`)** — on by default. Rewrites the
     bundled ephemeris's clock/orbit epoch to the phone's current UTC so a
     real receiver accepts it. Turn off to transmit at the file's native epoch.
   - **TX RF Amp** — the HackRF's +14 dB amplifier stage. Leave OFF for
     wired-to-a-receiver setups.
   - **TX VGA Gain** — 0–47 dB. Start at 0 and only increase if a
     receiver-under-test can't acquire lock.
4. Tap **Start TX**. The **LOG** pane (auto-scrolling) reports the ephemeris
   path, generator progress and any underrun warnings.
5. In **Live** mode, push the joystick to move: direction sets heading,
   deflection sets fraction of the top speed. Use the **speed slider** (km/h)
   or a **Walk / Run / Motorcycle / Car** preset to set the top speed; the live
   readout shows current speed and position. Height stays editable during TX.
6. Tap **Start/Stop TX** to toggle. Amp and gain can be adjusted while TX is
   running; the mode tabs lock while a transmission is in flight.

## Attribution

- **`gpssim.c` / `gpssim.h` / `hackrf.h`** — Copyright © the
  [`gps-sdr-sim`](https://github.com/osqzss/gps-sdr-sim) authors, MIT-style
  license. See those files' headers for the exact terms.
- **`hackrf_android.aar`** — Copyright © Dennis Mantz,
  [`demantz/hackrf_android`](https://github.com/demantz/hackrf_android).
- **HackRF hardware and `libhackrf` protocol** — Great Scott Gadgets.

The Kotlin/JNI glue and Android UI in this project are new work built on
top of those.

## Known limitations

- **Live motion is joystick-driven, not scripted.** You steer the position by
  hand; there's no map picker or GPX/route playback yet. The generator's
  motion-file loaders (`readUserMotion` / `readUserMotionLLH`) are already
  ported, so wiring a file picker into the same FIFO is a natural follow-up.
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

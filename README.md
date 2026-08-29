# LiveGpsSdrSim

An Android app that turns a phone (or tablet) + a HackRF One connected over USB-OTG
into a live GPS L1 C/A signal simulator. It lets you enter a latitude and
longitude in the UI, hit **Start TX**, and start radiating a synthesized GPS
signal for the coordinates you chose.

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
│  (lat/lon inputs, amp toggle, VGA gain, TX button) │
├────────────────────────────────────────────────────┤
│  HackrfTxController                                │
│    ├── hackrf_android (USB Host API transport)     │
│    └── producer thread → hackrf_android TX queue   │
├────────────────────────────────────────────────────┤
│  JNI bridge (native-lib.cpp)                       │
├────────────────────────────────────────────────────┤
│  libgpssim.so — the ported gpssim.c, NDK-built     │
│    ├── static location + ephemeris → I/Q samples   │
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
2. Enter the latitude and longitude you want the fake receiver to think
   it is at. Height is fixed at 10 m.
3. Configure the RF chain:
   - **TX RF Amp** — the HackRF's +14 dB amplifier stage. Leave OFF for
     wired-to-a-receiver setups.
   - **TX VGA Gain** — 0–47 dB. Start at 0 and only increase if a
     receiver-under-test can't acquire lock.
4. Tap **Start TX**. The status pane logs generator progress and any
   underrun warnings.
5. Tap again to stop. Both amp and gain can be adjusted while TX is running.

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

- **Position is static per TX run.** Change lat/lon → Start TX → HackRF
  transmits that fixed location. Editing the fields while TX is running
  has no effect. Live-position updates (map picker / GPX playback) are
  planned; they'd tie into the same `socket_mode` LLH FIFO the desktop
  frontend uses.
- **No foreground service.** Swiping the app away or long backgrounding
  will kill the TX stream. Wrap the transmit path in a `Service` with a
  persistent notification if you need it to survive.
- **`SCHED_FIFO` isn't granted** to unrooted apps. The generator thread
  falls back to normal scheduling; if you see underruns during long
  transmissions, that's the reason.
- **Ephemeris is bundled** (`app/src/main/assets/brdc0010.22n`, a 2022
  file). Real-world GPS receivers expect roughly-current ephemeris —
  loading fresher RINEX from the phone's storage or from a
  network-fetched broadcast RINEX is a natural follow-up.

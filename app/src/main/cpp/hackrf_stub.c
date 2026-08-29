// Android stub for libhackrf.
//
// gpssim.c is written against libhackrf and calls a handful of hackrf_*
// functions when the user passes -H. On Android we don't link libhackrf
// (it needs libusb + typically root). The real HackRF transport is
// provided by the Java library `com.github.mantz-it:hackrf_android`,
// driven from Kotlin, and fed samples produced by gpssim's file-output
// path.
//
// This file provides no-op implementations of every hackrf_* function
// that gpssim.c references, so the native library links cleanly. If
// anyone actually passes -H, they'll get an immediate failure and a log
// line — that's the correct behavior for the Android build.

#include "hackrf.h"
#include <android/log.h>

#define LOG_TAG "hackrf_stub"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

static void warn_unsupported(const char *fn) {
    LOGW("%s called on Android build — libhackrf is not linked. "
         "Use the Kotlin hackrf_android transport instead.", fn);
}

int ADDCALL hackrf_init(void) {
    warn_unsupported("hackrf_init");
    return HACKRF_ERROR_NOT_FOUND;
}

int ADDCALL hackrf_exit(void) { return HACKRF_SUCCESS; }

int ADDCALL hackrf_open(hackrf_device **device) {
    (void)device;
    warn_unsupported("hackrf_open");
    return HACKRF_ERROR_NOT_FOUND;
}

int ADDCALL hackrf_close(hackrf_device *device) {
    (void)device;
    return HACKRF_SUCCESS;
}

int ADDCALL hackrf_set_sample_rate(hackrf_device *device, const double freq_hz) {
    (void)device; (void)freq_hz; return HACKRF_ERROR_NOT_FOUND;
}

int ADDCALL hackrf_set_freq(hackrf_device *device, const uint64_t freq_hz) {
    (void)device; (void)freq_hz; return HACKRF_ERROR_NOT_FOUND;
}

int ADDCALL hackrf_set_amp_enable(hackrf_device *device, const uint8_t value) {
    (void)device; (void)value; return HACKRF_ERROR_NOT_FOUND;
}

int ADDCALL hackrf_set_txvga_gain(hackrf_device *device, uint32_t value) {
    (void)device; (void)value; return HACKRF_ERROR_NOT_FOUND;
}

int ADDCALL hackrf_start_tx(
        hackrf_device *device,
        hackrf_sample_block_cb_fn callback,
        void *tx_ctx) {
    (void)device; (void)callback; (void)tx_ctx;
    warn_unsupported("hackrf_start_tx");
    return HACKRF_ERROR_NOT_FOUND;
}

int ADDCALL hackrf_stop_tx(hackrf_device *device) {
    (void)device; return HACKRF_SUCCESS;
}

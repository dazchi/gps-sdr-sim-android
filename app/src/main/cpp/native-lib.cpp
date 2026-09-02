// JNI bridge into gpssim.
//
// Two modes are exposed to Kotlin:
//
//   gpssimRun(args)         — synchronous; runs the generator to completion,
//                             typically writing a .bin file.
//   startStreaming(args)    — spawns a background pthread running gpssim_main
//                             with -H, so samples pile into the ring buffer.
//   rbPullBytes(dst,len)    — blocks until `len` bytes are available (or the
//                             generator has stopped and drained). Called from
//                             HackrfTxController to fill each transmit buffer.
//   requestShutdown()       — asks the generator to stop; wakes any waiter.
//
// gpssim.c's original int main(int, char**) is renamed to gpssim_main() at
// compile time via -Dmain=gpssim_main in CMakeLists.txt.

#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <setjmp.h>
#include <unistd.h>
#include <cstring>
#include <cstdlib>
#include <cstdio>
#include <string>
#include <vector>

extern "C" {
    int    gpssim_main(int argc, char **argv);
    size_t rb_pull_bytes(uint8_t *dst, size_t len);
    void   android_request_shutdown(void);
    void   android_streaming_reset(void);
    int    android_push_position(double lat_deg, double lon_deg, double hgt_m);
    int    android_force_position(double lat_deg, double lon_deg, double hgt_m);
    int    android_get_current_position(double *out);
    extern __thread jmp_buf g_gpssim_exit_jmp;
    extern __thread int     g_gpssim_exit_armed;
}

// Run gpssim_main with the exit() jmp_buf armed so an exit(N) unwinds back
// here as -(N & 0xff) instead of tearing down the process.
static int run_gpssim_main_safe(int argc, char** argv) {
    volatile int rc = setjmp(g_gpssim_exit_jmp);
    if (rc == 0) {
        g_gpssim_exit_armed = 1;
        int r = gpssim_main(argc, argv);
        g_gpssim_exit_armed = 0;
        return r;
    }
    g_gpssim_exit_armed = 0;
    return -(rc & 0xff);
}

#define LOG_TAG "gpssim_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// -----------------------------------------------------------------------
// Redirect stderr (which gpssim.c uses heavily for fprintf) to logcat so
// we can actually see the generator's error messages on Android.
// -----------------------------------------------------------------------
static int g_stderr_pipe_read_fd = -1;

static void* stderr_pump(void* /*arg*/) {
    char buf[512];
    ssize_t n;
    while ((n = read(g_stderr_pipe_read_fd, buf, sizeof(buf) - 1)) > 0) {
        if (buf[n - 1] == '\n') n--;
        buf[n] = 0;
        __android_log_write(ANDROID_LOG_INFO, "gpssim_stderr", buf);
    }
    return nullptr;
}

static pthread_once_t stderr_redir_once = PTHREAD_ONCE_INIT;
static void install_stderr_redirect(void) {
    int fds[2];
    if (pipe(fds) != 0) return;
    setvbuf(stderr, nullptr, _IOLBF, 0);
    dup2(fds[1], STDERR_FILENO);
    close(fds[1]);
    g_stderr_pipe_read_fd = fds[0];

    pthread_t tid;
    if (pthread_create(&tid, nullptr, stderr_pump, nullptr) == 0) {
        pthread_detach(tid);
    }
}

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* /*vm*/, void* /*reserved*/) {
    pthread_once(&stderr_redir_once, install_stderr_redirect);
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_livegpssdrsim_MainActivity_stringFromJNI(
        JNIEnv* env, jobject /*thiz*/) {
    pthread_once(&stderr_redir_once, install_stderr_redirect);
    return env->NewStringUTF("gpssim native library loaded");
}

// ---------------------------------------------------------------------------
// Synchronous run — the "generate a .bin file" path
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jint JNICALL
Java_com_example_livegpssdrsim_NativeGen_gpssimRun(
        JNIEnv* env, jobject /*thiz*/, jobjectArray jargs) {

    const jsize argc = env->GetArrayLength(jargs);
    std::vector<std::string> owned; owned.reserve(argc);
    std::vector<char*> argv;        argv.reserve(argc + 1);

    for (jsize i = 0; i < argc; ++i) {
        auto s = (jstring) env->GetObjectArrayElement(jargs, i);
        const char* c = env->GetStringUTFChars(s, nullptr);
        owned.emplace_back(c);
        env->ReleaseStringUTFChars(s, c);
        env->DeleteLocalRef(s);
        argv.push_back(owned.back().data());
    }
    argv.push_back(nullptr);

    LOGI("gpssim_main (blocking) starting, argc=%d", argc);
    android_streaming_reset();
    int rc = run_gpssim_main_safe(argc, argv.data());
    LOGI("gpssim_main returned %d", rc);
    return rc;
}

// ---------------------------------------------------------------------------
// Async streaming — spawns gpssim_main on its own pthread
// ---------------------------------------------------------------------------
struct GenArgs {
    std::vector<std::string> owned;
    std::vector<char*>       argv;
};

static void* generator_thread_main(void* p) {
    auto* a = static_cast<GenArgs*>(p);
    LOGI("generator thread starting, argc=%zu", a->owned.size());
    int rc = run_gpssim_main_safe(static_cast<int>(a->owned.size()), a->argv.data());
    LOGI("generator thread returning, rc=%d", rc);
    android_request_shutdown();   // ensure any consumer waiting on the ring wakes up
    delete a;
    return nullptr;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_livegpssdrsim_NativeGen_startStreaming(
        JNIEnv* env, jobject /*thiz*/, jobjectArray jargs) {

    const jsize argc = env->GetArrayLength(jargs);
    auto* a = new GenArgs();
    a->owned.reserve(argc);
    a->argv.reserve(argc + 1);

    for (jsize i = 0; i < argc; ++i) {
        auto s = (jstring) env->GetObjectArrayElement(jargs, i);
        const char* c = env->GetStringUTFChars(s, nullptr);
        a->owned.emplace_back(c);
        env->ReleaseStringUTFChars(s, c);
        env->DeleteLocalRef(s);
        a->argv.push_back(a->owned.back().data());
    }
    a->argv.push_back(nullptr);

    android_streaming_reset();

    pthread_t tid;
    int err = pthread_create(&tid, nullptr, generator_thread_main, a);
    if (err != 0) {
        LOGE("pthread_create failed: %d", err);
        delete a;
        return -1;
    }
    pthread_detach(tid);
    return 0;
}

// ---------------------------------------------------------------------------
// Ring-buffer pull, called from the HackRF TX producer thread on the JVM side
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jint JNICALL
Java_com_example_livegpssdrsim_NativeGen_rbPullBytes(
        JNIEnv* env, jobject /*thiz*/, jbyteArray dst, jint len) {
    if (len <= 0) return 0;

    // Pull into a local buffer, then copy into the Java byte[] once.
    // (Avoids holding the ring-buffer mutex across a JNI array pin.)
    auto* buf = static_cast<uint8_t*>(std::malloc(static_cast<size_t>(len)));
    if (!buf) return -1;

    size_t got = rb_pull_bytes(buf, static_cast<size_t>(len));
    if (got > 0) {
        env->SetByteArrayRegion(dst, 0, static_cast<jsize>(got),
                                reinterpret_cast<jbyte*>(buf));
    }
    std::free(buf);
    return static_cast<jint>(got);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_livegpssdrsim_NativeGen_requestShutdown(
        JNIEnv* /*env*/, jobject /*thiz*/) {
    LOGI("requestShutdown()");
    android_request_shutdown();
}

// ---------------------------------------------------------------------------
// Live position push — feeds the socket-mode generator loop directly (no TCP).
// Returns 1 if the position was accepted, 0 if rejected as an implausible jump.
// ---------------------------------------------------------------------------
extern "C" JNIEXPORT jint JNICALL
Java_com_example_livegpssdrsim_NativeGen_pushPosition(
        JNIEnv* /*env*/, jobject /*thiz*/, jdouble lat, jdouble lon, jdouble hgt) {
    return android_push_position(lat, lon, hgt);
}

// Force/override the position, bypassing the jump check (typed LLH override).
extern "C" JNIEXPORT jint JNICALL
Java_com_example_livegpssdrsim_NativeGen_pushPositionForce(
        JNIEnv* /*env*/, jobject /*thiz*/, jdouble lat, jdouble lon, jdouble hgt) {
    return android_force_position(lat, lon, hgt);
}

// Read back the position the generator is currently encoding (what's actually
// being transmitted, modulo the IQ buffer). Fills out[0..2] = lat,lon,hgt in
// degrees and returns 1 when valid, else 0. `out` must have length >= 3.
extern "C" JNIEXPORT jint JNICALL
Java_com_example_livegpssdrsim_NativeGen_currentPosition(
        JNIEnv* env, jobject /*thiz*/, jdoubleArray out) {
    if (out == nullptr || env->GetArrayLength(out) < 3) return 0;
    double buf[3];
    int valid = android_get_current_position(buf);
    if (valid) env->SetDoubleArrayRegion(out, 0, 3, buf);
    return valid;
}

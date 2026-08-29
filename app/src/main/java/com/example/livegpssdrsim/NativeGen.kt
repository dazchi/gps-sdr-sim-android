package com.example.livegpssdrsim

object NativeGen {
    init { System.loadLibrary("livegpssdrsim") }

    /** Runs gpssim_main synchronously (blocks the calling thread). Returns rc. */
    external fun gpssimRun(args: Array<String>): Int

    /** Spawns gpssim_main on a background pthread; returns immediately. */
    external fun startStreaming(args: Array<String>): Int

    /**
     * Blocks until `len` bytes are available in the generator's ring buffer,
     * then copies them into `dst`. Returns bytes actually written; a short
     * return means the generator has stopped and the ring is drained.
     */
    external fun rbPullBytes(dst: ByteArray, len: Int): Int

    /** Ask the generator thread to stop; wakes any thread parked in rb_pull. */
    external fun requestShutdown()
}

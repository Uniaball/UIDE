package com.uniaball.uide.build

/**
 * Bridge to the PTY helper in `cpp/uidepty.c` (part of libuidexec.so).
 *
 * [open] creates a pseudo-terminal pair; [spawn] forks a child that owns the
 * slave as its controlling terminal and execs the program there.  The returned
 * master fd is a normal file descriptor, so the app can read the program's
 * output from it and write keystrokes back.
 */
class NativePty {

    companion object {
        @Volatile
        private var loaded = false

        fun ensureLoaded() {
            if (loaded) return
            synchronized(this) {
                if (loaded) return
                System.loadLibrary("uidexec")
                loaded = true
            }
        }

        /** Master fd of a new PTY pair, or a negative value on failure. */
        @JvmStatic
        external fun open(): Int

        /** Forks a child on the PTY; returns its pid, or -1 on failure. */
        @JvmStatic
        external fun spawn(
            master: Int,
            path: String,
            argv: Array<String>,
            envp: Array<String>,
            rows: Int,
            cols: Int,
        ): Int

        /** Tells the program that the terminal geometry changed. */
        @JvmStatic
        external fun resize(fd: Int, rows: Int, cols: Int)

        @JvmStatic
        external fun isattyOf(fd: Int): Boolean

        /** Blocking read; -1 on error, -2 when interrupted (cancelled). */
        @JvmStatic
        external fun read(fd: Int, buffer: ByteArray): Int

        /** Blocking write of the whole buffer; returns bytes written or -1. */
        @JvmStatic
        external fun write(fd: Int, buffer: ByteArray): Int

        /** Blocking wait for the child; returns its exit code, or -1. */
        @JvmStatic
        external fun waitForPid(pid: Int): Int

        @JvmStatic
        external fun isAlive(pid: Int): Boolean

        @JvmStatic
        external fun killPid(pid: Int, signal: Int)

        @JvmStatic
        external fun closeFd(fd: Int)
    }
}

/*
 * Pseudo-terminal support for running built programs interactively.
 *
 * Android apps have no controlling terminal, so a program launched with pipes
 * sees isatty() == false: std::cin is unbuffered/fragile, stdout is fully
 * buffered, colours and prompts are disabled.  Here the app opens a PTY pair,
 * forks, makes the child a session leader with the slave as its controlling
 * terminal, and lets execv() hand the program over to the linker64 launch path
 * that uidexec.c already implements.
 *
 * The parent keeps the master fd, which the app relays in both directions:
 * output becomes log lines, keystrokes go back in.
 */
#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#define DEFAULT_ROWS 24
#define DEFAULT_COLS 80

static int slave_fd = -1;

static char **to_string_array(JNIEnv *env, jobjectArray array) {
    if (array == NULL) return NULL;
    jsize count = (*env)->GetArrayLength(env, array);
    char **out = calloc((size_t)count + 1, sizeof(char *));
    if (out == NULL) return NULL;
    for (jsize i = 0; i < count; i++) {
        jstring item = (jstring)(*env)->GetObjectArrayElement(env, array, i);
        if (item == NULL) continue;
        const char *chars = (*env)->GetStringUTFChars(env, item, NULL);
        out[i] = chars != NULL ? strdup(chars) : NULL;
        if (chars != NULL) (*env)->ReleaseStringUTFChars(env, item, chars);
        (*env)->DeleteLocalRef(env, item);
    }
    return out;
}

static void free_string_array(char **items) {
    if (items == NULL) return;
    for (size_t i = 0; items[i] != NULL; i++) free(items[i]);
    free(items);
}

static void apply_window_size(int fd, int rows, int cols) {
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_row = (unsigned short)(rows > 0 ? rows : DEFAULT_ROWS);
    ws.ws_col = (unsigned short)(cols > 0 ? cols : DEFAULT_COLS);
    ioctl(fd, TIOCSWINSZ, &ws);
}

/*
 * Class:     com_uniaball_uide_build_NativePty
 * Method:    open
 * Signature: ()I
 */
JNIEXPORT jint JNICALL Java_com_uniaball_uide_build_NativePty_open(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    if (slave_fd >= 0) return -1;

    int master = posix_openpt(O_RDWR | O_NOCTTY);
    if (master < 0) return -1;
    if (grantpt(master) != 0 || unlockpt(master) != 0) {
        close(master);
        return -1;
    }
    char *name = ptsname(master);
    if (name == NULL) {
        close(master);
        return -1;
    }
    int slave = open(name, O_RDWR | O_NOCTTY);
    if (slave < 0) {
        close(master);
        return -1;
    }
    /* Raw-ish defaults: keep line editing and echo, but stop the tty from
       translating the program's \n into \r\n so the log stays clean. */
    struct termios attrs;
    if (tcgetattr(slave, &attrs) == 0) {
        attrs.c_oflag &= (unsigned)~OPOST;
        tcsetattr(slave, TCSANOW, &attrs);
    }

    slave_fd = slave;
    return master;
}

/*
 * Class:     com_uniaball_uide_build_NativePty
 * Method:    spawn
 * Signature: (ILjava/lang/String;[Ljava/lang/String;[Ljava/lang/String;II)I
 */
JNIEXPORT jint JNICALL Java_com_uniaball_uide_build_NativePty_spawn(
        JNIEnv *env, jclass clazz, jint master, jstring path, jobjectArray argv,
        jobjectArray envp, jint rows, jint cols) {
    (void)clazz;
    int slave = slave_fd;
    slave_fd = -1;

    if (master < 0 || slave < 0 || path == NULL) {
        if (slave >= 0) close(slave);
        return -1;
    }

    char **args = to_string_array(env, argv);
    char **envs = to_string_array(env, envp);
    const char *program = (*env)->GetStringUTFChars(env, path, NULL);
    if (args == NULL || envs == NULL || program == NULL) {
        if (program != NULL) (*env)->ReleaseStringUTFChars(env, path, program);
        free_string_array(args);
        free_string_array(envs);
        close(slave);
        return -1;
    }

    apply_window_size(master, rows, cols);

    pid_t pid = fork();
    if (pid < 0) {
        (*env)->ReleaseStringUTFChars(env, path, program);
        free_string_array(args);
        free_string_array(envs);
        close(slave);
        return -1;
    }

    if (pid == 0) {
        /* Child: become a session leader owning the slave as its tty. */
        close(master);
        setsid();
        ioctl(slave, TIOCSCTTY, 0);
        dup2(slave, STDIN_FILENO);
        dup2(slave, STDOUT_FILENO);
        dup2(slave, STDERR_FILENO);
        if (slave > STDERR_FILENO) close(slave);

        /* uidexec.c hooks execv() in this process too (LD_PRELOAD), so this
           reaches /system/bin/linker64 for programs outside /system. */
        execve(program, args, envs);

        /* exec failed: report through the tty so the log shows why. */
        char message[512];
        int len = snprintf(message, sizeof(message), "\n[无法启动 %s: %s]\n", program, strerror(errno));
        if (len > 0) {
            ssize_t ignored = write(STDERR_FILENO, message, (size_t)len);
            (void)ignored;
        }
        _exit(127);
    }

    /* Parent: the child owns the slave now. */
    close(slave);
    (*env)->ReleaseStringUTFChars(env, path, program);
    free_string_array(args);
    free_string_array(envs);
    return (jint)pid;
}

/*
 * Class:     com_uniaball_uide_build_NativePty
 * Method:    resize
 * Signature: (III)V
 */
JNIEXPORT void JNICALL Java_com_uniaball_uide_build_NativePty_resize(JNIEnv *env, jclass clazz,
                                                                    jint fd, jint rows, jint cols) {
    (void)env;
    (void)clazz;
    if (fd >= 0) apply_window_size(fd, rows, cols);
}

/*
 * Class:     com_uniaball_uide_build_NativePty
 * Method:    isattyOf
 * Signature: (I)Z
 */
JNIEXPORT jboolean JNICALL Java_com_uniaball_uide_build_NativePty_isattyOf(JNIEnv *env, jclass clazz,
                                                                          jint fd) {
    (void)env;
    (void)clazz;
    return fd >= 0 && isatty(fd) ? JNI_TRUE : JNI_FALSE;
}

/*
 * Class:     com_uniaball_uide_build_NativePty
 * Method:    read
 * Signature: (I[B)I
 * Blocks until data arrives or the program closes the terminal; EINTR (a
 * cancelled coroutine) returns -2 so the reader can unwind.
 */
JNIEXPORT jint JNICALL Java_com_uniaball_uide_build_NativePty_read(JNIEnv *env, jclass clazz,
                                                                    jint fd, jbyteArray buffer) {
    (void)clazz;
    if (fd < 0 || buffer == NULL) return -1;
    jsize capacity = (*env)->GetArrayLength(env, buffer);
    if (capacity <= 0) return -1;
    jbyte *bytes = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (bytes == NULL) return -1;
    ssize_t got = read(fd, bytes, (size_t)capacity);
    /* Mode 0 copies the bytes back into the Java array; JNI_ABORT would
       discard them and the caller would see nothing but zeros. */
    (*env)->ReleaseByteArrayElements(env, buffer, bytes, got > 0 ? 0 : JNI_ABORT);
    if (got < 0) return errno == EINTR ? -2 : -1;
    return (jint)got;
}

/*
 * Class:     com_uniaball_uide_build_NativePty
 * Method:    write
 * Signature: (I[B)I
 */
JNIEXPORT jint JNICALL Java_com_uniaball_uide_build_NativePty_write(JNIEnv *env, jclass clazz,
                                                                    jint fd, jbyteArray buffer) {
    (void)clazz;
    if (fd < 0 || buffer == NULL) return -1;
    jsize length = (*env)->GetArrayLength(env, buffer);
    if (length <= 0) return 0;
    jbyte *bytes = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (bytes == NULL) return -1;
    jsize offset = 0;
    while (offset < length) {
        ssize_t sent = write(fd, bytes + offset, (size_t)(length - offset));
        if (sent <= 0) {
            (*env)->ReleaseByteArrayElements(env, buffer, bytes, JNI_ABORT);
            return sent == 0 ? 0 : -1;
        }
        offset += (jsize)sent;
    }
    (*env)->ReleaseByteArrayElements(env, buffer, bytes, JNI_ABORT);
    return offset;
}

/*
 * Class:     com_uniaball_uide_build_NativePty
 * Method:    waitForPid
 * Signature: (I)I
 * Blocking waitpid(); returns the exit code, or -1 when the child is gone.
 */
JNIEXPORT jint JNICALL Java_com_uniaball_uide_build_NativePty_waitForPid(JNIEnv *env, jclass clazz,
                                                                          jint pid) {
    (void)env;
    (void)clazz;
    if (pid <= 0) return -1;
    int status = 0;
    while (waitpid((pid_t)pid, &status, 0) < 0) {
        if (errno != EINTR) return -1;
    }
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return -1;
}

/*
 * Class:     com_uniaball_uide_build_NativePty
 * Method:    isAlive
 * Signature: (I)Z
 */
JNIEXPORT jboolean JNICALL Java_com_uniaball_uide_build_NativePty_isAlive(JNIEnv *env, jclass clazz,
                                                                         jint pid) {
    (void)env;
    (void)clazz;
    if (pid <= 0) return JNI_FALSE;
    return kill((pid_t)pid, 0) == 0 ? JNI_TRUE : JNI_FALSE;
}

/*
 * Class:     com_uniaball_uide_build_NativePty
 * Method:    killPid
 * Signature: (II)V
 */
JNIEXPORT void JNICALL Java_com_uniaball_uide_build_NativePty_killPid(JNIEnv *env, jclass clazz,
                                                                       jint pid, jint signal) {
    (void)env;
    (void)clazz;
    if (pid > 0) kill((pid_t)pid, signal > 0 ? signal : SIGKILL);
}

/*
 * Class:     com_uniaball_uide_build_NativePty
 * Method:    closeFd
 * Signature: (I)V
 */
JNIEXPORT void JNICALL Java_com_uniaball_uide_build_NativePty_closeFd(JNIEnv *env, jclass clazz,
                                                                      jint fd) {
    (void)env;
    (void)clazz;
    if (fd >= 0) close(fd);
}

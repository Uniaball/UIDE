#define _GNU_SOURCE

#include <dlfcn.h>
#include <errno.h>
#include <limits.h>
#include <spawn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

extern char **environ;

extern int uidexec_marker(void) __attribute__((weak));

#define CHILD_MODE "--uide-selftest-child"
#define SELF_ENV "UIDE_SELFTEST_SELF"

static const char *self_path(void) {
    const char *from_env = getenv(SELF_ENV);
    if (from_env != NULL && from_env[0] != '\0') return from_env;
    static char buf[PATH_MAX];
    ssize_t n = readlink("/proc/self/exe", buf, sizeof(buf) - 1);
    if (n <= 0) return NULL;
    buf[n] = '\0';
    return buf;
}

static void report(const char *label, int status) {
    if (WIFEXITED(status)) {
        int code = WEXITSTATUS(status);
        if (code == 0) {
            printf("selftest: %s ok\n", label);
        } else {
            printf("selftest: %s FAILED exit=%d\n", label, code);
        }
    } else if (WIFSIGNALED(status)) {
        printf("selftest: %s FAILED signal=%d\n", label, WTERMSIG(status));
    } else {
        printf("selftest: %s FAILED raw=%d\n", label, status);
    }
}

static void run_exec_case(const char *label, const char *program, char *const argv[]) {
    pid_t pid = fork();
    if (pid < 0) {
        printf("selftest: %s FAILED fork (%s)\n", label, strerror(errno));
        return;
    }
    if (pid == 0) {
        execv(program, argv);
        printf("selftest: %s execv(%s) FAILED errno=%d %s\n", label, program, errno,
               strerror(errno));
        fflush(stdout);
        _exit(90);
    }
    int status = 0;
    waitpid(pid, &status, 0);
    report(label, status);
}

static void run_spawn_case(const char *label, const char *program, char *const argv[]) {
    /*
     * bionic only exports posix_spawn from API 28 on, and the app's minSdk is
     * lower, so the symbol is resolved at runtime. RTLD_DEFAULT is used on
     * purpose: the preloaded libuidexec.so comes first in the search order, so
     * this exercises the hook rather than libc's own implementation.
     */
    typedef int (*spawn_fn)(pid_t *, const char *, const posix_spawn_file_actions_t *,
                            const posix_spawnattr_t *, char *const[], char *const[]);
    spawn_fn spawn_fn_ptr = (spawn_fn)dlsym(RTLD_DEFAULT, "posix_spawn");
    if (spawn_fn_ptr == NULL) {
        printf("selftest: %s FAILED posix_spawn not found (hook not loaded?)\n", label);
        return;
    }
    pid_t pid = 0;
    int rc = spawn_fn_ptr(&pid, program, NULL, NULL, argv, environ);
    if (rc != 0) {
        printf("selftest: %s FAILED posix_spawn rc=%d %s\n", label, rc, strerror(rc));
        return;
    }
    int status = 0;
    waitpid(pid, &status, 0);
    report(label, status);
}

static void print_header(void) {
    char exe[PATH_MAX];
    ssize_t n = readlink("/proc/self/exe", exe, sizeof(exe) - 1);
    if (n > 0) {
        exe[n] = '\0';
        printf("selftest: proc-self-exe=%s\n", exe);
    } else {
        printf("selftest: proc-self-exe=(unreadable)\n");
    }
    const char *preload = getenv("LD_PRELOAD");
    printf("selftest: ld-preload=%s\n", preload != NULL ? preload : "(unset)");
    printf("selftest: uidexec-preloaded=%s\n", uidexec_marker != NULL ? "yes" : "no");
}

int main(int argc, char **argv) {
    if (argc > 1 && strcmp(argv[1], CHILD_MODE) == 0) {
        printf("selftest: child-ok\n");
        fflush(stdout);
        return 0;
    }

    print_header();

    const char *self = self_path();
    printf("selftest: self=%s\n", self != NULL ? self : "(unknown)");

    char *echo_argv[] = {(char *)"/system/bin/echo", (char *)"system-exec-ok", NULL};
    run_exec_case("exec-system-binary", "/system/bin/echo", echo_argv);

    if (self != NULL) {
        char *self_argv[] = {(char *)self, (char *)CHILD_MODE, NULL};
        run_exec_case("exec-data-dir-elf", self, self_argv);
        run_spawn_case("spawn-data-dir-elf", self, self_argv);
    } else {
        printf("selftest: exec-data-dir-elf SKIPPED (self path unknown)\n");
        printf("selftest: spawn-data-dir-elf SKIPPED (self path unknown)\n");
    }

    printf("selftest: probe-done\n");
    fflush(stdout);
    return 0;
}

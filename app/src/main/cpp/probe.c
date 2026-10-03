/*
 * Diagnostic probe: reports how the process was started, which matters because
 * programs launched through the linker64 exec path see the linker as their
 * /proc/self/exe and tools that locate their own installation may misbehave.
 */
#define _GNU_SOURCE

#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <string.h>
#include <unistd.h>

extern char **environ;

int main(int argc, char **argv) {
    char buf[PATH_MAX];
    ssize_t n = readlink("/proc/self/exe", buf, sizeof(buf) - 1);
    if (n > 0) {
        buf[n] = '\0';
        printf("probe: proc-self-exe=%s\n", buf);
    } else {
        printf("probe: proc-self-exe=(unreadable)\n");
    }
    printf("probe: argv0=%s\n", argc > 0 ? argv[0] : "(none)");
    printf("probe: cwd=%s\n", getcwd(buf, sizeof(buf)) != NULL ? buf : "(unknown)");

    for (char **entry = environ; entry != NULL && *entry != NULL; entry++) {
        if (strncmp(*entry, "LD_", 3) == 0 || strncmp(*entry, "PATH", 4) == 0 ||
            strncmp(*entry, "PREFIX", 6) == 0 || strncmp(*entry, "UIDE", 4) == 0) {
            printf("probe: env %s\n", *entry);
        }
    }

if (argc > 1) {
        char resolved[PATH_MAX];
        const char *real = realpath(argv[1], resolved);
        errno = 0;
        int x_ok = access(argv[1], X_OK);
        int x_errno = errno;
        errno = 0;
        int r_ok = access(real != NULL ? real : argv[1], X_OK);
        int r_errno = errno;
        errno = 0;
        int f_ok = access(argv[1], F_OK);
        int f_errno = errno;
        struct stat st;
        memset(&st, 0, sizeof(st));
        errno = 0;
        int st_rc = stat(argv[1], &st);
        int st_errno = errno;
        printf("probe: target=%s\n", argv[1]);
        printf("probe: realpath=%s\n", real != NULL ? real : "(null)");
        printf("probe: x_ok=%d errno=%d (%s)\n", x_ok, x_errno, strerror(x_errno));
        printf("probe: x_ok_realpath=%d errno=%d\n", r_ok, r_errno);
        printf("probe: f_ok=%d errno=%d\n", f_ok, f_errno);
        printf("probe: stat=%d errno=%d mode=%o\n", st_rc, st_errno, (unsigned)st.st_mode);
        fflush(stdout);

        const char *argv0 = getenv("UIDE_PROBE_ARGV0");
        if (argv0 != NULL && argv0[0] != '\0') {
            char **forwarded = &argv[1];
            forwarded[0] = (char *)argv0;
            printf("probe: exec %s with argv0=%s\n", argv[1], argv0);
            fflush(stdout);
            execv(argv[1], forwarded);
        } else {
            execv(argv[1], &argv[1]);
        }
        printf("probe: execv(%s) failed errno=%d %s\n", argv[1], errno, strerror(errno));
        return 1;
    }
    return 0;
}
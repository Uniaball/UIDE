#define _GNU_SOURCE

#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <spawn.h>
#include <stdlib.h>
#include <string.h>
#include <sys/system_properties.h>
#include <unistd.h>

extern char **environ;

#define LINKER32 "/system/bin/linker"
#define LINKER64 "/system/bin/linker64"
#define MAX_HEAD 16
#define LINE_CAP 256
#define SELF_EXE_ENV "UIDE_EXEC_SELF_EXE"

typedef int (*execve_fn)(const char *, char *const[], char *const[]);
typedef int (*execvp_fn)(const char *, char *const[]);
typedef int (*spawn_fn)(pid_t *, const char *, const posix_spawn_file_actions_t *,
                        const posix_spawnattr_t *, char *const[], char *const[]);
typedef ssize_t (*readlink_fn)(const char *, char *, size_t);
typedef ssize_t (*readlinkat_fn)(int, const char *, char *, size_t);

static execve_fn next_execve;
static execvp_fn next_execvp;
static spawn_fn next_posix_spawn;
static readlink_fn next_readlink;
static readlinkat_fn next_readlinkat;

static const char *const PASSTHROUGH[] = {
    "/system/",   "/apex/",     "/vendor/",       "/product/",
    "/system_ext/", "/odm/",     "/data/local/tmp/", "/dev/",
    "/proc/",     "/linkerconfig/", NULL
};

typedef struct {
    char **items;
    int len;
} strvec;

int uidexec_marker(void) { return 0x55494445; }

static void resolve_next(void) __attribute__((constructor));

static void resolve_next(void) {
    next_execve = (execve_fn)dlsym(RTLD_NEXT, "execve");
    next_execvp = (execvp_fn)dlsym(RTLD_NEXT, "execvp");
    next_posix_spawn = (spawn_fn)dlsym(RTLD_NEXT, "posix_spawn");
    next_readlink = (readlink_fn)dlsym(RTLD_NEXT, "readlink");
    next_readlinkat = (readlinkat_fn)dlsym(RTLD_NEXT, "readlinkat");
}

static int device_blocks_data_exec(void) {
    static int cached = -1;
    if (cached < 0) {
        char value[PROP_VALUE_MAX];
        cached = 0;
        if (__system_property_get("ro.build.version.sdk", value) > 0) {
            cached = atoi(value) >= 29 ? 1 : 0;
        }
    }
    return cached;
}

static int is_linker(const char *path) {
    const char *base = strrchr(path, '/');
    base = base != NULL ? base + 1 : path;
    return strcmp(base, "linker") == 0 || strcmp(base, "linker64") == 0;
}

static int needs_launch(const char *path) {
    if (path == NULL || path[0] == '\0') return 0;
    if (!device_blocks_data_exec()) return 0;
    if (is_linker(path)) return 0;
    const char *flag = getenv("UIDE_EXEC_NO_WRAP");
    if (flag != NULL && flag[0] == '1') return 0;
    for (int i = 0; PASSTHROUGH[i] != NULL; i++) {
        if (strncmp(path, PASSTHROUGH[i], strlen(PASSTHROUGH[i])) == 0) return 0;
    }
    return 1;
}

static const char *linker_for(const char *path) {
    unsigned char header[5] = {0};
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return LINKER64;
    size_t got = 0;
    while (got < sizeof(header)) {
        ssize_t n = read(fd, header + got, sizeof(header) - got);
        if (n <= 0) break;
        got += (size_t)n;
    }
    close(fd);
    int elf = got == sizeof(header) && header[0] == 0x7f && header[1] == 'E' &&
              header[2] == 'L' && header[3] == 'F';
    if (elf && header[4] == 1) return LINKER32;
    return LINKER64;
}

static int count_of(char *const *v) {
    int n = 0;
    if (v != NULL) while (v[n] != NULL) n++;
    return n;
}

static strvec split_words(const char *s) {
    strvec out = {NULL, 0};
    int cap = 8;
    out.items = calloc((size_t)cap, sizeof(char *));
    if (out.items == NULL) return out;
    const char *p = s;
    while (*p != '\0') {
        while (*p == ' ' || *p == '\t') p++;
        if (*p == '\0') break;
        const char *start = p;
        while (*p != '\0' && *p != ' ' && *p != '\t') p++;
        char *word = malloc((size_t)(p - start) + 1);
        if (word == NULL) return out;
        memcpy(word, start, (size_t)(p - start));
        word[p - start] = '\0';
        if (out.len == cap) {
            char **grown = realloc(out.items, (size_t)cap * 2 * sizeof(char *));
            if (grown == NULL) {
                free(word);
                return out;
            }
            out.items = grown;
            cap *= 2;
        }
        out.items[out.len++] = word;
    }
    return out;
}

static void free_strvec(strvec *v) {
    if (v->items == NULL) return;
    for (int i = 0; i < v->len; i++) free(v->items[i]);
    free(v->items);
    v->items = NULL;
    v->len = 0;
}

static const char *env_value(char *const envp[], const char *key) {
    size_t n = strlen(key);
    for (int i = 0; envp != NULL && envp[i] != NULL; i++) {
        if (strncmp(envp[i], key, n) == 0 && envp[i][n] == '=') return envp[i] + n + 1;
    }
    return NULL;
}

static char *resolve_in_path(const char *name, const char *path) {
    const char *seg = path;
    while (*seg != '\0') {
        const char *end = strchr(seg, ':');
        size_t len = end != NULL ? (size_t)(end - seg) : strlen(seg);
        if (len == 0) {
            seg = "/system/bin";
            len = strlen(seg);
        }
        char *candidate = malloc(len + strlen(name) + 2);
        if (candidate == NULL) return NULL;
        memcpy(candidate, seg, len);
        candidate[len] = '/';
        strcpy(candidate + len + 1, name);
        if (access(candidate, X_OK) == 0) return candidate;
        free(candidate);
        if (end == NULL) break;
        seg = end + 1;
    }
    return NULL;
}

static char *resolve_program(const char *name, char *const envp[]) {
    if (name == NULL || name[0] == '\0') return NULL;
    if (strchr(name, '/') != NULL) {
        return access(name, X_OK) == 0 ? strdup(name) : NULL;
    }
    const char *path = env_value(envp, "PATH");
    char *found = resolve_in_path(name, path != NULL ? path : "/system/bin");
    if (found == NULL && path != NULL) found = resolve_in_path(name, "/system/bin");
    return found;
}

static char **build_argv(const char *linker, char **head, int head_len, const char *target,
                         char *const *rest) {
    int rest_len = rest != NULL ? count_of(rest) - 1 : 0;
    if (rest_len < 0) rest_len = 0;
    int total = 1 + head_len + 1 + rest_len + 1;
    char **out = calloc((size_t)total, sizeof(char *));
    if (out == NULL) return NULL;
    int i = 0;
    out[i++] = (char *)linker;
    for (int j = 0; j < head_len; j++) out[i++] = head[j];
    out[i++] = (char *)target;
    for (int j = 1; rest != NULL && rest[j] != NULL; j++) out[i++] = rest[j];
    out[i] = NULL;
    return out;
}

static int prepare_head(const char *path, char *const envp[], char **head, int *head_len) {
    *head_len = 0;
    char line[LINE_CAP];
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    ssize_t n = read(fd, line, sizeof(line) - 1);
    close(fd);
    if (n < 2 || line[0] != '#' || line[1] != '!') return 0;
    line[n] = '\0';
    char *nl = strchr(line, '\n');
    if (nl != NULL) *nl = '\0';
    char *body = line + 2;
    while (*body == ' ' || *body == '\t') body++;
    strvec words = split_words(body);
    if (words.len == 0) {
        free_strvec(&words);
        return 0;
    }
    char *interp = resolve_program(words.items[0], envp);
    if (interp == NULL) {
        free_strvec(&words);
        return 0;
    }
    head[(*head_len)++] = interp;
    for (int i = 1; i < words.len && *head_len < MAX_HEAD; i++) {
        head[(*head_len)++] = strdup(words.items[i]);
    }
    free_strvec(&words);
    return 1;
}

static void free_head(char **head, int head_len) {
    for (int i = 0; i < head_len; i++) free(head[i]);
}

static char **env_with_self(char *const envp[], const char *program) {
    const char *existing = env_value(envp, SELF_EXE_ENV);
    if (existing != NULL && existing[0] != '\0') return NULL;
    int count = 0;
    while (envp != NULL && envp[count] != NULL) count++;
    char **copy = calloc((size_t)count + 2, sizeof(char *));
    if (copy == NULL) return NULL;
    for (int i = 0; i < count; i++) copy[i] = envp[i];
    size_t prefix = strlen(SELF_EXE_ENV);
    size_t program_len = strlen(program);
    copy[count] = malloc(prefix + 2 + program_len);
    if (copy[count] == NULL) {
        free(copy);
        return NULL;
    }
    memcpy(copy[count], SELF_EXE_ENV, prefix);
    copy[count][prefix] = '=';
    memcpy(copy[count] + prefix + 1, program, program_len + 1);
    return copy;
}

static int exec_launched(const char *path, char *const argv[], char *const envp[],
                         char **head, int head_len) {
    const char *linker = head_len > 0 ? linker_for(head[0]) : linker_for(path);
    char **next = build_argv(linker, head, head_len, path, argv);
    if (next == NULL) {
        free_head(head, head_len);
        errno = ENOMEM;
        return -1;
    }
    char **child_env = env_with_self(envp, path);
    next_execve(linker, next, child_env != NULL ? child_env : envp);
    int saved = errno;
    free(next);
    free(child_env);
    free_head(head, head_len);
    errno = saved;
    return -1;
}

static int posix_spawn_launched(const char *path, char *const argv[], char *const envp[],
                                char **head, int head_len,
                                const posix_spawn_file_actions_t *actions,
                                const posix_spawnattr_t *attr, pid_t *pid) {
    const char *linker = head_len > 0 ? linker_for(head[0]) : linker_for(path);
    char **next = build_argv(linker, head, head_len, path, argv);
    if (next == NULL) {
        free_head(head, head_len);
        return ENOMEM;
    }
    char **child_env = env_with_self(envp, path);
    int rc = next_posix_spawn(pid, linker, actions, attr, next,
                              child_env != NULL ? child_env : envp);
    free(next);
    free(child_env);
    free_head(head, head_len);
    return rc;
}

/*
 * A program started as `/system/bin/linker64 <program>` keeps the linker as its
 * mm->exe_file, so /proc/self/exe reports the linker. Tools that locate their own
 * installation (cmake looking for its Modules directory, for example) break on
 * that, and CMake offers no override, so the real path is reported instead.
 */
static ssize_t self_exe_result(char *buf, size_t bufsz) {
    const char *self = getenv(SELF_EXE_ENV);
    if (self == NULL || self[0] == '\0' || bufsz == 0) return -1;
    size_t len = strlen(self);
    if (len > bufsz) len = bufsz;
    memcpy(buf, self, len);
    return (ssize_t)len;
}

ssize_t readlink(const char *path, char *buf, size_t bufsz) {
    if (path != NULL && strcmp(path, "/proc/self/exe") == 0) {
        ssize_t len = self_exe_result(buf, bufsz);
        if (len >= 0) return len;
    }
    return next_readlink(path, buf, bufsz);
}

ssize_t readlinkat(int dirfd, const char *path, char *buf, size_t bufsz) {
    if (path != NULL && strcmp(path, "/proc/self/exe") == 0 &&
        (dirfd == AT_FDCWD || dirfd == -100)) {
        ssize_t len = self_exe_result(buf, bufsz);
        if (len >= 0) return len;
    }
    return next_readlinkat(dirfd, path, buf, bufsz);
}

int execve(const char *path, char *const argv[], char *const envp[]) {
    if (!needs_launch(path)) return next_execve(path, argv, envp);
    char *head[MAX_HEAD];
    int head_len = 0;
    prepare_head(path, envp, head, &head_len);
    return exec_launched(path, argv, envp, head, head_len);
}

int execv(const char *path, char *const argv[]) {
    return execve(path, argv, environ);
}

int execvp(const char *file, char *const argv[]) {
    if (strchr(file, '/') == NULL) {
        char *resolved = resolve_program(file, environ);
        if (resolved == NULL) return next_execvp(file, argv);
        int rc = execve(resolved, argv, environ);
        int saved = errno;
        free(resolved);
        errno = saved;
        return rc;
    }
    return execve(file, argv, environ);
}

int execvpe(const char *file, char *const argv[], char *const envp[]) {
    if (strchr(file, '/') == NULL) {
        char *resolved = resolve_program(file, envp);
        if (resolved == NULL) return next_execvp(file, argv);
        int rc = execve(resolved, argv, envp);
        int saved = errno;
        free(resolved);
        errno = saved;
        return rc;
    }
    return execve(file, argv, envp);
}

int posix_spawn(pid_t *pid, const char *path, const posix_spawn_file_actions_t *actions,
                const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
    if (!needs_launch(path)) return next_posix_spawn(pid, path, actions, attr, argv, envp);
    char *head[MAX_HEAD];
    int head_len = 0;
    prepare_head(path, envp, head, &head_len);
    return posix_spawn_launched(path, argv, envp, head, head_len, actions, attr, pid);
}

int posix_spawnp(pid_t *pid, const char *file, const posix_spawn_file_actions_t *actions,
                 const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
    if (strchr(file, '/') == NULL) {
        char *resolved = resolve_program(file, envp);
        if (resolved == NULL) return next_posix_spawn(pid, file, actions, attr, argv, envp);
        int rc = posix_spawn(pid, resolved, actions, attr, argv, envp);
        int saved = errno;
        free(resolved);
        errno = saved;
        return rc;
    }
    return posix_spawn(pid, file, actions, attr, argv, envp);
}

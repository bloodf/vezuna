// Simulator-only audit with a fresh synthetic-data installation. Never bundle this library.
// Compile with the simulator SDK and inject through SIMCTL_CHILD_DYLD_INSERT_LIBRARIES.
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <string.h>
#include <sys/param.h>
#include <sys/stat.h>
#include <unistd.h>

static _Thread_local int reporting;

__attribute__((constructor)) static void loaded(void) {
    const char message[] = "VEZUNA_FILE_API_PROBE_LOADED\n";
    write(STDERR_FILENO, message, sizeof(message) - 1);
}

static void report(const char *api, const char *path, int result, void *caller) {
    Dl_info image;
    if (reporting || !dladdr(caller, &image) || !image.dli_fname ||
        !strstr(image.dli_fname, "/Vezuna.app/")) return;
    reporting = 1;
    char message[2 * MAXPATHLEN];
    int length = snprintf(message, sizeof(message),
        "VEZUNA_FILE_API %s result=%d caller=%s path=%s\n",
        api, result, image.dli_sname ? image.dli_sname : "unknown", path);
    if (length > 0) {
        size_t count = (size_t)length < sizeof(message) ? (size_t)length : sizeof(message) - 1;
        write(STDERR_FILENO, message, count);
    }
    reporting = 0;
}

static int probe_stat(const char *path, struct stat *metadata) {
    int result = stat(path, metadata);
    int saved_errno = errno;
    report("stat", path ? path : "(null)", result, __builtin_return_address(0));
    errno = saved_errno;
    return result;
}

static int probe_fstat(int descriptor, struct stat *metadata) {
    int result = fstat(descriptor, metadata);
    int saved_errno = errno;
    char path[MAXPATHLEN] = "(unresolved descriptor)";
    fcntl(descriptor, F_GETPATH, path);
    report("fstat", path, result, __builtin_return_address(0));
    errno = saved_errno;
    return result;
}

#define INTERPOSE(replacement, original) \
    __attribute__((used)) static struct { const void *new_function; const void *old_function; } \
    interpose_##original __attribute__((section("__DATA,__interpose"))) = { \
        (const void *)(replacement), (const void *)(original) \
    }

INTERPOSE(probe_stat, stat);
INTERPOSE(probe_fstat, fstat);

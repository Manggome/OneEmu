#include "logsink.h"
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <fcntl.h>
#include <mutex>
#include <unistd.h>

namespace {
std::mutex g_mutex;
int g_fd = -1;
}

void logSinkOpen(const char* path) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_fd >= 0) { close(g_fd); g_fd = -1; }
    if (!path || !*path) return;
    g_fd = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_APPEND | O_CLOEXEC, 0644);
}

void logSinkWrite(char level, const char* line) {
    if (!line) return;
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_fd < 0) return;
    timespec ts{}; clock_gettime(CLOCK_REALTIME, &ts);
    tm t{}; localtime_r(&ts.tv_sec, &t);
    char head[40];
    int n = snprintf(head, sizeof head, "%02d:%02d:%02d.%03d %c ", t.tm_hour, t.tm_min, t.tm_sec, (int)(ts.tv_nsec / 1000000), level);
    size_t len = strnlen(line, 2000);
    // One write per line keeps lines intact across threads and survives a crash right after.
    char buf[2100];
    if ((size_t)n + len + 1 > sizeof buf) len = sizeof buf - n - 1;
    memcpy(buf, head, n); memcpy(buf + n, line, len); buf[n + len] = '\n';
    write(g_fd, buf, n + len + 1);
}

void logSinkPrintf(char level, const char* fmt, ...) {
    if (g_fd < 0) return; // racy read is fine: worst case one line is dropped around open/close
    char buf[1024];
    va_list ap; va_start(ap, fmt);
    vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    logSinkWrite(level, buf);
}

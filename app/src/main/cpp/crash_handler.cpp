// Minimal native crash recorder. Android's tombstones are not readable by the app itself, so when a
// libretro core faults we write "signal, fault address, faulting module + offset, a few frames" to a
// file the Kotlin side includes in its crash report. The module offsets can be symbolised offline
// against the unstripped core builds.
#include "crash_handler.h"
#include "log.h"
#include <dlfcn.h>
#include <fcntl.h>
#include <signal.h>
#include <string.h>
#include <sys/ucontext.h>
#include <time.h>
#include <unistd.h>
#include <unwind.h>

namespace {

char g_path[512] = {0};
struct sigaction g_prev[32];
const int kSignals[] = { SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGABRT, SIGTRAP };

// Async-signal-safe helpers (no printf).
void writeStr(int fd, const char* s) { if (s) write(fd, s, strlen(s)); }
void writeHex(int fd, unsigned long v) {
    char buf[19] = "0x";
    const char* d = "0123456789abcdef";
    int n = 2;
    bool started = false;
    for (int i = 60; i >= 0; i -= 4) {
        int nib = (v >> i) & 0xf;
        if (nib || started || i == 0) { buf[n++] = d[nib]; started = true; }
    }
    buf[n] = 0;
    writeStr(fd, buf);
}
void writeDec(int fd, long v) {
    char buf[24]; int n = 0;
    if (v < 0) { write(fd, "-", 1); v = -v; }
    do { buf[n++] = (char)('0' + v % 10); v /= 10; } while (v && n < 23);
    while (n) write(fd, &buf[--n], 1);
}

void writeAddr(int fd, uintptr_t pc) {
    Dl_info info;
    writeHex(fd, pc);
    if (dladdr((void*)pc, &info) && info.dli_fname) {
        const char* base = strrchr(info.dli_fname, '/');
        writeStr(fd, "  ");
        writeStr(fd, base ? base + 1 : info.dli_fname);
        writeStr(fd, "+");
        writeHex(fd, pc - (uintptr_t)info.dli_fbase);
        if (info.dli_sname) { writeStr(fd, " ("); writeStr(fd, info.dli_sname); writeStr(fd, ")"); }
    }
    writeStr(fd, "\n");
}

struct UnwindState { int fd; int count; uintptr_t skipUntil; bool skipping; };

_Unwind_Reason_Code unwindCb(struct _Unwind_Context* ctx, void* arg) {
    auto* st = (UnwindState*)arg;
    uintptr_t pc = _Unwind_GetIP(ctx);
    if (!pc) return _URC_NO_REASON;
    if (st->count >= 24) return _URC_END_OF_STACK;
    writeStr(st->fd, "  #");
    writeDec(st->fd, st->count++);
    writeStr(st->fd, " ");
    writeAddr(st->fd, pc);
    return _URC_NO_REASON;
}

void handler(int sig, siginfo_t* si, void* uctx) {
    if (g_path[0]) {
        int fd = open(g_path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
        if (fd >= 0) {
            writeStr(fd, "signal "); writeDec(fd, sig);
            writeStr(fd, " ("); writeStr(fd, sig == SIGSEGV ? "SIGSEGV" : sig == SIGBUS ? "SIGBUS" : sig == SIGILL ? "SIGILL" : sig == SIGFPE ? "SIGFPE" : sig == SIGABRT ? "SIGABRT" : "SIGTRAP");
            writeStr(fd, ") code "); writeDec(fd, si ? si->si_code : 0);
            writeStr(fd, " fault addr "); writeHex(fd, si ? (unsigned long)si->si_addr : 0);
            writeStr(fd, "\ntime "); writeDec(fd, (long)time(nullptr));
            writeStr(fd, "\n");
            auto* uc = (ucontext_t*)uctx;
            if (uc) {
#if defined(__aarch64__)
                uintptr_t pc = uc->uc_mcontext.pc;
                uintptr_t lr = uc->uc_mcontext.regs[30];
                writeStr(fd, "pc "); writeAddr(fd, pc);
                writeStr(fd, "lr "); writeAddr(fd, lr);
#endif
            }
            writeStr(fd, "backtrace (from handler; frames above the fault may be missing):\n");
            UnwindState st{ fd, 0, 0, false };
            _Unwind_Backtrace(unwindCb, &st);
            close(fd);
        }
    }
    // Restore the previous disposition and re-raise so the system still produces its tombstone.
    if (sig >= 0 && sig < 32) sigaction(sig, &g_prev[sig], nullptr);
    else signal(sig, SIG_DFL);
    raise(sig);
}

} // namespace

void installCrashHandler(const char* filePath) {
    if (!filePath) return;
    strncpy(g_path, filePath, sizeof(g_path) - 1);
    struct sigaction sa;
    memset(&sa, 0, sizeof sa);
    sa.sa_sigaction = handler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);
    for (int s : kSignals) sigaction(s, &sa, &g_prev[s]);
    LOGI("native crash handler installed -> %s", filePath);
}

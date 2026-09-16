#pragma once
// Session log file: every frontend LOGx line and every core log line is appended to a plain text file the app
// can attach to bug reports. logcat is not reliably readable by the app on all devices (Samsung restricts it to
// the calling process), so crash reports of a previous process were empty without this.
void logSinkOpen(const char* path);            // truncates and (re)opens; nullptr/empty closes
void logSinkWrite(char level, const char* line); // thread-safe; a trailing newline is added
void logSinkPrintf(char level, const char* fmt, ...) __attribute__((format(printf, 2, 3)));

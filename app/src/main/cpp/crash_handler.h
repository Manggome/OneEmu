#pragma once
/** Writes a short native crash record (signal, fault address, module+offset, frames) to filePath. */
void installCrashHandler(const char* filePath);

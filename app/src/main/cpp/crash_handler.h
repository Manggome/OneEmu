#pragma once
/** Writes a short native crash record (signal, fault address, module+offset, frames) to filePath. */
void installCrashHandler(const char* filePath);
/** Re-installs the handler as the only one (previous disposition = default). Call after a core's retro_deinit:
 *  cores such as Azahar (dynarmic) leave their own SIGSEGV/SIGBUS handler installed; once the core is unloaded
 *  that handler points at unmapped code and the next fault kills the process without any record. */
void reinstallCrashHandler();

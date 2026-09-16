#pragma once
#include <android/log.h>
#include "logsink.h"
#define LOG_TAG "OneEmu"
// Every line goes to logcat and to the session log file (see logsink.h).
#define LOGD(...) do { __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__); logSinkPrintf('D', __VA_ARGS__); } while (0)
#define LOGI(...) do { __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__); logSinkPrintf('I', __VA_ARGS__); } while (0)
#define LOGW(...) do { __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__); logSinkPrintf('W', __VA_ARGS__); } while (0)
#define LOGE(...) do { __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__); logSinkPrintf('E', __VA_ARGS__); } while (0)

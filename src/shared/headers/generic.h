/*
 * Copyright 2012 Adam Murdoch
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

#ifndef __INCLUDE_GENERIC_H__
#define __INCLUDE_GENERIC_H__

#include <jni.h>

#include "native_platform_version.h"
#include "net_rubygrapefruit_platform_internal_jni_NativeLogger.h"

#ifdef __cplusplus
extern "C" {
#endif

// Corresponds to NativeLibraryFunctions constants
#define STDOUT_DESCRIPTOR 0
#define STDERR_DESCRIPTOR 1
#define STDIN_DESCRIPTOR 2

// Corresponds to values of FileInfo.Type
#define FILE_TYPE_FILE 0
#define FILE_TYPE_DIRECTORY 1
#define FILE_TYPE_SYMLINK 2
#define FILE_TYPE_OTHER 3
#define FILE_TYPE_MISSING 4

// Corresponds to values of FunctionResult.Failure
#define FAILURE_GENERIC 0
#define FAILURE_NO_SUCH_FILE 1
#define FAILURE_NOT_A_DIRECTORY 2
#define FAILURE_PERMISSIONS 3

// Corresponds to values of FileWatcherCallback.Type
#define FILE_EVENT_CREATED 0
#define FILE_EVENT_REMOVED 1
#define FILE_EVENT_MODIFIED 2
#define FILE_EVENT_INVALIDATE 3
#define FILE_EVENT_UNKNOWN 4

#define IS_SET(flags, flag) (((flags) & (flag)) == (flag))
#define IS_ANY_SET(flags, mask) (((flags) & (mask)) != 0)

/*
 * Marks the given result as failed, using the given error message
 */
extern void mark_failed_with_message(JNIEnv* env, const char* message, jobject result);

/*
 * Marks the given result as failed, using the given error message and the current value of errno/GetLastError()
 */
extern void mark_failed_with_errno(JNIEnv* env, const char* message, jobject result);

/*
 * Marks the given result as failed, using the given error message and error code
 */
extern void mark_failed_with_code(JNIEnv* env, const char* message, int error_code, const char* error_code_message, jobject result);

/**
 * Maps system error code to a failure constant above.
 */
extern int map_error_code(int error_code);

/**
 * Attaches JNI to the current thread.
 */
extern JNIEnv* attach_jni(JavaVM* jvm, const char* name, bool daemon);

/**
 * Detaches JNI from the current thread.
 */
extern int detach_jni(JavaVM* jvm);

/*
 * Converts the given Java string to a NULL terminated wchar_str. Should call free() when finished.
 *
 * Returns NULL on failure.
 */
extern wchar_t*
java_to_wchar(JNIEnv* env, jstring string, jobject result);

/*
 * Converts the given wchar_t string to a Java string.
 *
 * Returns NULL on failure.
 */
extern jstring wchar_to_java(JNIEnv* env, const wchar_t* chars, size_t len, jobject result);

/*
 * Converts the given Java string to a NULL terminated char string. Should call free() when finished.
 *
 * Returns NULL on failure.
 */
extern char* java_to_char(JNIEnv* env, jstring string, jobject result);

/*
 * Converts the given NULL terminated char string to a Java string.
 *
 * Returns NULL on failure.
 */
extern jstring char_to_java(JNIEnv* env, const char* chars, jobject result);

/*
 * Converts the given Java string to a NULL terminated char string (encoded with modified UTF-8). Should call free() when finished.
 *
 * Returns NULL on failure.
 */
extern char* java_to_utf_char(JNIEnv* env, jstring string, jobject result);

/*
 * Converts the given NULL terminated char string (encoded with modified UTF-8) to a Java string.
 *
 * Returns NULL on failure.
 */
extern jstring utf_char_to_java(JNIEnv* env, const char* chars, jobject result);

typedef struct file_stat {
    jint fileType;
    jlong lastModified;
    jlong size;
} file_stat_t;

#define LOG_FINEST 0
#define LOG_FINER 1
#define LOG_FINE 2
#define LOG_CONFIG 3
#define LOG_INFO 4
#define LOG_WARNING 5
#define LOG_SEVERE 6

#define log_finest(env, message, ...) printlog(env, LOG_FINEST, message, __VA_ARGS__)
#define log_finer(env, message, ...) printlog(env, LOG_FINER, message, __VA_ARGS__)
#define log_fine(env, message, ...) printlog(env, LOG_FINE, message, __VA_ARGS__)
#define log_config(env, message, ...) printlog(env, LOG_CONFIG, message, __VA_ARGS__)
#define log_info(env, message, ...) printlog(env, LOG_INFO, message, __VA_ARGS__)
#define log_warning(env, message, ...) printlog(env, LOG_WARNING, message, __VA_ARGS__)
#define log_severe(env, message, ...) printlog(env, LOG_SEVERE, message, __VA_ARGS__)

#ifdef _WIN32
void printlog(JNIEnv* env, int level, const wchar_t* message, ...);
#else
void printlog(JNIEnv* env, int level, const char* message, ...);
#endif

#ifdef __cplusplus
}
#endif

#endif

/*
 * Initial version copied from https://github.com/JetBrains/intellij-community/blob/master/native/fsNotifier/mac/fsnotifier.c
 */
// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

/*
 * Apple specific functions.
 */
#if defined(__APPLE__)

#include "native.h"
#include "generic.h"
#include <CoreServices/CoreServices.h>
#include <pthread.h>
#include <strings.h>

JavaVM* jvm = NULL;
bool invalidStateDetected = false;

typedef struct watch_details {
    CFMutableArrayRef rootsToWatch;
    FSEventStreamRef watcherStream;
    pthread_t watcherThread;
    jobject watcherCallback;
    CFRunLoopRef threadLoop;
} watch_details_t;

static void reportEvent(jint type, char *path, jobject watcherCallback) {
    // TODO What does this do?
    size_t len = 0;
    if (path != NULL) {
        len = strlen(path);
        for (char *p = path; *p != '\0'; p++) {
            if (*p == '\n') {
                *p = '\0';
            }
        }
    }

    // TODO Extract this logic to some global function
    JNIEnv* env;
    int getEnvStat = jvm->GetEnv((void **)&env, JNI_VERSION_1_6);
    if (getEnvStat != JNI_OK) {
        invalidStateDetected = true;
        return;
    }

    printf("~~~~ Changed: %s %d\n", path, type);

    jclass callback_class = env->GetObjectClass(watcherCallback);
    jmethodID methodCallback = env->GetMethodID(callback_class, "pathChanged", "(ILjava/lang/String;)V");
    env->CallVoidMethod(watcherCallback, methodCallback, type, env->NewStringUTF(path));
}

static void callback(ConstFSEventStreamRef streamRef,
                     void *clientCallBackInfo,
                     size_t numEvents,
                     void *eventPaths,
                     const FSEventStreamEventFlags eventFlags[],
                     const FSEventStreamEventId eventIds[]) {
    if (invalidStateDetected) return;
    char **paths = (char**) eventPaths;

    jobject watcherCallback = (jobject) clientCallBackInfo;

    for (int i = 0; i < numEvents; i++) {
        FSEventStreamEventFlags flags = eventFlags[i];
        printf("~~~~ Event flags: 0x%x for %s\n", flags, paths[i]);
        jint type;
        if (IS_SET(flags, kFSEventStreamEventFlagMustScanSubDirs)) {
            type = FILE_EVENT_INVALIDATE;
        } else if (IS_SET(flags, kFSEventStreamEventFlagItemRenamed)) {
            if (IS_SET(flags, kFSEventStreamEventFlagItemCreated)) {
                type = FILE_EVENT_REMOVED;
            } else {
                type = FILE_EVENT_CREATED;
            }
        } else if (IS_SET(flags, kFSEventStreamEventFlagItemModified)) {
            type = FILE_EVENT_MODIFIED;
        } else if (IS_SET(flags, kFSEventStreamEventFlagItemRemoved)) {
            type = FILE_EVENT_REMOVED;
        } else if (IS_SET(flags, kFSEventStreamEventFlagItemCreated)) {
            type = FILE_EVENT_CREATED;
        } else if (IS_SET(flags, kFSEventStreamEventFlagItemInodeMetaMod)) {
            // File locked
            type = FILE_EVENT_MODIFIED;
        } else {
            printf("~~~~ Unknown event 0x%x for %s\n", flags, paths[i]);
            type = FILE_EVENT_UNKNOWN;
        }
        reportEvent(type, paths[i], watcherCallback);
    }
}

static void *EventProcessingThread(void *data) {
    watch_details_t *details = (watch_details_t*) data;

    printf("~~~~ Starting thread\n");
    JNIEnv* env;
    int getEnvStat = jvm->GetEnv((void **)&env, JNI_VERSION_1_6);
    if (getEnvStat == JNI_EDETACHED) {
        if (jvm->AttachCurrentThread((void **) &env, NULL) != JNI_OK) {
            invalidStateDetected = true;
            return NULL;
        }
    } else if (getEnvStat == JNI_EVERSION) {
        invalidStateDetected = true;
        return NULL;
    }

    CFRunLoopRef threadLoop = CFRunLoopGetCurrent();
    FSEventStreamScheduleWithRunLoop(details->watcherStream, threadLoop, kCFRunLoopDefaultMode);
    FSEventStreamStart(details->watcherStream);
    details->threadLoop = threadLoop;
    // TODO We should wait for this in the caller thread otherwise stopWatching() might crash
    // This triggers run loop for this thread, causing it to run until we explicitly stop it.
    CFRunLoopRun();

    printf("~~~~ Stopping thread\n");
    jvm->DetachCurrentThread();

    return NULL;
}

void freeDetails(JNIEnv *env, watch_details_t *details) {
    CFMutableArrayRef rootsToWatch = details->rootsToWatch;
    FSEventStreamRef watcherStream = details->watcherStream;
    pthread_t watcherThread = details->watcherThread;
    jobject watcherCallback = details->watcherCallback;
    CFRunLoopRef threadLoop = details->threadLoop;
    free(details);

    if (threadLoop != NULL) {
        CFRunLoopStop(threadLoop);
    }

    if (watcherThread != NULL) {
        pthread_join(watcherThread, NULL);
    }

    if (rootsToWatch != NULL) {
        for (int i = 0; i < CFArrayGetCount(rootsToWatch); i++) {
            const void *value = CFArrayGetValueAtIndex(rootsToWatch, i);
            CFRelease(value);
        }
        // TODO Can we release these earlier?
        CFRelease(rootsToWatch);
    }

    if (watcherStream != NULL) {
        FSEventStreamStop(watcherStream);
        FSEventStreamInvalidate(watcherStream);
        FSEventStreamRelease(watcherStream);
        // TODO: consider using FSEventStreamFlushSync to flush all pending events.
    }

    if (watcherCallback != NULL) {
        env->DeleteGlobalRef(watcherCallback);
    }
}

JNIEXPORT jobject JNICALL
Java_net_rubygrapefruit_platform_internal_jni_OsxFileEventFunctions_startWatching(JNIEnv *env, jclass target, jobjectArray paths, long latencyInMillis, jobject javaCallback, jobject result) {

    printf("\n~~~~ Configuring...\n");

    invalidStateDetected = false;
    CFMutableArrayRef rootsToWatch = CFArrayCreateMutable(NULL, 0, NULL);
    if (rootsToWatch == NULL) {
        mark_failed_with_errno(env, "Could not allocate array to store roots to watch.", result);
        return NULL;
    }
    int count = env->GetArrayLength(paths);
    if (count == 0) {
        mark_failed_with_errno(env, "No paths given to watch.", result);
        return NULL;
    }

    watch_details_t* details = (watch_details_t*) malloc(sizeof(watch_details_t));
    details->rootsToWatch = rootsToWatch;
    for (int i = 0; i < count; i++) {
        jstring path = (jstring) env->GetObjectArrayElement(paths, i);
        char* watchedPath = java_to_char(env, path, result);
        printf("~~~~ Watching %s\n", watchedPath);
        if (watchedPath == NULL) {
            mark_failed_with_errno(env, "Could not allocate string to store root to watch.", result);
            freeDetails(env, details);
            return NULL;
        }
        CFStringRef stringPath = CFStringCreateWithCString(NULL, watchedPath, kCFStringEncodingUTF8);
        free(watchedPath);
        if (stringPath == NULL) {
            mark_failed_with_errno(env, "Could not create CFStringRef.", result);
            freeDetails(env, details);
            return NULL;
        }
        CFArrayAppendValue(rootsToWatch, stringPath);
    }

    details->watcherCallback = env->NewGlobalRef(javaCallback);
    if (details->watcherCallback == NULL) {
        mark_failed_with_errno(env, "Could not create global reference for callback.", result);
        freeDetails(env, details);
        return NULL;
    }

    FSEventStreamContext context = {0, (void*) details->watcherCallback, NULL, NULL, NULL};
    details->watcherStream = FSEventStreamCreate (
                NULL,
                &callback,
                &context,
                rootsToWatch,
                kFSEventStreamEventIdSinceNow,
                latencyInMillis / 1000.0,
                kFSEventStreamCreateFlagNoDefer | kFSEventStreamCreateFlagFileEvents);
    if (details->watcherStream == NULL) {
        mark_failed_with_errno(env, "Could not create FSEventStreamCreate to track changes.", result);
        freeDetails(env, details);
        return NULL;
    }

    if (pthread_create(&(details->watcherThread), NULL, EventProcessingThread, details) != 0) {
        mark_failed_with_errno(env, "Could not create file watcher thread.", result);
        freeDetails(env, details);
        return NULL;
    }

    // TODO Should this be somewhere global?
    int jvmStatus = env->GetJavaVM(&jvm);
    if (jvmStatus < 0) {
        mark_failed_with_errno(env, "Could not store jvm instance.", result);
        freeDetails(env, details);
        return NULL;
    }

    jclass clsWatcher = env->FindClass("net/rubygrapefruit/platform/internal/jni/OsxFileEventFunctions$WatcherImpl");
    jmethodID constructor = env->GetMethodID(clsWatcher, "<init>", "(Ljava/lang/Object;)V");
    return env->NewObject(clsWatcher, constructor, env->NewDirectByteBuffer(details, sizeof(details)));
}

JNIEXPORT void JNICALL
Java_net_rubygrapefruit_platform_internal_jni_OsxFileEventFunctions_stopWatching(JNIEnv *env, jclass target, jobject detailsObj, jobject result) {
    watch_details_t *details = (watch_details_t*) env->GetDirectBufferAddress(detailsObj);

    if (invalidStateDetected) {
        // report and reset flag, but try to clean up state as much as possible
        mark_failed_with_errno(env, "Watcher is in invalid state, reported changes may be incorrect.", result);
    }

    freeDetails(env, details);
}

#endif

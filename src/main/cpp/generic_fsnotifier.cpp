#if defined(_WIN32) || defined(__APPLE__)

#include "generic_fsnotifier.h"

AbstractServer::AbstractServer(JNIEnv* env, jobject watcherCallback) {
    JavaVM* jvm;
    int jvmStatus = env->GetJavaVM(&jvm);
    if (jvmStatus < 0) {
        throw FileWatcherException("Could not store jvm instance");
    }
    this->jvm = jvm;

    jclass callbackClass = env->GetObjectClass(watcherCallback);
    this->watcherCallbackMethod = env->GetMethodID(callbackClass, "pathChanged", "(ILjava/lang/String;)V");

    jobject globalWatcherCallback = env->NewGlobalRef(watcherCallback);
    if (globalWatcherCallback == NULL) {
        throw FileWatcherException("Could not get global ref for watcher callback");
    }
    this->watcherCallback = globalWatcherCallback;
}

AbstractServer::~AbstractServer() {
    if (watcherCallback != NULL) {
        JNIEnv* env = getThreadEnv();
        if (env != NULL) {
            env->DeleteGlobalRef(watcherCallback);
        }
    }
}

static JNIEnv* lookupThreadEnv(JavaVM* jvm) {
    JNIEnv* env;
    // TODO Verify that JNI 1.6 is the right version
    jint ret = jvm->GetEnv((void**) &(env), JNI_VERSION_1_6);
    if (ret != JNI_OK) {
        fprintf(stderr, "Failed to get JNI env for current thread: %d\n", ret);
        throw FileWatcherException("Failed to get JNI env for current thread");
    }
    return env;
}

JNIEnv* AbstractServer::getThreadEnv() {
    return lookupThreadEnv(jvm);
}

void AbstractServer::reportChange(JNIEnv* env, int type, jstring path) {
    env->CallVoidMethod(watcherCallback, watcherCallbackMethod, type, path);
}

#endif
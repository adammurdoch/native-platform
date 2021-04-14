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

/*
 * Apple specific functions.
 */
#if defined(__APPLE__)

#include "generic.h"
#include "net_rubygrapefruit_platform_internal_jni_MemoryFunctions.h"
#include "net_rubygrapefruit_platform_internal_jni_OsxMemoryFunctions.h"
#include "net_rubygrapefruit_platform_internal_jni_PosixFileSystemFunctions.h"
#include <mach/mach.h>
#include <stdlib.h>
#include <string.h>
#include <sys/attr.h>
#include <sys/mount.h>
#include <sys/param.h>
#include <sys/sysctl.h>
#include <sys/types.h>
#include <sys/ucred.h>
#include <unistd.h>

typedef struct vol_caps_buf {
    u_int32_t size;
    vol_capabilities_attr_t caps;
} vol_caps_buf_t;

/*
 * File system functions
 */
JNIEXPORT void JNICALL
Java_net_rubygrapefruit_platform_internal_jni_PosixFileSystemFunctions_listFileSystems(JNIEnv* env, jclass target, jobject info, jobject result) {
    int fs_count = getfsstat(NULL, 0, MNT_NOWAIT);
    if (fs_count < 0) {
        mark_failed_with_errno(env, "could not stat file systems", result);
        return;
    }

    size_t len = fs_count * sizeof(struct statfs);
    struct statfs* buf = (struct statfs*) malloc(len);
    if (getfsstat(buf, len, MNT_NOWAIT) < 0) {
        mark_failed_with_errno(env, "could not stat file systems", result);
        free(buf);
        return;
    }

    jclass info_class = env->GetObjectClass(info);
    jmethodID method = env->GetMethodID(info_class, "add", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZZZ)V");
    jmethodID unknownFsMethod = env->GetMethodID(info_class, "addUnknown", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Z)V");

    for (int i = 0; i < fs_count; i++) {
        jboolean caseSensitive = JNI_TRUE;
        jboolean casePreserving = JNI_TRUE;

        struct attrlist alist;
        memset(&alist, 0, sizeof(alist));
        alist.bitmapcount = ATTR_BIT_MAP_COUNT;
        alist.volattr = ATTR_VOL_CAPABILITIES | ATTR_VOL_INFO;
        vol_caps_buf_t buffer;

        jstring mount_point = char_to_java(env, buf[i].f_mntonname, result);
        jstring file_system_type = char_to_java(env, buf[i].f_fstypename, result);
        jstring device_name = char_to_java(env, buf[i].f_mntfromname, result);
        jboolean remote = (buf[i].f_flags & MNT_LOCAL) == 0;

        // getattrlist requires the path to the actual mount point.
        int err = getattrlist(buf[i].f_mntonname, &alist, &buffer, sizeof(buffer), 0);
        if (err != 0) {
            env->CallVoidMethod(info, unknownFsMethod, mount_point, file_system_type, device_name, remote);
        } else {
            if (alist.volattr & ATTR_VOL_CAPABILITIES) {
                if ((buffer.caps.valid[VOL_CAPABILITIES_FORMAT] & VOL_CAP_FMT_CASE_SENSITIVE)) {
                    caseSensitive = (buffer.caps.capabilities[VOL_CAPABILITIES_FORMAT] & VOL_CAP_FMT_CASE_SENSITIVE) != 0;
                }
                if ((buffer.caps.valid[VOL_CAPABILITIES_FORMAT] & VOL_CAP_FMT_CASE_PRESERVING)) {
                    casePreserving = (buffer.caps.capabilities[VOL_CAPABILITIES_FORMAT] & VOL_CAP_FMT_CASE_PRESERVING) != 0;
                }
            }

            env->CallVoidMethod(info, unknownFsMethod, mount_point, file_system_type, device_name, remote);
        }
    }
    free(buf);
}

/**
 * Memory functions
 */
JNIEXPORT void JNICALL
Java_net_rubygrapefruit_platform_internal_jni_MemoryFunctions_getMemoryInfo(JNIEnv* env, jclass type, jobject dest, jobject result) {
    jclass destClass = env->GetObjectClass(dest);
    jmethodID mid = env->GetMethodID(destClass, "details", "(JJ)V");
    if (mid == NULL) {
        mark_failed_with_message(env, "could not find method", result);
        return;
    }

    // Get total physical memory
    int mib[2];
    mib[0] = CTL_HW;
    mib[1] = HW_MEMSIZE;
    int64_t total_memory = 0;
    size_t len = sizeof(total_memory);
    if (sysctl(mib, 2, &total_memory, &len, NULL, 0) != 0) {
        mark_failed_with_errno(env, "could not query memory size", result);
        return;
    }

    // Get VM stats
    vm_size_t page_size;
    mach_port_t mach_port;
    mach_msg_type_number_t count;
    vm_statistics64_data_t vm_stats;

    mach_port = mach_host_self();
    count = HOST_VM_INFO64_COUNT;
    if (KERN_SUCCESS != host_page_size(mach_port, &page_size)) {
        mark_failed_with_errno(env, "could not query page size", result);
        return;
    }
    if (KERN_SUCCESS != host_statistics64(mach_port, HOST_VM_INFO64, (host_info64_t) &vm_stats, &count)) {
        mark_failed_with_errno(env, "could not query host statistics", result);
        return;
    }

    // Calculate available memory
    long long available_memory = ((int64_t) vm_stats.free_count
                                     + (int64_t) vm_stats.inactive_count
                                     - (int64_t) vm_stats.speculative_count)
        * (int64_t) page_size;
    // Feed Java with details
    env->CallVoidMethod(dest, mid, (jlong) total_memory, (jlong) available_memory);
}

JNIEXPORT void JNICALL
Java_net_rubygrapefruit_platform_internal_jni_OsxMemoryFunctions_getOsxMemoryInfo(JNIEnv* env, jclass type, jobject dest, jobject result) {
    jclass destClass = env->GetObjectClass(dest);
    jmethodID mid = env->GetMethodID(destClass, "details", "(JJJJJJJJJ)V");
    if (mid == NULL) {
        mark_failed_with_message(env, "could not find method", result);
        return;
    }

    // Get total physical memory
    int mib[2];
    mib[0] = CTL_HW;
    mib[1] = HW_MEMSIZE;
    int64_t total_memory = 0;
    size_t len = sizeof(total_memory);
    if (sysctl(mib, 2, &total_memory, &len, NULL, 0) != 0) {
        mark_failed_with_errno(env, "could not query memory size", result);
        return;
    }

    // Get VM stats
    vm_size_t page_size;
    mach_port_t mach_port;
    vm_statistics64_data_t vm_stats;
    mach_msg_type_number_t count;

    mach_port = mach_host_self();
    count = HOST_VM_INFO64_COUNT;
    if (KERN_SUCCESS != host_page_size(mach_port, &page_size)) {
        mark_failed_with_errno(env, "could not query page size", result);
        return;
    }
    if (KERN_SUCCESS != host_statistics64(mach_port, HOST_VM_INFO64, (host_info64_t) &vm_stats, &count)) {
        mark_failed_with_errno(env, "could not query host statistics", result);
        return;
    }

    // Calculate available memory
    long long available_memory = ((int64_t) vm_stats.free_count
                                     + (int64_t) vm_stats.inactive_count
                                     - (int64_t) vm_stats.speculative_count)
        * (int64_t) page_size;

    // Feed Java with details
    env->CallVoidMethod(dest, mid,
        (jlong) page_size,
        (jlong) vm_stats.free_count,
        (jlong) vm_stats.inactive_count,
        (jlong) vm_stats.wire_count,
        (jlong) vm_stats.active_count,
        (jlong) vm_stats.external_page_count,
        (jlong) vm_stats.speculative_count,
        (jlong) total_memory,
        (jlong) available_memory);
}

#endif

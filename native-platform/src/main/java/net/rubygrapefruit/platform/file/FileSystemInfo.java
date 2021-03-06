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

package net.rubygrapefruit.platform.file;

import net.rubygrapefruit.platform.ThreadSafe;

import javax.annotation.Nullable;
import java.io.File;

/**
 * Information about a file system. This is a snapshot view and does not change.
 */
@ThreadSafe
public interface FileSystemInfo {
    /**
     * Returns the root directory of this file system.
     */
    @ThreadSafe
    File getMountPoint();

    /**
     * Returns the operating system specific name for the type of this file system
     * or {@code "unknown"} if the type could not be determined.
     */
    @ThreadSafe
    String getFileSystemType();

    /**
     * Returns true if this file system is a remote file system, or false if local.
     */
    @ThreadSafe
    boolean isRemote();

    /**
     * Returns the operating system specific name for this file system.
     */
    @ThreadSafe
    String getDeviceName();

    /**
     * Returns case sensitivity information for the file system if known or {@code null}.
     */
    @ThreadSafe
    @Nullable
    CaseSensitivity getCaseSensitivity();

    /**
     * Returns true if this file system is performance case sensitive searches.
     *
     * @deprecated Use {@link #getCaseSensitivity()} instead.
     */
    @Deprecated
    @ThreadSafe
    boolean isCaseSensitive();

    /**
     * Returns true if this file system preserves file name case.
     *
     * @deprecated Use {@link #getCaseSensitivity()} instead.
     */
    @Deprecated
    @ThreadSafe
    boolean isCasePreserving();
}

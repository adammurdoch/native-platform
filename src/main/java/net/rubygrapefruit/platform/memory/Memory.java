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

package net.rubygrapefruit.platform.memory;

import net.rubygrapefruit.platform.NativeException;
import net.rubygrapefruit.platform.NativeIntegration;
import net.rubygrapefruit.platform.ThreadSafe;

/**
 * Provides details about the system memory.
 */
@ThreadSafe
public interface Memory extends NativeIntegration {
    /**
     * Queries the current state of the system memory.
     *
     * @return A snapshot of the current state of the system memory.
     * @throws NativeException On failure.
     */
    @ThreadSafe
    MemoryInfo getMemoryInfo() throws NativeException;
}

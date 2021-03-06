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

package net.rubygrapefruit.platform.internal.jni;

import net.rubygrapefruit.platform.internal.CharInputBuffer;
import net.rubygrapefruit.platform.internal.FunctionResult;
import net.rubygrapefruit.platform.internal.MutableTerminalSize;

public class WindowsConsoleFunctions {
    public static final int CONSOLE_NONE = 0;
    public static final int CONSOLE_WINDOWS = 1;
    public static final int CONSOLE_CYGWIN = 2;

    /**
     * @return {@link #CONSOLE_NONE} when not a console, {@link #CONSOLE_WINDOWS} when Windows console, {@link #CONSOLE_CYGWIN} when Cygwin or msys console.
     */
    public static native int isConsole(int filedes, FunctionResult result);

    public static native void getConsoleSize(int filedes, MutableTerminalSize size, FunctionResult result);

    public static native void initConsole(int filedes, FunctionResult result);

    public static native void rawInputMode(FunctionResult result);

    public static native void resetInputMode(FunctionResult result);

    public static native void readInput(CharInputBuffer buffer, FunctionResult result);

    public static native void boldOn(FunctionResult result);

    public static native void boldOff(FunctionResult result);

    public static native void reset(FunctionResult result);

    public static native void showCursor(FunctionResult result);

    public static native void hideCursor(FunctionResult result);

    public static native void foreground(int ansiColor, FunctionResult result);

    public static native void defaultForeground(FunctionResult result);

    public static native void left(int count, FunctionResult result);

    public static native void right(int count, FunctionResult result);

    public static native void up(int count, FunctionResult result);

    public static native void down(int count, FunctionResult result);

    public static native void startLine(FunctionResult result);

    public static native void clearToEndOfLine(FunctionResult result);
}

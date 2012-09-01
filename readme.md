
# Native-platform: Java bindings for various native APIs

A collection of cross-platform Java APIs for various native APIs. Supports OS X, Linux, Solaris and Windows.

These APIs support Java 5 and later. Some of these APIs overlap with APIs available in later Java versions.

## Available bindings

### Generic

* Get PID of current process.
* Get kernel name and version.
* Get machine architecture.

### Terminal and console

These bindings work for both the UNIX terminal and the Windows console:

* Determine if stdout/stderr are attached to a terminal.
* Query the terminal size.
* Switch between bold and normal mode on the terminal.
* Change foreground color on the terminal.
* Move terminal cursor up, down, left, right, start of line.
* Clear to end of line.

### File systems

* Get and set UNIX file mode.
* List the available file systems on the machine
* Query file system mount point.
* Query file system type.
* Query file system device name.
* Query whether a file system is local or remote.

## Supported platforms

Currently ported to OS X, Linux, Solaris and Windows. Tested on:

* OS X 10.7.4, 10.8 (x86_64)
* Ubunutu 12.04 (amd64)
* Solaris 11 (x86)
* Windows 7 (amd64)

## Using

Include `native-platform.jar` and `native-platform-jni.jar` in your classpath.

    import net.rubygrapefruit.platform.Native;
    import net.rubygrapefruit.platform.Terminals;
    import net.rubygrapefruit.platform.Terminal;
    import static net.rubygrapefruit.platform.Terminals.Output.*;

    Terminals terminals = Native.get(Terminals.class);

    // check if terminal
    terminals.isTerminal(Stdout);

    // use terminal
    Terminal stdout = terminals.getTerminal(Stdout);
    stdout.bold();
    System.out.println("bold text");


## Building

You will need a very recent snapshot of [Gradle](http://www.gradle.org/).

### Ubuntu

The g++ compiler is required to build the native library. You will need to `g++` package for this. Generally this is already installed.

You need to install the `gcc-multilib` and `g++-multilib` packages to pick up i386 support.

You need to install the `libncurses5-dev` and `lib32ncurses5-dev` package to pick up the ncurses header files and i386 version. Also worth installing the `ncurses-doc` package too.

### Windows

You need to install Visual studio, and build from a Visual studio command prompt.

### OS X

The g++ compiler is required to build the native library. You will need to install the XCode tools for this.

### Solaris

For Solaris 11, you need to install the `development/gcc-45` and `system/header` packages.

## Running

Run `gradle install` to install into `build/install/native-platform`. Or `gradle distZip` to create an application distribtion
in `build/distributions/native-platform.zip`.

You can run `$INSTALL_DIR/bin/native-platform` to run the test application.

## Testing

* Test on IBM JVM.
* Test on Java 5, 6, 7.
* Test on Windows 7, Windows XP

## TODO

### Fixes

* Windows: build 32 bit and 64 bit libraries.
* Windows: flush System.out or System.err on attribute change.
* Windows: fail for unsupported architecture.
* Linux: fail for unsupported architecture.
* Linux: detect remote filesystems.
* Solaris: fix unicode file name handling.
* Solaris: fail for unsupported architecture.
* Solaris: build 32 bit and 64 bit libraries.
* Freebsd: finish port.
* Freebsd: fail for unsupported architecture.
* Freebsd: build 32 bit and 64 bit libraries.

### Improvements

* Support for cygwin terminal
* Use TERM=xtermc instead of TERM=xterm on Solaris.
* Add diagnostics for terminal.
* Split out separate native library for terminal handling.
* Version each native interface separately.
* String names for errno values.
* Split into multiple projects.
* Convert to c.
* Thread safety.
* Improve error message when unsupported capability is used.
* Initial release.
* Use fully decomposed form for unicode file names on hfs+ filesystems.
* Handle string encoding for file system details.
* Handle string encoding for system info.

### Ideas

* Expose platform-specific HTTP proxy configuration. Query registry on windows to determine IE settings.

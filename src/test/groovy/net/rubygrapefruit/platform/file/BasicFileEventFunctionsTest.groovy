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
package net.rubygrapefruit.platform.file

import net.rubygrapefruit.platform.NativeException
import net.rubygrapefruit.platform.internal.Platform
import net.rubygrapefruit.platform.internal.jni.AbstractFileEventFunctions
import net.rubygrapefruit.platform.internal.jni.NativeLogger
import org.junit.Assume
import spock.lang.Ignore
import spock.lang.IgnoreIf
import spock.lang.Requires
import spock.lang.Unroll
import spock.util.concurrent.AsyncConditions
import spock.util.environment.OperatingSystem

import java.util.concurrent.ArrayBlockingQueue
import java.util.logging.Level
import java.util.logging.Logger
import java.util.regex.Pattern

import static java.util.logging.Level.INFO
import static java.util.logging.Level.SEVERE
import static java.util.logging.Level.WARNING
import static net.rubygrapefruit.platform.file.FileWatcherCallback.Type.CREATED
import static net.rubygrapefruit.platform.file.FileWatcherCallback.Type.INVALIDATE
import static net.rubygrapefruit.platform.file.FileWatcherCallback.Type.MODIFIED
import static net.rubygrapefruit.platform.file.FileWatcherCallback.Type.REMOVED

@Unroll
@Requires({ Platform.current().macOs || Platform.current().linux || Platform.current().windows })
class BasicFileEventFunctionsTest extends AbstractFileEventFunctionsTest {
    def "can start and stop watcher without watching any paths"() {
        when:
        startWatcher()

        then:
        noExceptionThrown()
    }

    def "can open and close watcher on a directory without receiving any events"() {
        when:
        startWatcher(rootDir)

        then:
        noExceptionThrown()
    }

    def "can detect file created"() {
        given:
        def createdFile = new File(rootDir, "created.txt")
        startWatcher(rootDir)

        when:
        createNewFile(createdFile)

        then:
        expectEvents change(CREATED, createdFile)
    }

    @IgnoreIf({ Platform.current().linux })
    def "can detect file created in subdirectory"() {
        given:
        def subDir = new File(rootDir, "sub-dir")
        subDir.mkdirs()
        def createdFile = new File(subDir, "created.txt")
        startWatcher(rootDir)

        when:
        createNewFile(createdFile)

        then:
        expectEvents change(CREATED, createdFile)
    }

    def "can detect directory created"() {
        given:
        def createdDir = new File(rootDir, "created")
        startWatcher(rootDir)

        when:
        assert createdDir.mkdirs()

        then:
        expectEvents change(CREATED, createdDir)
    }

    @IgnoreIf({ Platform.current().linux })
    def "can detect directory created in subdirectory"() {
        given:
        def subDir = new File(rootDir, "sub-dir")
        subDir.mkdirs()
        def createdDir = new File(subDir, "created")
        startWatcher(rootDir)

        when:
        assert createdDir.mkdirs()

        then:
        expectEvents change(CREATED, createdDir)
    }

    def "can detect file removed"() {
        given:
        def removedFile = new File(rootDir, "removed.txt")
        createNewFile(removedFile)
        startWatcher(rootDir)

        when:
        removedFile.delete()

        then:
        // Windows reports the file as modified before removing it
        expectEvents Platform.current().windows
            ? [change(MODIFIED, removedFile), change(REMOVED, removedFile)]
            : [change(REMOVED, removedFile)]
    }

    @IgnoreIf({ Platform.current().linux })
    def "can detect file removed in subdirectory"() {
        given:
        def subDir = new File(rootDir, "sub-dir")
        subDir.mkdirs()
        def removedFile = new File(subDir, "removed.txt")
        createNewFile(removedFile)
        startWatcher(rootDir)

        when:
        removedFile.delete()

        then:
        // Windows reports the file as modified before removing it
        expectEvents Platform.current().windows
            ? [change(MODIFIED, removedFile), change(REMOVED, removedFile)]
            : [change(REMOVED, removedFile)]
    }

    def "can detect directory removed"() {
        given:
        def removedDir = new File(rootDir, "removed")
        assert removedDir.mkdirs()
        startWatcher(rootDir)

        when:
        removedDir.deleteDir()

        then:
        expectEvents change(REMOVED, removedDir)
    }

    @IgnoreIf({ Platform.current().linux })
    def "can detect directory removed in subdirectory"() {
        given:
        def subDir = new File(rootDir, "sub-dir")
        subDir.mkdirs()
        def removedDir = new File(subDir, "removed")
        assert removedDir.mkdirs()
        startWatcher(rootDir)

        when:
        removedDir.deleteDir()

        then:
        expectEvents change(REMOVED, removedDir)
    }

    def "can detect file modified"() {
        given:
        def modifiedFile = new File(rootDir, "modified.txt")
        createNewFile(modifiedFile)
        startWatcher(rootDir)

        when:
        modifiedFile << "change"

        then:
        expectEvents change(MODIFIED, modifiedFile)
    }

    @IgnoreIf({ Platform.current().linux })
    def "can detect file modified in subdirectory"() {
        given:
        def subDir = new File(rootDir, "sub-dir")
        subDir.mkdirs()
        def modifiedFile = new File(subDir, "modified.txt")
        createNewFile(modifiedFile)
        startWatcher(rootDir)

        when:
        modifiedFile << "change"

        then:
        expectEvents change(MODIFIED, modifiedFile)
    }

    @Requires({ Platform.current().macOs })
    def "can detect file metadata modified"() {
        given:
        def modifiedFile = new File(rootDir, "modified.txt")
        createNewFile(modifiedFile)
        startWatcher(rootDir)

        when:
        modifiedFile.setReadable(false)

        then:
        expectEvents change(MODIFIED, modifiedFile)

        when:
        modifiedFile.setReadable(true)

        then:
        expectEvents change(MODIFIED, modifiedFile)
    }

    @Requires({ Platform.current().macOs })
    def "changing metadata immediately after creation is reported as modified"() {
        given:
        def createdFile = new File(rootDir, "file.txt")
        startWatcher(rootDir)

        when:
        createNewFile(createdFile)
        createdFile.setReadable(false)

        then:
        expectEvents change(MODIFIED, createdFile)
    }

    @Requires({ Platform.current().macOs })
    def "changing metadata doesn't mask content change"() {
        given:
        def modifiedFile = new File(rootDir, "modified.txt")
        modifiedFile.createNewFile()
        startWatcher(rootDir)

        when:
        modifiedFile.setReadable(false)
        modifiedFile << "change"

        then:
        expectEvents change(MODIFIED, modifiedFile)
    }

    @Requires({ Platform.current().macOs })
    def "changing metadata doesn't mask removal"() {
        given:
        def removedFile = new File(rootDir, "removed.txt")
        createNewFile(removedFile)
        startWatcher(rootDir)

        when:
        removedFile.setReadable(false)
        assert removedFile.delete()

        then:
        expectEvents change(REMOVED, removedFile)
    }

    def "can detect file renamed"() {
        given:
        def sourceFile = new File(rootDir, "source.txt")
        def targetFile = new File(rootDir, "target.txt")
        createNewFile(sourceFile)
        startWatcher(rootDir)

        when:
        sourceFile.renameTo(targetFile)

        then:
        expectEvents Platform.current().windows
            ? [change(REMOVED, sourceFile), change(CREATED, targetFile), optionalChange(MODIFIED, targetFile)]
            : [change(REMOVED, sourceFile), change(CREATED, targetFile)]
    }

    @IgnoreIf({ Platform.current().linux })
    def "can detect file renamed in subdirectory"() {
        given:
        def subDir = new File(rootDir, "sub-dir")
        subDir.mkdirs()
        def sourceFile = new File(subDir, "source.txt")
        def targetFile = new File(subDir, "target.txt")
        createNewFile(sourceFile)
        startWatcher(rootDir)

        when:
        sourceFile.renameTo(targetFile)

        then:
        expectEvents Platform.current().windows
            ? [change(REMOVED, sourceFile), change(CREATED, targetFile), change(MODIFIED, subDir), optionalChange(MODIFIED, targetFile)]
            : [change(REMOVED, sourceFile), change(CREATED, targetFile)]
    }

    def "can detect file moved out"() {
        given:
        def outsideDir = new File(testDir, "outside")
        assert outsideDir.mkdirs()
        def sourceFileInside = new File(rootDir, "source-inside.txt")
        def targetFileOutside = new File(outsideDir, "target-outside.txt")
        createNewFile(sourceFileInside)
        startWatcher(rootDir)

        when:
        sourceFileInside.renameTo(targetFileOutside)

        then:
        expectEvents change(REMOVED, sourceFileInside)
    }

    def "can detect file moved in"() {
        given:
        def outsideDir = new File(testDir, "outside")
        assert outsideDir.mkdirs()
        def sourceFileOutside = new File(outsideDir, "source-outside.txt")
        def targetFileInside = new File(rootDir, "target-inside.txt")
        createNewFile(sourceFileOutside)
        startWatcher(rootDir)

        when:
        // On Windows we sometimes get a MODIFIED event after CREATED for some reason
        sourceFileOutside.renameTo(targetFileInside)

        then:
        expectEvents Platform.current().windows
            ? [change(CREATED, targetFileInside), optionalChange(MODIFIED, targetFileInside)]
            : [change(CREATED, targetFileInside)]
    }

    def "can receive multiple events from the same directory"() {
        given:
        def firstFile = new File(rootDir, "first.txt")
        def secondFile = new File(rootDir, "second.txt")
        startWatcher(rootDir)

        when:
        createNewFile(firstFile)

        then:
        expectEvents change(CREATED, firstFile)

        when:
        waitForChangeEventLatency()
        createNewFile(secondFile)

        then:
        expectEvents change(CREATED, secondFile)
    }

    def "does not receive events from unwatched directory"() {
        given:
        def watchedFile = new File(rootDir, "watched.txt")
        def unwatchedDir = new File(testDir, "unwatched")
        assert unwatchedDir.mkdirs()
        def unwatchedFile = new File(unwatchedDir, "unwatched.txt")
        startWatcher(rootDir)

        when:
        createNewFile(unwatchedFile)
        createNewFile(watchedFile)
        // Let's make sure there are no events for the unwatched file,
        // and we don't just miss them because of timing
        waitForChangeEventLatency()

        then:
        expectEvents change(CREATED, watchedFile)
    }

    // Apparently on macOS we can watch non-existent directories
    // TODO Should we fail for this?
    @IgnoreIf({ Platform.current().macOs })
    def "fails when watching non-existent directory"() {
        given:
        def missingDirectory = new File(rootDir, "missing")

        when:
        startWatcher(missingDirectory)

        then:
        def ex = thrown NativeException
        ex.message ==~ /Couldn't add watch.*: ${Pattern.quote(missingDirectory.absolutePath)}/

        expectLogMessage(SEVERE, Pattern.compile("Caught exception: Couldn't add watch.*: ${Pattern.quote(missingDirectory.absolutePath)}"))
    }

    // Apparently on macOS we can watch files
    // TODO Should we fail for this?
    @IgnoreIf({ Platform.current().macOs })
    def "fails when watching file"() {
        given:
        def file = new File(rootDir, "file.txt")

        when:
        startWatcher(file)

        then:
        def ex = thrown NativeException
        ex.message ==~ /Couldn't add watch.*: ${Pattern.quote(file.absolutePath)}/

        expectLogMessage(SEVERE, Pattern.compile("Caught exception: Couldn't add watch.*: ${Pattern.quote(file.absolutePath)}"))
    }

    def "fails when watching directory twice"() {
        given:
        startWatcher(rootDir)

        when:
        watcher.startWatching(rootDir)

        then:
        def ex = thrown NativeException
        ex.message == "Already watching path: ${rootDir.absolutePath}"

        expectLogMessage(SEVERE, "Caught exception: Already watching path: ${rootDir.absolutePath}")
    }

    def "can un-watch path that was not watched"() {
        given:
        startWatcher()

        expect:
        !watcher.stopWatching(rootDir)

        expectLogMessage(INFO, "Path is not watched: ${rootDir.absolutePath}")
    }

    def "can un-watch watched directory twice"() {
        given:
        startWatcher(rootDir)
        watcher.stopWatching(rootDir)

        expect:
        !watcher.stopWatching(rootDir)

        expectLogMessage(INFO, "Path is not watched: ${rootDir.absolutePath}")
    }

    def "does not receive events after directory is unwatched"() {
        given:
        def file = new File(rootDir, "first.txt")
        startWatcher(rootDir)

        expect:
        watcher.stopWatching(rootDir)

        when:
        createNewFile(file)

        then:
        expectNoEvents()
    }

    def "can receive multiple events from multiple watched directories"() {
        given:
        def firstWatchedDir = new File(testDir, "first")
        assert firstWatchedDir.mkdirs()
        def firstFileInFirstWatchedDir = new File(firstWatchedDir, "first-watched.txt")
        def secondWatchedDir = new File(testDir, "second")
        assert secondWatchedDir.mkdirs()
        def secondFileInSecondWatchedDir = new File(secondWatchedDir, "sibling-watched.txt")
        startWatcher(firstWatchedDir, secondWatchedDir)

        when:
        createNewFile(firstFileInFirstWatchedDir)

        then:
        expectEvents change(CREATED, firstFileInFirstWatchedDir)

        when:
        createNewFile(secondFileInSecondWatchedDir)

        then:
        expectEvents change(CREATED, secondFileInSecondWatchedDir)
    }

    @Requires({ !Platform.current().linux })
    def "can receive events from directory with different casing"() {
        given:
        def lowercaseDir = new File(rootDir, "watch-this")
        def uppercaseDir = new File(rootDir, "WATCH-THIS")
        def fileInLowercaseDir = new File(lowercaseDir, "lowercase.txt")
        def fileInUppercaseDir = new File(uppercaseDir, "UPPERCASE.TXT")
        uppercaseDir.mkdirs()

        def reportedDir = Platform.current().macOs
            ? uppercaseDir
            : lowercaseDir

        startWatcher(lowercaseDir)

        when:
        createNewFile(fileInLowercaseDir)

        then:
        expectEvents change(CREATED, new File(reportedDir, fileInLowercaseDir.name))

        when:
        createNewFile(fileInUppercaseDir)

        then:
        expectEvents change(CREATED, new File(reportedDir, fileInUppercaseDir.name))
    }

    def "can handle exception in callback"() {
        given:
        def createdFile = new File(rootDir, "created.txt")
        def conditions = new AsyncConditions()
        when:
        def watcher = startNewWatcher(new FileWatcherCallback() {
            @Override
            void pathChanged(FileWatcherCallback.Type type, String path) {
                throw new RuntimeException("Error")
            }

            @Override
            void reportError(Throwable ex) {
                conditions.evaluate {
                    assert ex instanceof NativeException
                    assert ex.message == "Caught java.lang.RuntimeException with message: Error"
                }
            }
        }, rootDir)
        createNewFile(createdFile)

        then:
        conditions.await()

        cleanup:
        watcher.close()
    }

    def "fails when stopped multiple times"() {
        given:
        def watcher = startNewWatcher()
        watcher.close()

        when:
        watcher.close()

        then:
        def ex = thrown IllegalStateException
        ex.message == "Watcher already closed"
    }

    def "can be used multiple times"() {
        given:
        def firstFile = new File(rootDir, "first.txt")
        def secondFile = new File(rootDir, "second.txt")
        startWatcher(rootDir)

        when:
        createNewFile(firstFile)

        then:
        expectEvents change(CREATED, firstFile)
        stopWatcher()

        when:
        startWatcher(rootDir)
        createNewFile(secondFile)

        then:
        expectEvents change(CREATED, secondFile)
    }

    def "can start multiple watchers"() {
        given:
        def firstRoot = new File(rootDir, "first")
        firstRoot.mkdirs()
        def secondRoot = new File(rootDir, "second")
        secondRoot.mkdirs()
        def firstFile = new File(firstRoot, "file.txt")
        def secondFile = new File(secondRoot, "file.txt")
        def firstQueue = newEventQueue()
        def secondQueue = newEventQueue()

        LOGGER.info("> Starting first watcher")
        def firstWatcher = startNewWatcher(firstQueue)
        firstWatcher.startWatching(firstRoot)
        LOGGER.info("> Starting second watcher")
        def secondWatcher = startNewWatcher(secondQueue)
        secondWatcher.startWatching(secondRoot)
        LOGGER.info("> Watchers started")

        when:
        createNewFile(firstFile)

        then:
        expectEvents firstQueue, change(CREATED, firstFile)

        when:
        createNewFile(secondFile)

        then:
        expectEvents secondQueue, change(CREATED, secondFile)

        cleanup:
        firstWatcher.close()
        secondWatcher.close()
    }

    @Requires({ !Platform.current().linux })
    def "can receive event about a non-direct descendant change"() {
        given:
        def subDir = new File(rootDir, "sub-dir")
        subDir.mkdirs()
        def fileInSubDir = new File(subDir, "watched-descendant.txt")
        startWatcher(rootDir)

        when:
        createNewFile(fileInSubDir)

        then:
        expectEvents change(CREATED, fileInSubDir)
    }

    @Requires({ Platform.current().linux })
    def "does not receive event about a non-direct descendant change"() {
        given:
        def subDir = new File(rootDir, "sub-dir")
        subDir.mkdirs()
        def fileInSubDir = new File(subDir, "unwatched-descendant.txt")

        when:
        createNewFile(fileInSubDir)

        then:
        expectNoEvents()
    }

    def "can watch directory with long path"() {
        given:
        def subDir = new File(rootDir, "long-path")
        4.times {
            subDir = new File(subDir, "X" * 200)
        }
        subDir.mkdirs()
        def fileInSubDir = new File(subDir, "watched-descendant.txt")
        startWatcher(subDir)

        when:
        createNewFile(fileInSubDir)

        then:
        expectEvents change(CREATED, fileInSubDir)
    }

    def "can watch directory with #type characters"() {
        Assume.assumeTrue(supported as boolean)

        given:
        def subDir = new File(rootDir, path)
        subDir.mkdirs()
        def fileInSubDir = new File(subDir, path)
        startWatcher(subDir)

        when:
        createNewFile(fileInSubDir)

        then:
        expectEvents change(CREATED, fileInSubDir)

        where:
        type             | path                     | supported
        "ASCII-only"     | "directory"              | true
        "Chinese"        | "输入文件"                   | true
        "four-byte UTF8" | "𠜎𠜱𠝹𠱓"               | true
        "Hungarian"      | "Dezső"                  | true
        "space"          | "test directory"         | true
        "zwnj"           | "test\u200cdirectory"    | true
        "newline"        | "test\ndirectory"        | Platform.current().macOs
        "URL-quoted"     | "test%<directory>#2.txt" | !Platform.current().windows
    }

    def "can detect #ancestry removed"() {
        given:
        def parentDir = new File(rootDir, "parent")
        def watchedDir = new File(parentDir, "removed")
        watchedDir.mkdirs()
        def removedFile = new File(watchedDir, "file.txt")
        createNewFile(removedFile)
        File removedDir = removedDirectory(watchedDir)
        startWatcher(watchedDir)

        when:
        def directoryRemoved = removedDir.deleteDir()

        def expectedEvents = []
        if (Platform.current().macOs) {
            expectedEvents << change(INVALIDATE, watchedDir)
            if (ancestry == "watched directory") {
                expectedEvents << change(REMOVED, watchedDir)
            }
        } else if (Platform.current().linux) {
            expectedEvents << change(REMOVED, removedFile) << change(REMOVED, watchedDir)
        } else if (Platform.current().windows) {
            expectedEvents << change(MODIFIED, removedFile) << optionalChange(REMOVED, removedFile) << change(REMOVED, watchedDir)
        }

        then:
        expectEvents expectedEvents
        directoryRemoved == expectDirectoryRemoved

        where:
        ancestry                            | removedDirectory             | expectDirectoryRemoved
        "watched directory"                 | { it }                       | true
        "parent of watched directory"       | { it.parentFile }            | !OperatingSystem.current.windows
        "grand-parent of watched directory" | { it.parentFile.parentFile } | !OperatingSystem.current.windows
    }

    def "can set log level by #action"() {
        given:
        def nativeLogger = Logger.getLogger(NativeLogger.name)
        def originalLevel = nativeLogger.level
        def fileChanged = new File(rootDir, "changed.txt")
        fileChanged.createNewFile()

        when:
        logging.clear()
        nativeLogger.level = Level.FINEST
        ensureLogLevelInvalidated(service)
        startWatcher(rootDir)
        fileChanged << "changed"
        waitForChangeEventLatency()

        then:
        logging.messages.values().any { it == Level.FINE }

        when:
        stopWatcher()
        logging.clear()
        nativeLogger.level = WARNING
        ensureLogLevelInvalidated(service)
        startWatcher()
        fileChanged << "changed again"
        waitForChangeEventLatency()

        then:
        !logging.messages.values().any { it == Level.FINE }

        cleanup:
        nativeLogger.level = originalLevel

        where:
        action                                    | ensureLogLevelInvalidated
        "invalidating the log level cache"        | { AbstractFileEventFunctions service -> service.invalidateLogLevelCache() }
        "waiting for log level cache to time out" | { Thread.sleep(1500) }
    }

    def "jvm does not crash when event queue is blocked"() {
        given:
        def watchedDir = new File(rootDir, "watched")
        def secondWatchedDir = new File(rootDir, "secondWatched")
        watchedDir.mkdirs()
        def blockedQueue = new ArrayBlockingQueue(1)
        def watcher = startNewWatcher(blockedQueue, watchedDir)

        when:
        new File(watchedDir, "first").text = "first"
        new File(watchedDir, "second").text = "second"
        new File(watchedDir, "third").text = "third"
        waitForChangeEventLatency()

        then:
        blockedQueue.remainingCapacity() == 0

        when:
        watcher.startWatching(secondWatchedDir)

        then:
        noExceptionThrown()

        when:
        watcher.close()

        then:
        def exception = thrown(NativeException)
        exception.message == "Termination timed out"

        expectLogMessage(SEVERE, "Caught exception: Termination timed out")
    }

    def "can throw exception with no message from callback"() {
        def watchedDir = new File(rootDir, "watched")
        watchedDir.mkdirs()
        def error

        when:
        watcher = startNewWatcher(new FileWatcherCallback() {
            @Override
            void pathChanged(FileWatcherCallback.Type type, String path) {
                throw new InterruptedException()
            }

            @Override
            void reportError(Throwable ex) {
                error = ex
            }
        }, watchedDir)
        new File(watchedDir, "new").createNewFile()
        waitForChangeEventLatency()

        then:
        noExceptionThrown()
        error.getClass() == NativeException
        error.message == "Caught ${InterruptedException.name}"
    }
}

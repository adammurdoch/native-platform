package net.rubygrapefruit.platform.file

import net.rubygrapefruit.platform.Native
import net.rubygrapefruit.platform.NativeException
import net.rubygrapefruit.platform.internal.Platform
import spock.lang.IgnoreIf
import spock.lang.Unroll

import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission

import static java.nio.file.attribute.PosixFilePermission.*

@IgnoreIf({ Platform.current().windows })
class PosixFilesTest extends FilesTest {
    PosixFiles files = Native.get(PosixFiles.class)

    @Override
    void assertIsFile(FileInfo stat, File file) {
        assert stat instanceof PosixFileInfo
        super.assertIsFile(stat, file)
        stat.mode == mode(attributes(file))
        assert stat.uid != 0
        assert stat.gid >= 0
        assert stat.blockSize
    }

    @Override
    void assertIsDirectory(FileInfo stat, File file) {
        assert stat instanceof PosixFileInfo
        super.assertIsDirectory(stat, file)
        stat.mode == mode(attributes(file))
        assert stat.uid != 0
        assert stat.gid >= 0
        assert stat.blockSize
    }

    @Override
    void assertIsSymlink(FileInfo stat, File file) {
        assert stat instanceof PosixFileInfo
        super.assertIsSymlink(stat, file)
        stat.mode == mode(attributes(file))
        assert stat.uid != 0
        assert stat.gid >= 0
        assert stat.blockSize
    }

    @Override
    void assertIsMissing(FileInfo stat) {
        assert stat instanceof PosixFileInfo
        super.assertIsMissing(stat)
        assert stat.uid == 0
        assert stat.gid == 0
        assert stat.blockSize == 0
    }

    def "uses same instance for specialized file types"() {
        expect:
        Native.get(PosixFiles.class) == files
        Native.get(Files.class) == files
    }

    def "can stat a file with no read permissions"() {
        def testFile = tmpDir.newFile("test.file")
        chmod(testFile, [OWNER_WRITE])

        when:
        def stat = files.stat(testFile)

        then:
        stat.type == FileInfo.Type.File

        cleanup:
        chmod(testFile, [OWNER_READ, OWNER_WRITE])
    }

    def "cannot stat a file with no execute permission on parent"() {
        def testDir = tmpDir.newFolder("test-dir")
        def testFile = new File(testDir, "test.file")
        chmod(testDir, permissions)

        when:
        files.stat(testFile)

        then:
        def e = thrown(FilePermissionException)
        e.message == "Could not get file details of $testFile: permission denied"

        cleanup:
        chmod(testDir, [OWNER_READ, OWNER_WRITE])

        where:
        permissions   | _
        []            | _
        [OWNER_WRITE] | _
        [OWNER_READ]  | _
    }

    def "can stat a symlink with no read permissions on symlink"() {
        def testDir = tmpDir.newFolder("test-dir")
        new File(testDir, "test.file").createNewFile()
        def linkFile = new File(testDir, "link")
        files.symlink(linkFile, "test.file")
        chmod(linkFile, [])

        when:
        def stat = files.stat(linkFile, true)

        then:
        stat.type == FileInfo.Type.File

        when:
        stat = files.stat(linkFile, false)

        then:
        stat.type == FileInfo.Type.Symlink

        cleanup:
        chmod(linkFile, [OWNER_READ, OWNER_WRITE])
    }

    def "cannot stat a symlink with no read permissions on parent of target"() {
        def dir = tmpDir.newFolder()
        def testDir = new File(dir, "test-dir")
        testDir.mkdirs()
        def testFile = new File(testDir, "test.file")
        testFile.createNewFile()
        chmod(testDir, [OWNER_WRITE])

        def linkFile = new File(dir, "link")
        linkFile.delete()
        files.symlink(linkFile, "test-dir/test.file")

        when:
        files.stat(linkFile, true)

        then:
        def e = thrown(FilePermissionException)
        e.message == "Could not get file details of $linkFile: permission denied"

        cleanup:
        chmod(testDir, [OWNER_READ, OWNER_WRITE])
    }

    @Unroll
    def "stat follows symlinks to parent directory"() {
        def parentDir = tmpDir.newFolder()
        def testFile = new File(parentDir, fileName)
        testFile.parentFile.mkdirs()
        testFile.text = "content"
        def link = new File(tmpDir.newFolder(), "link")

        given:
        files.symlink(link, parentDir.absolutePath)

        when:
        def stat = files.stat(new File(link, fileName))

        then:
        assertIsFile(stat, testFile)

        where:
        fileName << names
    }

    def "directory listing follows symlinks to dir"() {
        def dir = tmpDir.newFolder()
        new File(dir, "a").text = 'content'
        new File(dir, "b").text = 'content'
        def testDir = tmpDir.newFolder()
        def link1 = new File(testDir, "some-dir")
        def link2 = new File(testDir, "link2")
        files.symlink(link1, "link2")
        files.symlink(link2, dir.absolutePath)

        expect:
        def list = files.listDir(link1)
        list.size() == 2
        list*.name.sort() == ["a", "b"]
    }

    def "cannot list directory without read and execute permissions"() {
        def dir = tmpDir.newFolder()
        new File(dir, "a").text = 'content'
        new File(dir, "b").text = 'content'
        chmod(dir, permissions)

        when:
        files.listDir(dir)

        then:
        def e = thrown(FilePermissionException)
        e.message == "Could not list directory $dir: permission denied"

        cleanup:
        chmod(dir, [OWNER_READ, OWNER_WRITE, OWNER_EXECUTE])

        where:
        permissions     | _
        []              | _
        [OWNER_WRITE]   | _
        [OWNER_EXECUTE] | _
        [OWNER_READ]    | _
    }

    @Unroll
    def "can set mode on a file"() {
        def testFile = tmpDir.newFile(fileName)

        when:
        files.setMode(testFile, fileMode)

        then:
        mode(attributes(testFile)) == fileMode
        files.getMode(testFile) == fileMode
        files.stat(testFile).mode == fileMode

        where:
        fileName << maybeWithUnicde(["test.txt", "test\u03b1\u2295.txt", "test2.txt"])
        fileMode << [0777, 0740, 0644]
    }

    @Unroll
    def "can set mode on a directory"() {
        def testFile = tmpDir.newFolder(fileName)

        when:
        files.setMode(testFile, fileMode)

        then:
        mode(attributes(testFile)) == fileMode
        files.getMode(testFile) == fileMode
        files.stat(testFile).mode == fileMode

        where:
        fileName << maybeWithUnicde(["test-dir", "test\u03b1\u2295-dir", "test2.txt"])
        fileMode << [0777, 0740, 0644]
    }

    def "cannot set mode on file that does not exist"() {
        def testFile = new File(tmpDir.root, "unknown")

        when:
        files.setMode(testFile, 0660)

        then:
        NativeException e = thrown()
        e.message == "Could not set UNIX mode on $testFile: could not chmod file (errno 2: No such file or directory)"
    }

    def "cannot get mode on file that does not exist"() {
        def testFile = new File(tmpDir.root, "unknown")

        when:
        files.getMode(testFile)

        then:
        NativeException e = thrown()
        e.message == "Could not get UNIX mode on $testFile: file does not exist."
    }

    @Unroll
    def "can create symbolic link"() {
        def testFile = new File(tmpDir.root, name)
        testFile.parentFile.mkdirs()
        testFile.text = "hi"
        def symlinkFile = new File(tmpDir.root, name + ".link")

        when:
        files.symlink(symlinkFile, testFile.name)

        then:
        symlinkFile.file
        symlinkFile.text == "hi"
        symlinkFile.canonicalFile == testFile.canonicalFile

        where:
        name << names
    }

    @Unroll
    def "can read symbolic link"() {
        def symlinkFile = new File(tmpDir.root, name)
        symlinkFile.parentFile.mkdirs()

        when:
        files.symlink(symlinkFile, name)

        then:
        files.readLink(symlinkFile) == name

        where:
        name << names
    }

    def "cannot read a symlink that does not exist"() {
        def symlinkFile = new File(tmpDir.root, "symlink")

        when:
        files.readLink(symlinkFile)

        then:
        NativeException e = thrown()
        e.message == "Could not read symlink $symlinkFile: could not lstat file (errno 2: No such file or directory)"
    }

    def "cannot read a symlink that is not a symlink"() {
        def symlinkFile = tmpDir.newFile("not-a-symlink.txt")

        when:
        files.readLink(symlinkFile)

        then:
        NativeException e = thrown()
        e.message == "Could not read symlink $symlinkFile: could not readlink (errno 22: Invalid argument)"
    }

    @Override
    PosixFileAttributes attributes(File file) {
        return java.nio.file.Files.getFileAttributeView(file.toPath(), PosixFileAttributeView, LinkOption.NOFOLLOW_LINKS).readAttributes()
    }

    void chmod(File file, Collection<PosixFilePermission> perms) {
        PosixFileAttributeView fileAttributeView = java.nio.file.Files.getFileAttributeView(file.toPath(), PosixFileAttributeView)
        fileAttributeView.setPermissions(perms as Set)
    }
}

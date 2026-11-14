package ca.yorku.eecs3311.huntassist.adapter.persistence;

import ca.yorku.eecs3311.huntassist.application.AppException;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;

public final class ProjectStoreLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    private final Path directory;

    private ProjectStoreLock(FileChannel channel, FileLock lock, Path directory) {
        this.channel = channel;
        this.lock = lock;
        this.directory = directory;
    }

    public static ProjectStoreLock acquire(Path requested) {
        try {
            Path directory = prepareDirectory(requested);
            validateDatabaseLocation(directory);
            Path lockPath = directory.resolve(".store.lock");
            FileChannel channel = FileChannel.open(lockPath, CREATE, WRITE, NOFOLLOW_LINKS);
            try {
                FileLock lock = channel.tryLock();
                if (lock == null) {
                    throw busy(directory);
                }
                return new ProjectStoreLock(channel, lock, directory);
            } catch (OverlappingFileLockException e) {
                channel.close();
                throw busy(directory);
            } catch (RuntimeException | IOException e) {
                channel.close();
                throw e;
            }
        } catch (IOException | SecurityException e) {
            throw AppException.unavailable("The local project store could not be opened.");
        }
    }

    public Path directory() { return directory; }

    private static Path prepareDirectory(Path requested) throws IOException {
        Path absolute = requested.toAbsolutePath().normalize();
        if (Files.exists(absolute, NOFOLLOW_LINKS) && !Files.isDirectory(absolute, NOFOLLOW_LINKS)) {
            throw AppException.invalid("Choose a project-store directory, without a symbolic link.");
        }
        try {
            Files.createDirectories(absolute, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException e) {
            Files.createDirectories(absolute);
        }
        Path resolved = absolute.toRealPath();
        if (resolved.toString().contains("${") || resolved.toString().codePoints().anyMatch(c ->
                c == ';' || (c == '\\' && java.io.File.separatorChar != '\\') || Character.isISOControl(c))) {
            throw AppException.invalid("Choose a store path without configuration delimiters or control characters.");
        }
        return resolved;
    }

    private static void validateDatabaseLocation(Path directory) {
        for (String filename : new String[] {"huntassist.mv.db", "huntassist.trace.db", "huntassist.lock.db"}) {
            Path file = directory.resolve(filename);
            if (Files.exists(file, NOFOLLOW_LINKS) && !Files.isRegularFile(file, NOFOLLOW_LINKS)) {
                throw AppException.refused("The project database must be a regular local file.");
            }
        }
    }

    private static AppException busy(Path directory) {
        return AppException.conflict("The project store is in use: " + directory
                + ". Close the HuntAssist server or other command using this store and retry.");
    }

    @Override
    public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}

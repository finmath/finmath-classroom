package org.finmath.classroom.io;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

/** Bounded input and private atomic output for administrator-owned directories. */
public final class SafeFiles {
    private SafeFiles() { }

    public static byte[] read(Path path, int limit) throws IOException {
        if (limit < 1 || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Expected a regular, non-symlink file: " + path);
        }
        if (Files.size(path) > limit) {
            throw new IllegalArgumentException("Input exceeds size limit: " + path);
        }
        try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] content = stream.readNBytes(limit + 1);
            if (content.length > limit) {
                throw new IllegalArgumentException("Input exceeds size limit: " + path);
            }
            return content;
        }
    }

    /** Resolve the existing parent even when the output file does not yet exist. */
    public static Path validateOutput(Path path, boolean overwrite) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (absolute.getFileName() == null) {
            throw new IllegalArgumentException("Output must be a file, not a filesystem root");
        }
        Path parent = absolute.getParent().toRealPath();
        if (!Files.isDirectory(parent)) {
            throw new IllegalArgumentException("Output parent directory does not exist: " + parent);
        }
        Path target = parent.resolve(absolute.getFileName());
        if (Files.isSymbolicLink(target)
                || (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    && (!overwrite || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)))) {
            throw new IllegalArgumentException("Refusing to replace output: " + target);
        }
        return target;
    }

    /** Parent directory must exist; no non-atomic fallback is used for sensitive results. */
    public static void writeAtomic(Path path, byte[] content, boolean overwrite) throws IOException {
        Path target = validateOutput(path, overwrite);
        Path parent = target.getParent();
        Path temporary;
        if (Files.getFileStore(parent).supportsFileAttributeView("posix")) {
            temporary = Files.createTempFile(parent, ".classroom-", ".tmp",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } else {
            temporary = Files.createTempFile(parent, ".classroom-", ".tmp");
        }
        try {
            Files.write(temporary, content);
            // Atomic replacement is for private administrator-controlled directories.
            // Recheck to avoid overwriting an output created during validation.
            if (Files.isSymbolicLink(target) || (!overwrite && Files.exists(target))) {
                throw new IllegalArgumentException("Output changed while writing: " + target);
            }
            if (overwrite) {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("Atomic writes are required for classroom output", exception);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}

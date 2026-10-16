package limitium.art.camunda.backend.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Reads and writes rule files with optimistic concurrency.
 *
 * <p>Two people can be in the same working space. A save built on a version someone else has since
 * replaced is refused, and there is deliberately no way to force it: an overwrite path is a path that
 * discards a colleague's work, and offering one guarantees it is eventually taken. The way past a
 * stale save is the three-way merge.
 */
@Service
public class VersionedFiles {

    /** One lock per file: the precondition check and the write must not interleave with another save. */
    private final ConcurrentHashMap<Path, ReentrantLock> locks = new ConcurrentHashMap<>();

    /** The outcome of a conditional write, ready for a controller to turn into a response. */
    public static final class WriteResult {
        public final HttpStatus status;
        public final String etag;
        public final Map<String, Object> body;

        WriteResult(HttpStatus status, String etag, Map<String, Object> body) {
            this.status = status;
            this.etag = etag;
            this.body = body;
        }

        public boolean ok() {
            return status.is2xxSuccessful();
        }
    }

    /** A strong ETag over the exact bytes. Identical content always gets the identical tag. */
    public static String etag(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder sb = new StringBuilder("\"");
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.append('"').toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public String etagOf(Path file) throws IOException {
        return Files.exists(file) ? etag(Files.readAllBytes(file)) : null;
    }

    /**
     * Writes {@code content} if the caller's {@code If-Match} names the version currently on disk.
     *
     * @param strict when true an existing file cannot be written without a precondition. Wildcard
     *               {@code *} does not count: it asserts nothing about what the caller has seen, so
     *               accepting it would make "cannot be forced" a matter of which header you send.
     */
    public WriteResult write(Path file, byte[] content, String ifMatch, boolean strict) throws IOException {
        ReentrantLock lock = lockOf(file);
        lock.lock();
        try {
            String presented = normalise(ifMatch);
            if (!Files.exists(file) && presented != null) {
                // The caller loaded a version that has since been removed: writing would bring back a
                // file a colleague deleted.
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("code", "REMOVED");
                body.put("error", "this file was removed after you loaded it; nothing was written");
                return new WriteResult(HttpStatus.PRECONDITION_FAILED, null, body);
            }
            if (Files.exists(file)) {
                String current = etag(Files.readAllBytes(file));
                if (presented == null) {
                    if (strict) {
                        return refused(HttpStatus.PRECONDITION_REQUIRED, "PRECONDITION_REQUIRED",
                                "send If-Match with the ETag of the version you loaded", current, file);
                    }
                } else if (!presented.equals(current)) {
                    return refused(HttpStatus.PRECONDITION_FAILED, "STALE",
                            "this file changed after you loaded it; nothing was overwritten", current, file);
                }
            }

            writeAtomically(file, content);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", file.getFileName().toString());
            body.put("size", (long) content.length);
            body.put("modified", Files.getLastModifiedTime(file).toInstant().toString());
            return new WriteResult(HttpStatus.OK, etag(content), body);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Creates a file that must not exist yet, atomically: of two creators racing, exactly one wins and
     * the other is told the file exists. Returns false when it already existed.
     */
    public boolean create(Path file, byte[] content) throws IOException {
        ReentrantLock lock = lockOf(file);
        lock.lock();
        try {
            if (Files.exists(file)) {
                return false;
            }
            writeAtomically(file, content);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Deletes a file if the caller's {@code If-Match} names the version on disk — the same rule as a
     * save, because removing a version you have not seen discards a colleague's work just as surely.
     */
    public WriteResult delete(Path file, String ifMatch, boolean strict) throws IOException {
        ReentrantLock lock = lockOf(file);
        lock.lock();
        try {
            if (!Files.exists(file)) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("code", "REMOVED");
                body.put("error", "this file is already gone");
                return new WriteResult(HttpStatus.NOT_FOUND, null, body);
            }
            String current = etag(Files.readAllBytes(file));
            String presented = normalise(ifMatch);
            if (presented == null && strict) {
                return refused(HttpStatus.PRECONDITION_REQUIRED, "PRECONDITION_REQUIRED",
                        "send If-Match with the ETag of the version you are removing", current, file);
            }
            if (presented != null && !presented.equals(current)) {
                return refused(HttpStatus.PRECONDITION_FAILED, "STALE",
                        "this file changed since you looked at it; nothing was removed", current, file);
            }
            Files.delete(file);
            return new WriteResult(HttpStatus.NO_CONTENT, null, new LinkedHashMap<>());
        } finally {
            lock.unlock();
        }
    }

    /** Readers, commits and test runs never see a half-written file: write aside, then swap in. */
    public static void writeAtomically(Path file, byte[] content) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = Files.createTempFile(file.getParent(), "." + file.getFileName(), ".tmp");
        try {
            Files.write(tmp, content);
            try {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private ReentrantLock lockOf(Path file) {
        return locks.computeIfAbsent(file.toAbsolutePath().normalize(), p -> new ReentrantLock());
    }

    public WriteResult write(Path file, String content, String ifMatch, boolean strict) throws IOException {
        return write(file, content.getBytes(StandardCharsets.UTF_8), ifMatch, strict);
    }

    private WriteResult refused(HttpStatus status, String code, String message, String current, Path file)
            throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("error", message);
        body.put("currentEtag", current);
        body.put("changedAt", Files.getLastModifiedTime(file).toInstant().toString());
        return new WriteResult(status, current, body);
    }

    /** Accepts {@code "abc"} and weak {@code W/"abc"}; returns null for absent or wildcard. */
    private static String normalise(String ifMatch) {
        if (ifMatch == null) {
            return null;
        }
        String value = ifMatch.trim();
        if (value.isEmpty() || "*".equals(value)) {
            return null;
        }
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        if (!value.startsWith("\"")) {
            value = "\"" + value + "\"";
        }
        return value;
    }
}

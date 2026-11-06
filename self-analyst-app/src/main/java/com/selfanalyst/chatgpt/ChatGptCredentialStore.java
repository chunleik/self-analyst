package com.selfanalyst.chatgpt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/** Owner-only storage. The OS account (including other processes of that account) is trusted. */
final class ChatGptCredentialStore {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Set<PosixFilePermission> DIRECTORY_MODE = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_MODE = PosixFilePermissions.fromString("rw-------");
    private static final ConcurrentHashMap<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private final Path directory;
    private final Path credentials;
    private final Path lockFile;
    private final ReentrantLock localLock;

    ChatGptCredentialStore(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
        this.credentials = this.directory.resolve("credentials.json");
        this.lockFile = this.directory.resolve(".lock");
        this.localLock = LOCKS.computeIfAbsent(this.directory, ignored -> new ReentrantLock());
    }

    ObjectNode read() {
        localLock.lock();
        try {
            checkComponents(directory);
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return JSON.createObjectNode();
            verify(directory, true);
            if (!Files.exists(lockFile, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.exists(credentials, LinkOption.NOFOLLOW_LINKS)) throw unavailable();
                return JSON.createObjectNode();
            }
            verify(lockFile, false);
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 FileLock ignored = channel.lock()) {
                verify(lockFile, false);
                return load();
            }
        } catch (IOException | SecurityException | UnsupportedOperationException | OverlappingFileLockException ex) {
            throw unavailable();
        } finally {
            localLock.unlock();
        }
    }

    <T> T locked(Function<Transaction, T> operation) {
        localLock.lock();
        try {
            prepareDirectory();
            if (!Files.exists(lockFile, LinkOption.NOFOLLOW_LINKS)) {
                try (FileChannel channel = FileChannel.open(lockFile,
                        Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                        protection(directory, false))) {
                    initializeNewObject(lockFile, false);
                    channel.force(true);
                } catch (java.nio.file.FileAlreadyExistsException ignored) {
                    // Another process may have initialized the lock while we waited.
                }
            }
            verify(lockFile, false);
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 FileLock ignored = channel.lock()) {
                verify(directory, true);
                verify(lockFile, false);
                ObjectNode data = load();
                boolean fresh = data.isEmpty();
                if (fresh) {
                    data.put("hostId", "urn:uuid:" + UUID.randomUUID());
                    data.putObject("accounts");
                    data.put("activeAccountId", "");
                }
                Transaction transaction = new Transaction(data);
                try {
                    if (fresh) transaction.save();
                    try { return operation.apply(transaction); }
                    catch (RuntimeException ex) { throw new CallbackFailure(ex); }
                } finally {
                    transaction.open = false;
                }
            }
        } catch (CallbackFailure ex) {
            throw ex.original;
        } catch (IOException | SecurityException | UnsupportedOperationException | OverlappingFileLockException ex) {
            throw unavailable();
        } finally {
            localLock.unlock();
        }
    }

    private static final class CallbackFailure extends RuntimeException {
        private final RuntimeException original;
        private CallbackFailure(RuntimeException original) {
            super(null, null, false, false);
            this.original = original;
        }
    }

    final class Transaction {
        private final ObjectNode data;
        private final Thread owner = Thread.currentThread();
        private boolean open = true;

        private Transaction(ObjectNode data) { this.data = data; }

        ObjectNode data() {
            checkOpen();
            return data;
        }

        void save() {
            checkOpen();
            persist(data);
        }

        private void checkOpen() {
            if (!open || Thread.currentThread() != owner || !localLock.isHeldByCurrentThread()) throw unavailable();
        }
    }

    private ObjectNode load() throws IOException {
        verify(directory, true);
        if (!Files.exists(credentials, LinkOption.NOFOLLOW_LINKS)) return JSON.createObjectNode();
        verify(credentials, false);
        try (FileChannel input = FileChannel.open(credentials, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = input.size();
            if (size <= 0 || size > MAX_BYTES) throw unavailable();
            ByteBuffer buffer = ByteBuffer.allocate((int) size);
            while (buffer.hasRemaining()) {
                if (input.read(buffer) < 0) throw unavailable();
            }
            JsonNode parsed = JSON.readTree(buffer.array());
            if (!(parsed instanceof ObjectNode object)) throw unavailable();
            validate(object);
            return object;
        }
    }

    private void persist(ObjectNode data) {
        Path temporary = null;
        try {
            validate(data);
            verify(directory, true);
            if (Files.exists(credentials, LinkOption.NOFOLLOW_LINKS)) verify(credentials, false);
            byte[] bytes = JSON.writeValueAsBytes(data);
            if (bytes.length > MAX_BYTES) throw unavailable();
            temporary = Files.createTempFile(directory, ".credentials-", ".tmp", protection(directory, false));
            initializeNewObject(temporary, false);
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            // Atomic replacement is mandatory. Never fall back to a non-atomic move.
            Files.move(temporary, credentials, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
            verify(credentials, false);
        } catch (IOException | SecurityException | UnsupportedOperationException | OverlappingFileLockException ex) {
            throw unavailable();
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException | SecurityException ignored) { /* Never expose paths or data in cleanup errors. */ }
            }
        }
    }

    private void validate(ObjectNode data) {
        if (!data.path("hostId").isTextual() || !data.path("hostId").asText().startsWith("urn:uuid:")
                || !data.path("accounts").isObject() || !data.path("activeAccountId").isTextual()) throw unavailable();
        try { UUID.fromString(data.path("hostId").asText().substring(9)); }
        catch (IllegalArgumentException ex) { throw unavailable(); }
    }

    private void prepareDirectory() throws IOException {
        checkComponents(directory);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            Path ancestor = directory.getParent();
            while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.getParent();
            if (ancestor == null) throw unavailable();
            Path cursor = ancestor;
            for (Path part : ancestor.relativize(directory)) {
                Path next = cursor.resolve(part);
                try {
                    Files.createDirectory(next, protection(cursor, true));
                    initializeNewObject(next, true);
                }
                catch (java.nio.file.FileAlreadyExistsException ignored) { /* Validate the winning creation. */ }
                verify(next, true);
                cursor = next;
            }
        }
        verify(directory, true);
    }

    private static void checkComponents(Path path) throws IOException {
        if (path == null) throw unavailable();
        Path cursor = path.getRoot();
        for (Path part : path) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw unavailable();
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)
                    && !Files.readAttributes(cursor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isDirectory()) {
                throw unavailable();
            }
        }
    }

    private static UserPrincipal currentUser(Path path) throws IOException {
        return path.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
    }

    private static FileAttribute<?> protection(Path existing, boolean directory) throws IOException {
        if (Files.getFileAttributeView(existing, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) != null) {
            return PosixFilePermissions.asFileAttribute(directory ? DIRECTORY_MODE : FILE_MODE);
        }
        if (Files.getFileAttributeView(existing, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) == null) throw unavailable();
        AclEntry.Builder entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                .setPrincipal(currentUser(existing)).setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (directory) entry.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT);
        List<AclEntry> acl = List.of(entry.build());
        return new FileAttribute<List<AclEntry>>() {
            public String name() { return "acl:acl"; }
            public List<AclEntry> value() { return acl; }
        };
    }

    /** Call only after this process exclusively creates an empty object, before writing any data. */
    private static void initializeNewObject(Path path, boolean directory) throws IOException {
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) == null) {
            BasicFileAttributes basic = checkedAttributes(path, directory);
            AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (acl == null) throw unavailable();
            UserPrincipal user = currentUser(path);
            verifyAcl(acl, user, directory);
            if (directory) {
                try (var entries = Files.newDirectoryStream(path)) {
                    if (entries.iterator().hasNext()) throw unavailable();
                }
            } else if (basic.size() != 0) {
                throw unavailable();
            }
            // Elevated Windows tokens can default to Administrators as owner even when the
            // creation ACL grants only this user. Normalize only our new, still-empty object.
            // Existing stores (including empty directories and lock files) are never repaired.
            if (!acl.getOwner().equals(user)) acl.setOwner(user);
        }
        verify(path, directory);
    }

    private static BasicFileAttributes checkedAttributes(Path path, boolean directory) throws IOException {
        checkComponents(path.getParent());
        BasicFileAttributes basic = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (basic.isSymbolicLink() || (directory ? !basic.isDirectory() : !basic.isRegularFile())) throw unavailable();
        return basic;
    }

    private static void verify(Path path, boolean directory) throws IOException {
        checkedAttributes(path, directory);
        UserPrincipal user = currentUser(path);
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            PosixFileAttributes attributes = posix.readAttributes();
            if (!attributes.owner().equals(user) || !attributes.permissions().equals(directory ? DIRECTORY_MODE : FILE_MODE)) {
                throw unavailable();
            }
            if (!directory && path.getFileSystem().supportedFileAttributeViews().contains("unix")
                    && ((Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1) {
                throw unavailable();
            }
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        verifyAclProtection(acl, user, directory);
    }

    static void verifyAclProtection(AclFileAttributeView acl, UserPrincipal user, boolean directory) throws IOException {
        if (acl == null || !acl.getOwner().equals(user)) throw unavailable();
        verifyAcl(acl, user, directory);
    }

    private static void verifyAcl(AclFileAttributeView acl, UserPrincipal user, boolean directory) throws IOException {
        List<AclEntry> entries = acl.getAcl();
        if (entries.isEmpty()) throw unavailable();
        for (AclEntry entry : entries) {
            if (entry.type() != AclEntryType.ALLOW || !entry.principal().equals(user)) throw unavailable();
        }
        if (entries.stream().noneMatch(entry -> entry.permissions().containsAll(EnumSet.allOf(AclEntryPermission.class))
                && (!directory || entry.flags().containsAll(Set.of(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT))))) {
            throw unavailable();
        }
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("chatgpt_storage_unavailable");
    }
}

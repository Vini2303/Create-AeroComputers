package net.tui.aerocomputers.script;

import net.minecraft.world.level.storage.LevelResource;
import org.graalvm.polyglot.io.FileSystem;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.FileAttribute;
import java.util.Map;
import java.util.Set;

/** Read/write inside root; read-only inside runtimeDir (GraalPy's own stdlib). Everything else is denied. */
public final class SandboxFileSystem implements FileSystem {
    private static final long MAX_FILE_BYTES = 1_048_576;

    private final Path root;
    private final Path runtimeDir;
    private final FileSystem delegate = FileSystem.newDefaultFileSystem();

    public SandboxFileSystem(Path root, Path runtimeDir) throws IOException {
        Files.createDirectories(root);
        Files.createDirectories(runtimeDir);
        this.root = root.toRealPath();
        this.runtimeDir = runtimeDir.toRealPath();
        delegate.setCurrentWorkingDirectory(this.root);
    }

    public static Path rootFor(net.minecraft.server.MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT)
                .resolve("aerocomputers")
                .toAbsolutePath()
                .normalize();
    }

    /** Resolves relative paths against root and enforces the access rules. */
    private Path check(Path p, boolean write) {
        Path abs = (p.isAbsolute() ? p : root.resolve(p)).normalize();
        if (abs.startsWith(root)) return abs;
        if (!write && abs.startsWith(runtimeDir)) return abs;
        throw new SecurityException("Path outside sandbox: " + p);
    }

    private static boolean isWrite(Set<? extends OpenOption> options) {
        for (OpenOption o : options) {
            if (o == StandardOpenOption.WRITE || o == StandardOpenOption.APPEND
                    || o == StandardOpenOption.CREATE || o == StandardOpenOption.CREATE_NEW
                    || o == StandardOpenOption.TRUNCATE_EXISTING
                    || o == StandardOpenOption.DELETE_ON_CLOSE) {
                return true;
            }
        }
        return false;
    }

    // Must be a pure parse: Truffle calls this for its own internal paths.
    @Override
    public Path parsePath(URI uri) {
        return Paths.get(uri);
    }

    @Override
    public Path parsePath(String path) {
        return Paths.get(path);
    }

    @Override
    public void checkAccess(Path path, Set<? extends AccessMode> modes, LinkOption... links) throws IOException {
        boolean write = modes.contains(AccessMode.WRITE);
        delegate.checkAccess(check(path, write), modes, links);
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        delegate.createDirectory(check(dir, true), attrs);
    }

    @Override
    public void delete(Path path) throws IOException {
        Path p = check(path, true);
        if (p.equals(root)) throw new SecurityException("Cannot delete the root");
        delegate.delete(p);
    }

    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
                                              FileAttribute<?>... attrs) throws IOException {
        boolean write = isWrite(options);
        SeekableByteChannel ch = delegate.newByteChannel(check(path, write), options, attrs);
        return write ? new CappedChannel(ch) : ch;
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException {
        return delegate.newDirectoryStream(check(dir, false), filter);
    }

    @Override
    public Path toAbsolutePath(Path path) {
        return check(path, false);
    }

    @Override
    public Path toRealPath(Path path, LinkOption... options) throws IOException {
        Path real = delegate.toRealPath(check(path, false), options);
        if (!real.startsWith(root) && !real.startsWith(runtimeDir)) {
            throw new SecurityException("Path outside sandbox: " + path);
        }
        return real;
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        return delegate.readAttributes(check(path, false), attributes, options);
    }

    private static final class CappedChannel implements SeekableByteChannel {
        private final SeekableByteChannel inner;
        CappedChannel(SeekableByteChannel inner) { this.inner = inner; }

        @Override public int read(ByteBuffer dst) throws IOException { return inner.read(dst); }
        @Override public int write(ByteBuffer src) throws IOException {
            if (inner.position() + src.remaining() > MAX_FILE_BYTES) {
                throw new IOException("File too large (max " + MAX_FILE_BYTES + " bytes)");
            }
            return inner.write(src);
        }
        @Override public long position() throws IOException { return inner.position(); }
        @Override public SeekableByteChannel position(long p) throws IOException { inner.position(p); return this; }
        @Override public long size() throws IOException { return inner.size(); }
        @Override public SeekableByteChannel truncate(long s) throws IOException { inner.truncate(s); return this; }
        @Override public boolean isOpen() { return inner.isOpen(); }
        @Override public void close() throws IOException { inner.close(); }
    }
}
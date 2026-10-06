package com.datasifter.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class JsonFileStore {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final Path DATA_DIR = configuredDataDirectory();
    private static final ConcurrentMap<Path, ReentrantReadWriteLock> FILE_LOCKS = new ConcurrentHashMap<>();

    public static Path resolveDataDirectory() {
        try {
            Files.createDirectories(DATA_DIR);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create DataSifter user data directory", e);
        }
        return DATA_DIR;
    }

    private static Path configuredDataDirectory() {
        String configured = System.getProperty("datasifter.data-directory");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("DATASIFTER_DATA_DIRECTORY");
        }
        if (configured == null || configured.isBlank()) {
            configured = Paths.get(System.getProperty("user.home"), ".datasifter").toString();
        }
        return Paths.get(configured);
    }

    public static <T> List<T> readList(Path file, TypeReference<List<T>> typeReference) {
        Path normalizedFile = normalizeFile(file);
        ReentrantReadWriteLock.ReadLock lock = lockFor(normalizedFile).readLock();
        lock.lock();
        try {
            if (Files.notExists(normalizedFile)) {
                return new ArrayList<>();
            }
            List<T> items = MAPPER.readValue(normalizedFile.toFile(), typeReference);
            if (items == null) {
                throw new IllegalStateException("JSON store must contain an array: " + normalizedFile);
            }
            return items;
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read JSON store: " + normalizedFile, e);
        } finally {
            lock.unlock();
        }
    }

    public static <T> void writeList(Path file, List<T> items) {
        Path normalizedFile = normalizeFile(file);
        if (items == null) {
            throw new IllegalArgumentException("JSON store items cannot be null");
        }

        ReentrantReadWriteLock.WriteLock lock = lockFor(normalizedFile).writeLock();
        lock.lock();
        Path temporaryFile = null;
        try {
            Path parent = normalizedFile.getParent();
            if (parent == null) {
                throw new IllegalArgumentException("JSON store file must have a parent directory: " + normalizedFile);
            }
            Files.createDirectories(parent);
            temporaryFile = Files.createTempFile(parent, "." + normalizedFile.getFileName() + ".", ".tmp");
            byte[] serialized = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(items);
            try (FileChannel channel = FileChannel.open(
                    temporaryFile,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(serialized);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            replaceFile(temporaryFile, normalizedFile);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write JSON store: " + normalizedFile, e);
        } finally {
            try {
                if (temporaryFile != null) {
                    Files.deleteIfExists(temporaryFile);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Unable to clean up temporary JSON store file: " + temporaryFile, e);
            } finally {
                lock.unlock();
            }
        }
    }

    private static void replaceFile(Path source, Path destination) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AtomicMoveNotSupportedException unsupportedAtomicMove) {
                Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AccessDeniedException transientFileLock) {
                if (attempt == 3) {
                    throw transientFileLock;
                }
                try {
                    Thread.sleep(10L << attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while replacing JSON store file", interrupted);
                }
            }
        }
    }

    private static Path normalizeFile(Path file) {
        if (file == null) {
            throw new IllegalArgumentException("JSON store file cannot be null");
        }
        return file.toAbsolutePath().normalize();
    }

    private static ReentrantReadWriteLock lockFor(Path file) {
        return FILE_LOCKS.computeIfAbsent(file, ignored -> new ReentrantReadWriteLock());
    }
}

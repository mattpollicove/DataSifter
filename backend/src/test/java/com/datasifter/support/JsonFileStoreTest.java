package com.datasifter.support;

import com.fasterxml.jackson.core.type.TypeReference;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonFileStoreTest {
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    @TempDir
    Path temporaryDirectory;

    @Test
    void missingStoreStartsEmptyAndWritesCanBeReadBack() {
        Path file = temporaryDirectory.resolve("state.json");

        assertEquals(List.of(), JsonFileStore.readList(file, STRING_LIST));

        JsonFileStore.writeList(file, List.of("first", "second"));

        assertEquals(List.of("first", "second"), JsonFileStore.readList(file, STRING_LIST));
    }

    @Test
    void corruptOrEmptyExistingStoreFailsInsteadOfAppearingEmpty() throws IOException {
        Path corruptFile = temporaryDirectory.resolve("corrupt.json");
        Files.writeString(corruptFile, "{not-json");
        assertThrows(IllegalStateException.class, () -> JsonFileStore.readList(corruptFile, STRING_LIST));

        Path emptyFile = temporaryDirectory.resolve("empty.json");
        Files.createFile(emptyFile);
        assertThrows(IllegalStateException.class, () -> JsonFileStore.readList(emptyFile, STRING_LIST));
    }

    @Test
    void replacementWritePersistsNewValueWithoutLeavingTemporaryFiles() throws IOException {
        Path file = temporaryDirectory.resolve("state.json");
        JsonFileStore.writeList(file, List.of("before"));

        JsonFileStore.writeList(file, List.of("after"));

        assertEquals(List.of("after"), JsonFileStore.readList(file, STRING_LIST));
        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(List.of(file), files.toList());
        }
    }

    @Test
    void writeFailureIsReported() throws IOException {
        Path parentFile = temporaryDirectory.resolve("not-a-directory");
        Files.writeString(parentFile, "blocker");
        Path target = parentFile.resolve("state.json");

        assertThrows(IllegalStateException.class, () -> JsonFileStore.writeList(target, List.of("value")));
    }

    @Test
    void serializationFailurePreservesPreviousStateAndCleansTemporaryFile() throws IOException {
        Path file = temporaryDirectory.resolve("state.json");
        JsonFileStore.writeList(file, List.of("preserved"));

        assertThrows(IllegalStateException.class, () -> JsonFileStore.writeList(file, List.of(new FailingValue())));

        assertEquals(List.of("preserved"), JsonFileStore.readList(file, STRING_LIST));
        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(List.of(file), files.toList());
        }
    }

    public static class FailingValue {
        public String getValue() {
            throw new IllegalStateException("Intentional serialization failure");
        }
    }
}

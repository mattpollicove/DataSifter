package com.datasifter.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalWorkflowFileStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void storesAndStreamsFilesThroughOpaqueReferences() throws Exception {
        LocalWorkflowFileStore store = new LocalWorkflowFileStore(temporaryDirectory.toString());
        String reference = store.store(new ByteArrayInputStream("id,name\n1,Ada\n".getBytes(StandardCharsets.UTF_8)),
                "users.csv");

        assertEquals("local:", reference.substring(0, 6));
        try (var input = store.open(reference)) {
            assertEquals("id,name\n1,Ada\n", new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        store.delete(reference);
        assertThrows(IOException.class, () -> store.open(reference));
    }

    @Test
    void rejectsReferencesThatEscapeTheManagedUploadDirectory() {
        LocalWorkflowFileStore store = new LocalWorkflowFileStore(temporaryDirectory.toString());
        assertThrows(IOException.class, () -> store.open("local:../outside.csv"));
    }
}

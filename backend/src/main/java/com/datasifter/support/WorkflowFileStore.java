package com.datasifter.support;

import java.io.IOException;
import java.io.InputStream;

public interface WorkflowFileStore {
    String providerId();

    String store(InputStream content, String fileName) throws IOException;

    default String store(InputStream content, String fileName, long contentLength) throws IOException {
        return store(content, fileName);
    }

    InputStream open(String reference) throws IOException;

    void delete(String reference) throws IOException;
}

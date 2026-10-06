package com.datasifter.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

final class ProviderSettings {
    private ProviderSettings() {
    }

    static String required(Map<String, String> environment, String name) {
        String value = read(environment, name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required provider setting is missing: " + name);
        }
        return value;
    }

    static String optional(Map<String, String> environment, String name, String defaultValue) {
        String value = read(environment, name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    static boolean enabled(Map<String, String> environment, String name) {
        String value = read(environment, name);
        return value != null && value.equalsIgnoreCase("true");
    }

    private static String read(Map<String, String> environment, String name) {
        String direct = environment.get(name);
        String file = environment.get(name + "_FILE");
        if (direct != null && file != null) {
            throw new IllegalStateException("Set either " + name + " or " + name + "_FILE, not both");
        }
        if (direct != null) {
            return direct.trim();
        }
        if (file == null || file.isBlank()) {
            return null;
        }
        try {
            return Files.readString(Path.of(file)).trim();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read provider secret file for " + name, e);
        }
    }
}

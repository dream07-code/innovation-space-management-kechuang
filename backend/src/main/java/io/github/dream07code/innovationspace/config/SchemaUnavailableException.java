package io.github.dream07code.innovationspace.config;

public class SchemaUnavailableException extends RuntimeException {
    public SchemaUnavailableException() {
        super("Database schema is unavailable");
    }
}

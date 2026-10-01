package engine.config;

/** Błąd w pliku konfiguracyjnym: zły JSON, brakująca albo nieprawidłowa wartość. */
public final class ConfigException extends RuntimeException {

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}

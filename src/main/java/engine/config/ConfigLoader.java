package engine.config;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Wczytuje {@link EngineConfig} w dwóch warstwach:
 *
 * 1. Wartości domyślne z classpath (config/engine-defaults.json) - zawsze
 *    kompletne, spakowane w jarze.
 * 2. Opcjonalny plik użytkownika, który nadpisuje TYLKO podane w nim klucze.
 *    Wystarczy więc np. {@code {"window": {"width": 1920, "height": 1080}}},
 *    bez przepisywania całej konfiguracji.
 *
 * Klucze z pliku użytkownika, których nie ma w domyślnych, są zgłaszane
 * jako ostrzeżenie - zwykle to literówka ("widht"), która inaczej zostałaby
 * po cichu zignorowana.
 *
 * Konfiguracja jest wczytywana przed utworzeniem okna i kontekstu Vulkana,
 * więc celowo nie korzysta z ResourceManagera (ten potrzebuje już GPU).
 */
public final class ConfigLoader {

    /** Plik użytkownika szukany w katalogu roboczym, gdy nie podano ścieżki w argumentach. */
    public static final String DEFAULT_USER_CONFIG = "engine.json";

    private static final String DEFAULTS_RESOURCE = "config/engine-defaults.json";

    private static final Gson GSON = new Gson();

    private ConfigLoader() {
    }

    /**
     * Wybiera plik konfiguracji na podstawie argumentów programu:
     * pierwszy argument = ścieżka do pliku (musi istnieć), w przeciwnym razie
     * ./engine.json jeśli istnieje, a jeśli nie - same wartości domyślne.
     */
    public static EngineConfig fromArgs(String[] args) {
        if (args.length > 0) {
            Path file = Path.of(args[0]);
            if (!Files.isRegularFile(file)) {
                throw new ConfigException("Nie znaleziono pliku konfiguracji: " + file.toAbsolutePath());
            }
            return load(file);
        }
        Path file = Path.of(DEFAULT_USER_CONFIG);
        return load(Files.isRegularFile(file) ? file : null);
    }

    /** @param userFile plik nadpisujący wartości domyślne albo null = same domyślne */
    public static EngineConfig load(Path userFile) {
        JsonObject merged = readDefaults();

        if (userFile != null) {
            System.out.println("[Config] Wczytuję konfigurację: " + userFile.toAbsolutePath());
            JsonObject user = readUserFile(userFile);
            mergeInto(merged, user, "");
        } else {
            System.out.println("[Config] Brak " + DEFAULT_USER_CONFIG + " - używam konfiguracji domyślnej");
        }

        return toConfig(merged, userFile != null ? userFile.toString() : DEFAULTS_RESOURCE);
    }

    private static JsonObject readDefaults() {
        try (InputStream in = ConfigLoader.class.getClassLoader().getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (in == null) {
                throw new ConfigException("Brak domyślnej konfiguracji na classpath: " + DEFAULTS_RESOURCE);
            }
            return parseObject(new InputStreamReader(in, StandardCharsets.UTF_8), DEFAULTS_RESOURCE);
        } catch (IOException e) {
            throw new ConfigException("Nie udało się odczytać " + DEFAULTS_RESOURCE, e);
        }
    }

    private static JsonObject readUserFile(Path file) {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parseObject(reader, file.toString());
        } catch (IOException e) {
            throw new ConfigException("Nie udało się odczytać pliku konfiguracji " + file.toAbsolutePath(), e);
        }
    }

    private static JsonObject parseObject(Reader reader, String source) {
        try {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                throw new ConfigException(source + ": oczekiwano obiektu JSON { ... } na najwyższym poziomie");
            }
            return root.getAsJsonObject();
        } catch (JsonParseException e) {
            throw new ConfigException(source + ": niepoprawny JSON - " + e.getMessage(), e);
        }
    }

    /**
     * Głębokie scalanie: obiekty łączone są rekurencyjnie klucz po kluczu,
     * a każda inna wartość (liczba, tekst, tablica) zastępuje domyślną w całości.
     */
    private static void mergeInto(JsonObject target, JsonObject override, String pathPrefix) {
        for (Map.Entry<String, JsonElement> entry : override.entrySet()) {
            String key = entry.getKey();
            String path = pathPrefix + key;
            JsonElement value = entry.getValue();
            JsonElement existing = target.get(key);

            if (existing == null) {
                System.err.println("[Config] Nieznany klucz \"" + path + "\" - pomijam (literówka?)");
                continue;
            }
            if (existing.isJsonObject() && value.isJsonObject()) {
                mergeInto(existing.getAsJsonObject(), value.getAsJsonObject(), path + ".");
            } else {
                target.add(key, value);
            }
        }
    }

    private static EngineConfig toConfig(JsonObject json, String source) {
        try {
            return GSON.fromJson(json, EngineConfig.class);
        } catch (RuntimeException e) {
            // Walidacja w konstruktorach rekordów dociera tu opakowana przez Gson - wyciągamy oryginał.
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t instanceof ConfigException ce) {
                    throw new ConfigException(source + ": " + ce.getMessage(), ce);
                }
            }
            throw new ConfigException(source + ": nieprawidłowa wartość w konfiguracji - " + e.getMessage(), e);
        }
    }
}

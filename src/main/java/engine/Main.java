package engine;

import engine.config.ConfigException;
import engine.config.ConfigLoader;
import engine.config.EngineConfig;
import engine.core.Engine;
import engine.log.Log;

public final class Main {

    /** @param args opcjonalnie: ścieżka do pliku konfiguracji JSON (domyślnie ./engine.json, jeśli istnieje) */
    public static void main(String[] args) {
        EngineConfig config;
        try {
            config = ConfigLoader.fromArgs(args);
        } catch (ConfigException e) {
            // Zły plik konfiguracji to błąd użytkownika, nie programu - wystarczy czytelny komunikat.
            Log.error("Config", e.getMessage());
            System.exit(1);
            return;
        }
        Log.configure(config.log().level(), config.log().historySize());

        try {
            new Engine(config).run();
        } catch (RuntimeException | Error e) {
            // Ostatnia linia obrony: każdy nieobsłużony błąd trafia do logu z pełnym stack trace.
            Log.error("Engine", "Nieobsłużony błąd - zatrzymuję silnik", e);
            System.exit(1);
        }
    }
}

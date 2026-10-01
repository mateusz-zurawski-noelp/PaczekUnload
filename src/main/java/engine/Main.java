package engine;

import engine.config.ConfigLoader;
import engine.config.EngineConfig;
import engine.core.Engine;

public final class Main {

    /** @param args opcjonalnie: ścieżka do pliku konfiguracji JSON (domyślnie ./engine.json, jeśli istnieje) */
    public static void main(String[] args) {
        EngineConfig config = ConfigLoader.fromArgs(args);
        new Engine(config).run();
    }
}

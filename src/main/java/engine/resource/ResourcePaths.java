package engine.resource;

import java.nio.file.Path;

/**
 * Kanoniczna postać ścieżek zasobów, żeby "textures/a.png",
 * "./textures/a.png" i "textures\\a.png" trafiały do tego samego wpisu
 * w cache'u, zamiast wczytywać ten sam plik kilka razy.
 */
final class ResourcePaths {

    private ResourcePaths() {
    }

    static String normalize(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Pusta ścieżka zasobu");
        }
        String normalized = Path.of(path.replace('\\', '/')).normalize().toString().replace('\\', '/');
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Nieprawidłowa ścieżka zasobu: " + path);
        }
        return normalized;
    }
}

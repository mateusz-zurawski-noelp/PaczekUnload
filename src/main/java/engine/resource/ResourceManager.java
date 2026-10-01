package engine.resource;

import engine.vulkan.CommandPool;
import engine.vulkan.VulkanContext;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Centralny podsystem zasobów (Gregory, "Resources and the Resource
 * Manager"). Ma dwie role:
 *
 * 1. Warstwa plików - {@link #readBytes(String)} szuka zasobu najpierw na
 *    classpath (src/main/resources, spakowany jar), a potem na dysku
 *    (ścieżka względem katalogu roboczego albo bezwzględna). Dzięki temu
 *    konkretne menedżery nie muszą wiedzieć, skąd plik pochodzi.
 *
 * 2. Właściciel menedżerów poszczególnych typów zasobów (na razie tekstur;
 *    siatki, shadery, dźwięki dochodzą tu tak samo) i jedno miejsce, gdzie
 *    silnik sprząta nieużywane zasoby ({@link #collectGarbage()}) i zamyka
 *    całość ({@link #shutdown()}).
 *
 * Nie jest bezpieczny wątkowo - zakładamy, że wszystkie zasoby są
 * wczytywane z wątku głównego (tak jak całe nagrywanie poleceń Vulkana).
 */
public final class ResourceManager {

    private final TextureManager textures;

    public ResourceManager(VulkanContext ctx, CommandPool commandPool) {
        this.textures = new TextureManager(this, ctx, commandPool);
    }

    public TextureManager textures() {
        return textures;
    }

    /**
     * Wczytuje cały plik do bufora poza stertą Javy (tego oczekują
     * stb_image, Assimp itp.). Wywołujący musi go zwolnić przez
     * {@link MemoryUtil#memFree}.
     */
    public ByteBuffer readBytes(String path) {
        String key = ResourcePaths.normalize(path);
        byte[] bytes = readFromClasspath(key);
        if (bytes == null) {
            bytes = readFromFileSystem(key);
        }
        ByteBuffer buffer = MemoryUtil.memAlloc(bytes.length);
        buffer.put(bytes).flip();
        return buffer;
    }

    private static byte[] readFromClasspath(String path) {
        try (InputStream in = ResourceManager.class.getClassLoader().getResourceAsStream(path)) {
            return in != null ? in.readAllBytes() : null;
        } catch (IOException e) {
            throw new RuntimeException("Nie udało się odczytać zasobu z classpath: " + path, e);
        }
    }

    private static byte[] readFromFileSystem(String path) {
        Path file = Path.of(path);
        if (!Files.isRegularFile(file)) {
            throw new RuntimeException("Nie znaleziono zasobu ani na classpath, ani na dysku: " + path);
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new RuntimeException("Nie udało się odczytać pliku " + file.toAbsolutePath(), e);
        }
    }

    /**
     * Niszczy zasoby, do których nikt już nie trzyma referencji. Wołać tylko
     * wtedy, gdy GPU na pewno ich nie używa (np. po vkDeviceWaitIdle).
     */
    public void collectGarbage() {
        textures.cache().destroyUnused();
    }

    /** Niszczy wszystkie zasoby. Wywoływać przed zniszczeniem urządzenia Vulkana. */
    public void shutdown() {
        reportLeaks(textures.cache().kind(), textures.cache().destroyAll());
        textures.destroyBuiltIns();
    }

    private static void reportLeaks(String kind, List<String> leaked) {
        for (String path : leaked) {
            System.err.println("[ResourceManager] Niezwolniony zasób (" + kind + "): " + path);
        }
    }
}

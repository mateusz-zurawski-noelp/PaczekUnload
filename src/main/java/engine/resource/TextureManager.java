package engine.resource;

import engine.log.Log;
import engine.vulkan.CommandPool;
import engine.vulkan.Texture;
import engine.vulkan.VulkanContext;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Jedyne miejsce w silniku, które wczytuje tekstury z plików. Zamiast
 * {@code new Texture(path)} kod gry woła {@link #acquire(String)} - dwa
 * materiały proszące o "textures/chalet.jpg" dostaną ten sam obiekt
 * Texture (jedna kopia w pamięci GPU), a nie dwie.
 *
 * Każde acquire musi mieć swoje {@link #release(Texture)}. Zwolnione
 * tekstury są niszczone dopiero przy {@link ResourceManager#collectGarbage()}
 * lub {@link ResourceManager#shutdown()} - patrz ResourceCache.
 *
 * Menedżer trzyma też wbudowane tekstury proceduralne (np. {@link #white()}),
 * które nie pochodzą z pliku, nie są zliczane i żyją do shutdown().
 */
public final class TextureManager {

    private final ResourceCache<Texture> cache;
    private final Texture white;

    TextureManager(ResourceManager resources, VulkanContext ctx, CommandPool commandPool) {
        this.cache = new ResourceCache<>("tekstura", path -> load(resources, ctx, commandPool, path), Texture::destroy);
        this.white = Texture.solidColor(ctx, commandPool, 255, 255, 255, 255);
    }

    private static Texture load(ResourceManager resources, VulkanContext ctx, CommandPool commandPool, String path) {
        long start = System.nanoTime();
        ByteBuffer encoded = resources.readBytes(path);
        try {
            Texture texture = Texture.fromEncodedImage(ctx, commandPool, encoded, path);
            Log.info("Resources", "Tekstura " + path + " (" + (System.nanoTime() - start) / 1_000_000 + " ms)");
            return texture;
        } finally {
            MemoryUtil.memFree(encoded);
        }
    }

    /** Zwraca teksturę spod ścieżki, wczytując ją tylko przy pierwszym żądaniu. */
    public Texture acquire(String path) {
        return cache.acquire(path);
    }

    /** Oddaje referencję uzyskaną przez {@link #acquire(String)}. */
    public void release(Texture texture) {
        cache.release(texture);
    }

    /** Biała tekstura 1x1 - neutralny zamiennik dla materiałów bez danej tekstury. Nie zwalniać. */
    public Texture white() {
        return white;
    }

    /** Ile aktywnych referencji ma tekstura spod ścieżki (0 = nieużywana albo niewczytana). */
    public int refCount(String path) {
        return cache.refCount(path);
    }

    /** Liczba tekstur z plików trzymanych w pamięci (łącznie z nieużywanymi, czekającymi na sprzątanie). */
    public int loadedCount() {
        return cache.size();
    }

    ResourceCache<Texture> cache() {
        return cache;
    }

    void destroyBuiltIns() {
        white.destroy();
    }
}

package engine.resource;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Wspólny mechanizm menedżerów zasobów: cache "ścieżka -> zasób" ze
 * zliczaniem referencji. Pierwsze {@link #acquire} wczytuje zasób z dysku,
 * każde kolejne dla tej samej ścieżki zwraca TEN SAM obiekt i tylko
 * zwiększa licznik. {@link #release} go zmniejsza.
 *
 * Zasób z licznikiem 0 NIE jest niszczony od razu - GPU może wciąż rysować
 * klatkę "w locie", która go używa. Zostaje w cache'u jako nieużywany, aż
 * silnik w bezpiecznym momencie (np. po vkDeviceWaitIdle, przy zmianie
 * poziomu) wywoła {@link #destroyUnused()}. Dodatkowa korzyść: ponowne
 * acquire przed sprzątaniem nie wczytuje pliku drugi raz.
 *
 * Typ T musi mieć tożsamość obiektową (release szuka zasobu po referencji,
 * nie po equals) - dla obiektów GPU typu Texture/Mesh to naturalne.
 */
final class ResourceCache<T> {

    private static final class Entry<T> {
        final String path;
        final T resource;
        int refCount;

        Entry(String path, T resource) {
            this.path = path;
            this.resource = resource;
        }
    }

    private final String kind;
    private final Function<String, T> loader;
    private final Consumer<T> destroyer;

    private final Map<String, Entry<T>> byPath = new LinkedHashMap<>();
    private final Map<T, Entry<T>> byResource = new IdentityHashMap<>();

    /**
     * @param kind      nazwa rodzaju zasobu do komunikatów (np. "tekstura")
     * @param loader    wczytuje zasób spod znormalizowanej ścieżki
     * @param destroyer zwalnia zasób (np. Texture::destroy)
     */
    ResourceCache(String kind, Function<String, T> loader, Consumer<T> destroyer) {
        this.kind = kind;
        this.loader = loader;
        this.destroyer = destroyer;
    }

    T acquire(String path) {
        String key = ResourcePaths.normalize(path);
        Entry<T> entry = byPath.get(key);
        if (entry == null) {
            entry = new Entry<>(key, loader.apply(key));
            byPath.put(key, entry);
            byResource.put(entry.resource, entry);
        }
        entry.refCount++;
        return entry.resource;
    }

    void release(T resource) {
        Entry<T> entry = byResource.get(resource);
        if (entry == null) {
            throw new IllegalArgumentException("Zwalniany zasób (" + kind + ") nie pochodzi z tego menedżera");
        }
        if (entry.refCount == 0) {
            throw new IllegalStateException("Nadmiarowe release() dla zasobu (" + kind + "): " + entry.path);
        }
        entry.refCount--;
    }

    /** Niszczy wszystkie zasoby z licznikiem 0. Zwraca liczbę zniszczonych. */
    int destroyUnused() {
        int destroyed = 0;
        Iterator<Entry<T>> it = byPath.values().iterator();
        while (it.hasNext()) {
            Entry<T> entry = it.next();
            if (entry.refCount == 0) {
                destroyer.accept(entry.resource);
                byResource.remove(entry.resource);
                it.remove();
                destroyed++;
            }
        }
        return destroyed;
    }

    /**
     * Niszczy wszystko (zamykanie silnika). Zasoby z licznikiem > 0 to
     * "wycieki" - ktoś zrobił acquire bez release. Niszczymy je mimo to,
     * ale zwracamy ich ścieżki, żeby dało się to zauważyć i poprawić.
     */
    List<String> destroyAll() {
        List<String> leaked = new ArrayList<>();
        for (Entry<T> entry : byPath.values()) {
            if (entry.refCount > 0) {
                leaked.add(entry.path + " (refCount = " + entry.refCount + ")");
            }
            destroyer.accept(entry.resource);
        }
        byPath.clear();
        byResource.clear();
        return leaked;
    }

    int refCount(String path) {
        Entry<T> entry = byPath.get(ResourcePaths.normalize(path));
        return entry != null ? entry.refCount : 0;
    }

    int size() {
        return byPath.size();
    }

    String kind() {
        return kind;
    }
}

package engine.scene;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Jeden obiekt sceny widoczny na ekranie (postać, skrzynia, budynek...),
 * złożony z jednego lub kilku meshy - każdy z własnym materiałem, bo różne
 * części obiektu mogą mieć różny wygląd (np. głowa i tors postaci). Wszystkie
 * części dzielą jedną transformację (pozycja, obrót, skala).
 *
 * Renderable nie jest właścicielem meshy ani materiałów - tylko do nich
 * odwołuje się, więc np. sto skrzyń może współdzielić ten sam Mesh i Material.
 * Zwolnienie zasobów GPU to zadanie tego, kto je utworzył (np. Engine).
 *
 * Interpolacja: symulacja zmienia position/rotation/scale w stałych krokach
 * (patrz engine.core.FixedTimestep), a ekran odświeża się w innym rytmie.
 * Dlatego obiekt pamięta też transformację sprzed ostatniego kroku, a
 * renderer rysuje stan pośredni ({@link #interpolatedModelMatrix}). Bez tego
 * przy 144 Hz i 60 krokach/s obiekt "stałby" przez 2-3 klatki i skakał.
 */
public final class Renderable {

    /** Jedna część obiektu: geometria + wygląd. */
    public record Part(Mesh mesh, Material material) {
    }

    private final String name;
    private final List<Part> parts = new ArrayList<>();

    public final Vector3f position = new Vector3f();
    public final Quaternionf rotation = new Quaternionf();
    public final Vector3f scale = new Vector3f(1.0f, 1.0f, 1.0f);

    /** Niewidoczne obiekty są pomijane przy rysowaniu. */
    public boolean visible = true;

    // Transformacja sprzed ostatniego kroku symulacji (do interpolacji) i bufory robocze.
    private final Vector3f previousPosition = new Vector3f();
    private final Quaternionf previousRotation = new Quaternionf();
    private final Vector3f previousScale = new Vector3f(1.0f, 1.0f, 1.0f);
    private final Vector3f tmpPosition = new Vector3f();
    private final Quaternionf tmpRotation = new Quaternionf();
    private final Vector3f tmpScale = new Vector3f();

    public Renderable(String name) {
        this.name = name;
    }

    public Renderable add(Mesh mesh, Material material) {
        parts.add(new Part(mesh, material));
        return this;
    }

    public String name() {
        return name;
    }

    public List<Part> parts() {
        return Collections.unmodifiableList(parts);
    }

    /** Macierz modelu (lokalne -> świat): skala, potem obrót, potem przesunięcie. */
    public Matrix4f modelMatrix(Matrix4f dest) {
        return dest.translation(position).rotate(rotation).scale(scale);
    }

    /**
     * Zapamiętuje bieżącą transformację jako "poprzednią". Wywoływać na
     * początku każdego kroku symulacji, PRZED zmianą position/rotation/scale.
     */
    public void savePreviousTransform() {
        previousPosition.set(position);
        previousRotation.set(rotation);
        previousScale.set(scale);
    }

    /**
     * Po teleportacji (np. ustawieniu obiektu w nowym miejscu poza symulacją)
     * wołamy to, żeby renderer nie interpolował "przelotu" ze starego miejsca.
     */
    public void resetInterpolation() {
        savePreviousTransform();
    }

    /**
     * Macierz modelu dla stanu pośredniego między poprzednim a bieżącym krokiem
     * symulacji: alpha = 0 -> poprzedni, alpha = 1 -> bieżący. Pozycja i skala
     * interpolowane liniowo (lerp), obrót sferycznie (slerp), żeby obiekt
     * obracał się ze stałą prędkością kątową.
     */
    public Matrix4f interpolatedModelMatrix(float alpha, Matrix4f dest) {
        previousPosition.lerp(position, alpha, tmpPosition);
        previousRotation.slerp(rotation, alpha, tmpRotation);
        previousScale.lerp(scale, alpha, tmpScale);
        return dest.translation(tmpPosition).rotate(tmpRotation).scale(tmpScale);
    }
}

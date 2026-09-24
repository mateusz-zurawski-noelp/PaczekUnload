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
}

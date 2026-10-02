package engine.scene;

import org.joml.Vector3f;

/**
 * Oświetlenie sceny - na razie najprostszy sensowny zestaw:
 *
 *  - jedno światło kierunkowe ("słońce"): wszystkie promienie równoległe,
 *    bez pozycji i bez zaniku z odległością. Ma tylko kierunek, w którym
 *    świeci, kolor i natężenie. Daje rozjaśnienie powierzchni zwróconych do
 *    światła (diffuse) i odblaski (specular).
 *
 *  - światło otoczenia półsferyczne (hemisphere ambient): zamiast jednego
 *    stałego koloru "ambient" powierzchnie zwrócone w górę dostają kolor
 *    nieba, a zwrócone w dół - kolor ziemi (odbite światło od gruntu). To
 *    tani trik, który bardzo poprawia wygląd stron obiektu w cieniu - nie są
 *    jednolicie płaskie. Te same dwa kolory służą jako bardzo uproszczone
 *    "otoczenie" do odbić (Material.reflectivity).
 *
 * Obiekt jest zmienny - można go animować w symulacji (np. obrót słońca),
 * a co klatkę trafia do uniform bufferu (patrz UniformBufferObject).
 * Wszystkie kolory są liniowe (nie sRGB) - shader liczy w przestrzeni
 * liniowej, a konwersję na sRGB robi swapchain.
 */
public final class SceneLighting {

    /** Kierunek, W KTÓRYM świeci słońce (od słońca do sceny), znormalizowany. */
    public final Vector3f sunDirection = new Vector3f(0.0f, 0.0f, -1.0f);
    public final Vector3f sunColor = new Vector3f(1.0f, 1.0f, 1.0f);
    public float sunIntensity = 1.0f;

    public final Vector3f skyColor = new Vector3f(0.2f, 0.2f, 0.2f);
    public final Vector3f groundColor = new Vector3f(0.1f, 0.1f, 0.1f);

    /** Oś "góra" świata - potrzebna, żeby wiedzieć, co jest niebem, a co ziemią. */
    public final Vector3f worldUp = new Vector3f(0.0f, 0.0f, 1.0f);

    /** Obraca kierunek słońca wokół osi "góra" (np. symulacja pory dnia). */
    public void rotateSun(float radians) {
        sunDirection.rotateAxis(radians, worldUp.x, worldUp.y, worldUp.z).normalize();
    }
}

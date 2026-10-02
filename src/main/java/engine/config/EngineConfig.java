package engine.config;

import org.joml.Vector3f;

/**
 * Konfiguracja silnika - wszystko, co wcześniej było stałymi zaszytymi
 * w Engine/SyncObjects/Camera. Rekordy odpowiadają 1:1 strukturze pliku
 * JSON (patrz resources/config/engine-defaults.json), a Gson tworzy je
 * przez kanoniczny konstruktor - więc walidacja w konstruktorach
 * kompaktowych wykonuje się przy każdym wczytaniu i zły plik kończy się
 * czytelnym błędem, a nie np. oknem 0x0 albo czarnym ekranem.
 *
 * Wczytywanie i łączenie z wartościami domyślnymi: {@link ConfigLoader}.
 */
public record EngineConfig(
        Window window,
        Graphics graphics,
        Simulation simulation,
        Camera camera,
        Controls controls,
        Lighting lighting,
        Ui ui,
        Shaders shaders,
        Scene scene) {

    public EngineConfig {
        require(window != null, "brak sekcji window");
        require(graphics != null, "brak sekcji graphics");
        require(simulation != null, "brak sekcji simulation");
        require(camera != null, "brak sekcji camera");
        require(controls != null, "brak sekcji controls");
        require(lighting != null, "brak sekcji lighting");
        require(ui != null, "brak sekcji ui");
        require(shaders != null, "brak sekcji shaders");
        require(scene != null, "brak sekcji scene");
    }

    public record Window(int width, int height, String title) {
        public Window {
            require(width > 0 && height > 0, "window.width i window.height muszą być > 0");
            require(title != null, "brak window.title");
        }
    }

    /**
     * @param vsync             true = FIFO (czeka na odświeżenie ekranu),
     *                          false = MAILBOX, jeśli dostępny (bez tearingu, bez czekania)
     * @param maxFramesInFlight ile klatek CPU może przygotować, zanim poczeka na GPU
     * @param validationLayers  czy włączyć VK_LAYER_KHRONOS_validation (o ile zainstalowana)
     */
    public record Graphics(boolean vsync, int maxFramesInFlight, boolean validationLayers) {
        public Graphics {
            require(maxFramesInFlight >= 1 && maxFramesInFlight <= 4, "graphics.maxFramesInFlight musi być w zakresie 1..4");
        }
    }

    /**
     * Pętla gry ze stałym krokiem (engine.core.FixedTimestep).
     *
     * @param updatesPerSecond ile kroków symulacji (fixedUpdate) na sekundę
     * @param maxFrameTime     najdłuższy czas klatki brany pod uwagę, w sekundach -
     *                         ochrona przed "spiralą śmierci" po długiej przerwie
     */
    public record Simulation(double updatesPerSecond, double maxFrameTime) {
        public Simulation {
            require(updatesPerSecond >= 1 && updatesPerSecond <= 1000, "simulation.updatesPerSecond musi być w zakresie 1..1000");
            require(maxFrameTime > 0 && maxFrameTime <= 1, "simulation.maxFrameTime musi być w zakresie (0, 1]");
        }
    }

    public record Camera(float[] position, float[] target, float[] up,
                         float fovDegrees, float nearPlane, float farPlane) {
        public Camera {
            requireVec3(position, "camera.position");
            requireVec3(target, "camera.target");
            requireVec3(up, "camera.up");
            require(fovDegrees >= 10 && fovDegrees <= 100, "camera.fovDegrees musi być w zakresie 10..100");
            require(nearPlane > 0 && farPlane > nearPlane, "wymagane 0 < camera.nearPlane < camera.farPlane");
            // Uwaga: w konstruktorze kompaktowym pola rekordu nie są jeszcze przypisane,
            // więc porównujemy parametry, a nie positionVec()/targetVec().
            require(!java.util.Arrays.equals(position, target), "camera.position i camera.target nie mogą być tym samym punktem");
        }

        public Vector3f positionVec() {
            return new Vector3f(position);
        }

        public Vector3f targetVec() {
            return new Vector3f(target);
        }

        public Vector3f upVec() {
            return new Vector3f(up);
        }
    }

    /**
     * Sterowanie kamerą swobodną (FlyCameraController).
     *
     * @param moveSpeed        prędkość ruchu w jednostkach świata na sekundę
     * @param fastMultiplier   mnożnik prędkości przy wciśniętym Shift
     * @param mouseSensitivity stopnie obrotu na piksel ruchu myszy
     * @param invertY          true = mysz do przodu obraca kamerę w dół (jak w symulatorach lotu)
     * @param zoomStepDegrees  zmiana pola widzenia na jeden "ząbek" kółka myszy
     */
    public record Controls(float moveSpeed, float fastMultiplier, float mouseSensitivity,
                           boolean invertY, float zoomStepDegrees) {
        public Controls {
            require(moveSpeed > 0, "controls.moveSpeed musi być > 0");
            require(fastMultiplier >= 1, "controls.fastMultiplier musi być >= 1");
            require(mouseSensitivity > 0, "controls.mouseSensitivity musi być > 0");
            require(zoomStepDegrees >= 0, "controls.zoomStepDegrees nie może być ujemne");
        }
    }

    /**
     * Oświetlenie sceny (engine.scene.SceneLighting). Kolory liniowe, 0..1
     * (większe wartości dozwolone - "jaśniej niż białe").
     *
     * @param sunDirection             kierunek, w którym świeci słońce (nie musi być znormalizowany)
     * @param sunColor                 kolor słońca
     * @param sunIntensity             mnożnik jasności słońca
     * @param skyColor                 światło otoczenia padające z góry
     * @param groundColor              światło otoczenia odbite od ziemi (z dołu)
     * @param sunOrbitDegreesPerSecond obrót słońca wokół osi "góra" (0 = słońce stoi)
     */
    public record Lighting(float[] sunDirection, float[] sunColor, float sunIntensity,
                           float[] skyColor, float[] groundColor, float sunOrbitDegreesPerSecond) {
        public Lighting {
            requireVec3(sunDirection, "lighting.sunDirection");
            require(new Vector3f(sunDirection).lengthSquared() > 0, "lighting.sunDirection nie może być wektorem zerowym");
            requireColor(sunColor, "lighting.sunColor");
            requireColor(skyColor, "lighting.skyColor");
            requireColor(groundColor, "lighting.groundColor");
            require(sunIntensity >= 0, "lighting.sunIntensity nie może być ujemne");
        }
    }

    /**
     * Nakładka 2D (engine.ui).
     *
     * @param font     plik czcionki .ttf (classpath albo dysk)
     * @param fontSize wysokość czcionki w pikselach
     */
    public record Ui(String font, float fontSize) {
        public Ui {
            requirePath(font, "ui.font");
            // Górna granica: przy większym rozmiarze znaki nie mieszczą się w atlasie 1024x1024 (BakedFont).
            require(fontSize >= 6 && fontSize <= 48, "ui.fontSize musi być w zakresie 6..48");
        }
    }

    public record Shaders(String modelVertex, String modelFragment, String overlayVertex, String overlayFragment) {
        public Shaders {
            requirePath(modelVertex, "shaders.modelVertex");
            requirePath(modelFragment, "shaders.modelFragment");
            requirePath(overlayVertex, "shaders.overlayVertex");
            requirePath(overlayFragment, "shaders.overlayFragment");
        }
    }

    public record Scene(String model, String texture, float rotationDegreesPerSecond) {
        public Scene {
            requirePath(model, "scene.model");
            requirePath(texture, "scene.texture");
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new ConfigException(message);
        }
    }

    private static void requireVec3(float[] v, String key) {
        require(v != null && v.length == 3, key + " musi być tablicą 3 liczb, np. [0.0, 0.0, 1.0]");
    }

    private static void requireColor(float[] c, String key) {
        require(c != null && c.length == 3 && c[0] >= 0 && c[1] >= 0 && c[2] >= 0,
                key + " musi być tablicą 3 nieujemnych liczb (r, g, b), np. [1.0, 0.95, 0.9]");
    }

    private static void requirePath(String path, String key) {
        require(path != null && !path.isBlank(), "brak ścieżki " + key);
    }
}

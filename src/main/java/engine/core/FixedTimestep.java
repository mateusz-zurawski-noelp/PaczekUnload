package engine.core;

/**
 * Stały krok czasowy z akumulatorem (Gregory, "The Game Loop and Real-Time
 * Simulation"; klasyczny opis: Glenn Fiedler, "Fix Your Timestep!").
 *
 * Problem: gdy symulacja (fizyka, animacja, logika) dostaje zmienny
 * deltaTime prosto z pętli renderowania, jej wynik zależy od FPS - przy 30
 * FPS obiekt przeleci przez ścianę, przy 300 nie; całkowanie numeryczne daje
 * inne trajektorie przy różnych krokach.
 *
 * Rozwiązanie: rzeczywisty czas klatki trafia do "akumulatora", z którego
 * symulacja odbiera porcje o STAŁEJ długości (np. 1/60 s). Przy szybkim
 * renderowaniu w niektórych klatkach nie wykona się żaden krok, przy wolnym -
 * kilka. Reszta, która nie wystarczyła na pełny krok, zostaje na następną
 * klatkę, a {@link #alpha()} mówi, jak daleko jesteśmy między ostatnim a
 * następnym krokiem - renderer interpoluje stan o ten ułamek, żeby ruch był
 * płynny mimo że symulacja "tyka" rzadziej (lub częściej) niż ekran.
 *
 * Użycie w pętli:
 *
 *   double frameTime = timestep.beginFrame(glfwGetTime());
 *   while (timestep.consumeStep()) {
 *       fixedUpdate(timestep.stepSeconds());
 *   }
 *   render(timestep.alpha());
 */
public final class FixedTimestep {

    private final double step;
    private final double maxFrameTime;

    private double lastTime = Double.NaN;
    private double accumulator;
    private long totalSteps;

    /**
     * @param updatesPerSecond ile kroków symulacji na sekundę (np. 60)
     * @param maxFrameTime     najdłuższy czas klatki brany pod uwagę (sekundy).
     *                         Chroni przed "spiralą śmierci": gdy jedna klatka
     *                         trwa długo (breakpoint, przeciąganie okna, lag), bez
     *                         limitu trzeba by nadrobić setki kroków, co wydłuża
     *                         kolejną klatkę, która wymaga jeszcze więcej kroków...
     *                         Z limitem gra po prostu na chwilę "zwalnia".
     */
    public FixedTimestep(double updatesPerSecond, double maxFrameTime) {
        if (updatesPerSecond <= 0 || maxFrameTime <= 0) {
            throw new IllegalArgumentException("updatesPerSecond i maxFrameTime muszą być > 0");
        }
        this.step = 1.0 / updatesPerSecond;
        this.maxFrameTime = maxFrameTime;
    }

    /**
     * Rozpoczyna klatkę: dolicza do akumulatora czas od poprzedniej klatki
     * (przycięty do maxFrameTime) i zwraca go - to deltaTime dla rzeczy
     * aktualizowanych raz na klatkę (np. kamera sterowana myszą).
     *
     * @param now bieżący czas w sekundach (np. glfwGetTime())
     */
    public double beginFrame(double now) {
        double frameTime = Double.isNaN(lastTime) ? 0.0 : Math.min(now - lastTime, maxFrameTime);
        lastTime = now;
        accumulator += frameTime;
        return frameTime;
    }

    /** Jeśli w akumulatorze jest czas na pełny krok - odbiera go i zwraca true. */
    public boolean consumeStep() {
        if (accumulator >= step) {
            accumulator -= step;
            totalSteps++;
            return true;
        }
        return false;
    }

    /** Długość jednego kroku symulacji w sekundach. */
    public float stepSeconds() {
        return (float) step;
    }

    /**
     * Ułamek kroku, który został w akumulatorze (0..1): 0 = dokładnie stan
     * z ostatniego kroku, bliżej 1 = prawie czas na następny. Renderer
     * rysuje stan interpolowany: poprzedni + (bieżący - poprzedni) * alpha.
     */
    public float alpha() {
        return (float) (accumulator / step);
    }

    /** Liczba wykonanych kroków od startu - czas symulacji to totalSteps * stepSeconds. */
    public long totalSteps() {
        return totalSteps;
    }
}

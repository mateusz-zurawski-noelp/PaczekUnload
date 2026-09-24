package engine.scene;

import static org.lwjgl.glfw.GLFW.glfwGetTime;

/**
 * Liczy klatki na sekundę. Uśrednia w oknach czasowych zamiast pokazywać
 * "1 / czas ostatniej klatki" - ta druga metoda potrafi szaleńczo skakać
 * (jedna klatka wolniejsza od reszty o 1ms potrafi zmienić odczyt o kilka FPS).
 */
public final class FpsCounter {

    private static final double UPDATE_INTERVAL_SECONDS = 0.5;

    private double windowStart = glfwGetTime();
    private int framesInWindow;
    private int fps;

    /** Wywoływać raz na każdą narysowaną klatkę. */
    public void onFrameRendered() {
        framesInWindow++;

        double now = glfwGetTime();
        double elapsed = now - windowStart;

        if (elapsed >= UPDATE_INTERVAL_SECONDS) {
            fps = (int) Math.round(framesInWindow / elapsed);
            framesInWindow = 0;
            windowStart = now;
        }
    }

    public int fps() {
        return fps;
    }
}

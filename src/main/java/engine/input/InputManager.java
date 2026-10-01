package engine.input;

import engine.core.Window;
import org.joml.Vector2d;
import org.joml.Vector2dc;

import java.util.Arrays;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Podsystem wejścia (Gregory, "Human Interface Devices"). GLFW zgłasza
 * zdarzenia przez callbacki w trakcie {@link Window#pollEvents()}, a kod gry
 * chce zwykle zapytać "czy W jest wciśnięte?" albo "o ile ruszyła się mysz
 * od ostatniej klatki?". InputManager tłumaczy jedno na drugie: callbacki
 * tylko zapisują stan, a gra go odpytuje.
 *
 * Kolejność w pętli głównej:
 *
 *   input.beginFrame();   // zeruje zdarzenia "tej klatki" (wciśnięcia, delty)
 *   Window.pollEvents();  // GLFW woła callbacki -> stan się aktualizuje
 *   ... logika gry odpytuje input ...
 *
 * Rozróżniamy stan ciągły ({@link #isKeyDown}) i zbocza ({@link #wasKeyPressed},
 * {@link #wasKeyReleased}) - te drugie są prawdziwe tylko w jednej klatce,
 * więc nadają się do akcji "raz na wciśnięcie" (np. reset kamery), a nie do
 * ruchu. Kody klawiszy i przycisków to stałe GLFW (GLFW_KEY_W, GLFW_MOUSE_BUTTON_RIGHT...).
 */
public final class InputManager {

    private final long window;

    private final boolean[] keysDown = new boolean[GLFW_KEY_LAST + 1];
    private final boolean[] keysPressed = new boolean[GLFW_KEY_LAST + 1];
    private final boolean[] keysReleased = new boolean[GLFW_KEY_LAST + 1];

    private final boolean[] buttonsDown = new boolean[GLFW_MOUSE_BUTTON_LAST + 1];
    private final boolean[] buttonsPressed = new boolean[GLFW_MOUSE_BUTTON_LAST + 1];
    private final boolean[] buttonsReleased = new boolean[GLFW_MOUSE_BUTTON_LAST + 1];

    private final Vector2d cursor = new Vector2d();
    private final Vector2d mouseDelta = new Vector2d();
    private final Vector2d scrollDelta = new Vector2d();

    /** Po zmianie trybu kursora GLFW potrafi "przeskoczyć" pozycją - pierwszą deltę pomijamy. */
    private boolean skipNextCursorDelta = true;
    private boolean cursorCaptured;

    public InputManager(Window window) {
        this.window = window.handle();

        glfwSetKeyCallback(this.window, (w, key, scancode, action, mods) -> onKey(key, action));
        glfwSetMouseButtonCallback(this.window, (w, button, action, mods) -> onMouseButton(button, action));
        glfwSetCursorPosCallback(this.window, (w, x, y) -> onCursorPos(x, y));
        glfwSetScrollCallback(this.window, (w, dx, dy) -> scrollDelta.add(dx, dy));
    }

    /** Wywołać raz na klatkę, PRZED Window.pollEvents(). */
    public void beginFrame() {
        Arrays.fill(keysPressed, false);
        Arrays.fill(keysReleased, false);
        Arrays.fill(buttonsPressed, false);
        Arrays.fill(buttonsReleased, false);
        mouseDelta.zero();
        scrollDelta.zero();
    }

    // ===== callbacki GLFW ===== //

    private void onKey(int key, int action) {
        if (key < 0 || key > GLFW_KEY_LAST) {
            return; // GLFW_KEY_UNKNOWN - np. klawisze multimedialne bez przypisanego kodu
        }
        if (action == GLFW_PRESS) {
            keysDown[key] = true;
            keysPressed[key] = true;
        } else if (action == GLFW_RELEASE) {
            keysDown[key] = false;
            keysReleased[key] = true;
        }
        // GLFW_REPEAT (autopowtarzanie przy przytrzymaniu) ignorujemy - stan "wciśnięty" już jest.
    }

    private void onMouseButton(int button, int action) {
        if (button < 0 || button > GLFW_MOUSE_BUTTON_LAST) {
            return;
        }
        if (action == GLFW_PRESS) {
            buttonsDown[button] = true;
            buttonsPressed[button] = true;
        } else if (action == GLFW_RELEASE) {
            buttonsDown[button] = false;
            buttonsReleased[button] = true;
        }
    }

    private void onCursorPos(double x, double y) {
        if (skipNextCursorDelta) {
            skipNextCursorDelta = false;
        } else {
            mouseDelta.add(x - cursor.x, y - cursor.y);
        }
        cursor.set(x, y);
    }

    // ===== klawiatura ===== //

    /** Czy klawisz jest w tej chwili przytrzymany. */
    public boolean isKeyDown(int key) {
        return keysDown[key];
    }

    /** Czy klawisz został wciśnięty w tej klatce (prawdziwe tylko przez jedną klatkę). */
    public boolean wasKeyPressed(int key) {
        return keysPressed[key];
    }

    /** Czy klawisz został puszczony w tej klatce. */
    public boolean wasKeyReleased(int key) {
        return keysReleased[key];
    }

    // ===== mysz ===== //

    public boolean isMouseButtonDown(int button) {
        return buttonsDown[button];
    }

    public boolean wasMouseButtonPressed(int button) {
        return buttonsPressed[button];
    }

    public boolean wasMouseButtonReleased(int button) {
        return buttonsReleased[button];
    }

    /** Pozycja kursora w pikselach okna (lewy górny róg = 0,0; y rośnie w dół). */
    public Vector2dc cursorPosition() {
        return cursor;
    }

    /** Przesunięcie myszy od początku klatki, w pikselach (y dodatnie = w dół). */
    public Vector2dc mouseDelta() {
        return mouseDelta;
    }

    /** Obrót kółka od początku klatki (y dodatnie = od siebie). */
    public Vector2dc scrollDelta() {
        return scrollDelta;
    }

    /**
     * Przechwycony kursor jest ukryty i nie wychodzi poza okno, a mysz daje
     * nieograniczone delty - tryb do rozglądania się kamerą. Jeśli system to
     * obsługuje, włączamy też "surowy" ruch myszy (bez akceleracji systemowej).
     */
    public void setCursorCaptured(boolean captured) {
        if (captured == cursorCaptured) {
            return;
        }
        cursorCaptured = captured;
        glfwSetInputMode(window, GLFW_CURSOR, captured ? GLFW_CURSOR_DISABLED : GLFW_CURSOR_NORMAL);
        if (glfwRawMouseMotionSupported()) {
            glfwSetInputMode(window, GLFW_RAW_MOUSE_MOTION, captured ? GLFW_TRUE : GLFW_FALSE);
        }
        skipNextCursorDelta = true;
    }

    public boolean isCursorCaptured() {
        return cursorCaptured;
    }
}

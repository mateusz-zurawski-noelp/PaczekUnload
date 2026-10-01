package engine.scene;

import engine.config.EngineConfig;
import engine.input.InputManager;
import org.joml.Vector2dc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Sterowanie kamerą w stylu edytorów (Unity/Unreal):
 *
 *   prawy przycisk myszy (przytrzymany) - rozglądanie się; kursor jest wtedy ukryty
 *   W / S, A / D                        - przód / tył, lewo / prawo
 *   E / Q  (albo Spacja / Ctrl)          - w górę / w dół (wzdłuż osi "góra" świata)
 *   Shift                               - szybszy ruch
 *   kółko myszy                         - zoom (pole widzenia)
 *   R                                   - powrót do pozycji startowej z konfiguracji
 *
 * Kontroler nie wie nic o GLFW poza kodami klawiszy - cały stan wejścia
 * bierze z InputManagera, a kamerę zmienia tylko przez jej publiczne API.
 * Ruch jest mnożony przez deltaTime, więc prędkość nie zależy od FPS.
 */
public final class FlyCameraController {

    private final Camera camera;
    private final InputManager input;
    private final EngineConfig.Controls controls;

    private final Vector3fc startPosition;
    private final Vector3fc startTarget;
    private final float startFovDegrees;

    public FlyCameraController(Camera camera, InputManager input, EngineConfig.Controls controls) {
        this.camera = camera;
        this.input = input;
        this.controls = controls;
        this.startPosition = new Vector3f(camera.position());
        this.startTarget = new Vector3f(camera.position()).add(camera.forward());
        this.startFovDegrees = camera.fovDegrees();
    }

    public void update(float deltaTime) {
        updateLook();
        updateMovement(deltaTime);
        updateZoom();

        if (input.wasKeyPressed(GLFW_KEY_R)) {
            camera.lookAt(startPosition, startTarget);
            camera.zoom(startFovDegrees - camera.fovDegrees());
        }
    }

    private void updateLook() {
        boolean looking = input.isMouseButtonDown(GLFW_MOUSE_BUTTON_RIGHT);
        input.setCursorCaptured(looking);
        if (!looking) {
            return;
        }

        Vector2dc delta = input.mouseDelta();
        float radiansPerPixel = (float) Math.toRadians(controls.mouseSensitivity());
        float yaw = (float) -delta.x() * radiansPerPixel;   // mysz w prawo -> obrót w prawo
        float pitch = (float) -delta.y() * radiansPerPixel; // mysz do przodu (y maleje) -> patrz w górę
        if (controls.invertY()) {
            pitch = -pitch;
        }
        camera.rotate(yaw, pitch);
    }

    private void updateMovement(float deltaTime) {
        float forward = axis(GLFW_KEY_W, GLFW_KEY_S);
        float right = axis(GLFW_KEY_D, GLFW_KEY_A);
        float up = Math.clamp(axis(GLFW_KEY_E, GLFW_KEY_Q) + axis(GLFW_KEY_SPACE, GLFW_KEY_LEFT_CONTROL), -1, 1);
        if (forward == 0 && right == 0 && up == 0) {
            return;
        }

        float speed = controls.moveSpeed();
        if (input.isKeyDown(GLFW_KEY_LEFT_SHIFT) || input.isKeyDown(GLFW_KEY_RIGHT_SHIFT)) {
            speed *= controls.fastMultiplier();
        }

        // Normalizacja: po skosie (W + D) nie poruszamy się szybciej niż na wprost.
        Vector3f direction = new Vector3f(forward, right, up).normalize();
        float distance = speed * deltaTime;
        camera.move(direction.x * distance, direction.y * distance, direction.z * distance);
    }

    private void updateZoom() {
        double scroll = input.scrollDelta().y();
        if (scroll != 0) {
            camera.zoom((float) -scroll * controls.zoomStepDegrees()); // kółko od siebie = przybliżenie
        }
    }

    /** +1 gdy wciśnięty tylko positive, -1 gdy tylko negative, 0 gdy żaden albo oba. */
    private float axis(int positiveKey, int negativeKey) {
        float value = 0;
        if (input.isKeyDown(positiveKey)) {
            value += 1;
        }
        if (input.isKeyDown(negativeKey)) {
            value -= 1;
        }
        return value;
    }
}

package engine.scene;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Kamera "swobodna" (free-fly): pozycja + kierunek patrzenia (forward),
 * obracana w poziomie wokół osi "góra" świata (yaw) i w pionie wokół
 * własnej osi "w prawo" (pitch). Oś "góra" jest dowolna - w tej scenie to Z,
 * bo tak jest zorientowany model domku.
 *
 * Nie trzymamy kątów yaw/pitch, tylko sam wektor forward - dzięki temu
 * kamerę da się ustawić przez {@link #lookAt} bez przeliczania kątów, a
 * ograniczenie pitch (żeby nie "przewrócić się" przez biegun) liczymy z kąta
 * między forward a osią góra.
 *
 * Macierze widoku/projekcji liczone są na żądanie - w silniku produkcyjnym
 * cache'owałoby się je i przeliczało tylko gdy kamera faktycznie się poruszy.
 */
public final class Camera {

    /** Najmniejszy kąt między kierunkiem patrzenia a osią góra/dół (~1 stopień). */
    private static final float MIN_ANGLE_TO_POLE = (float) Math.toRadians(1.0);

    private static final float MIN_FOV_DEGREES = 10.0f;
    private static final float MAX_FOV_DEGREES = 100.0f;

    private final Vector3f position = new Vector3f();
    private final Vector3f forward = new Vector3f();
    private final Vector3f worldUp = new Vector3f();

    private float fovDegrees = 45.0f;
    private float nearPlane = 0.1f;
    private float farPlane = 10.0f;

    public Camera(Vector3fc position, Vector3fc target, Vector3fc worldUp) {
        this.worldUp.set(worldUp).normalize();
        lookAt(position, target);
    }

    public void setPerspective(float fovDegrees, float nearPlane, float farPlane) {
        this.fovDegrees = fovDegrees;
        this.nearPlane = nearPlane;
        this.farPlane = farPlane;
    }

    /** Ustawia kamerę w punkcie position, patrzącą na target. */
    public void lookAt(Vector3fc position, Vector3fc target) {
        Vector3f direction = new Vector3f(target).sub(position);
        if (direction.lengthSquared() == 0.0f) {
            throw new IllegalArgumentException("Pozycja kamery i punkt, na który patrzy, nie mogą być tym samym punktem");
        }
        direction.normalize();
        float angle = direction.angle(worldUp);
        if (angle < MIN_ANGLE_TO_POLE || angle > Math.PI - MIN_ANGLE_TO_POLE) {
            throw new IllegalArgumentException("Kamera nie może patrzeć dokładnie wzdłuż osi \"góra\" " + worldUp);
        }
        this.position.set(position);
        this.forward.set(direction);
    }

    /**
     * Obrót kamery.
     *
     * @param yawRadians   dodatni = w lewo (przeciwnie do wskazówek zegara patrząc "z góry")
     * @param pitchRadians dodatni = w górę; przycinany tak, żeby nie przejść przez pion
     */
    public void rotate(float yawRadians, float pitchRadians) {
        forward.rotateAxis(yawRadians, worldUp.x, worldUp.y, worldUp.z);

        // Kąt do osi "góra" maleje przy patrzeniu w górę - nie pozwalamy mu zejść
        // poniżej MIN_ANGLE_TO_POLE ani przekroczyć 180 stopni minus ten margines.
        float angleToUp = forward.angle(worldUp);
        float maxUp = angleToUp - MIN_ANGLE_TO_POLE;
        float maxDown = (float) Math.PI - MIN_ANGLE_TO_POLE - angleToUp;
        float pitch = Math.clamp(pitchRadians, -maxDown, maxUp);

        Vector3f right = right(new Vector3f());
        forward.rotateAxis(pitch, right.x, right.y, right.z).normalize();
    }

    /**
     * Przesunięcie w układzie kamery: forward wzdłuż kierunku patrzenia,
     * right w bok, up wzdłuż osi "góra" świata (jak winda, niezależnie od pitch).
     */
    public void move(float forwardAmount, float rightAmount, float upAmount) {
        Vector3f right = right(new Vector3f());
        position.fma(forwardAmount, forward)
                .fma(rightAmount, right)
                .fma(upAmount, worldUp);
    }

    /** Zmiana pola widzenia (zoom); dodatnia delta = szerszy kąt. */
    public void zoom(float deltaDegrees) {
        fovDegrees = Math.clamp(fovDegrees + deltaDegrees, MIN_FOV_DEGREES, MAX_FOV_DEGREES);
    }

    public Vector3fc position() {
        return position;
    }

    public Vector3fc forward() {
        return forward;
    }

    public float fovDegrees() {
        return fovDegrees;
    }

    /** Wektor "w prawo" = forward x góra (układ prawoskrętny). */
    private Vector3f right(Vector3f dest) {
        return forward.cross(worldUp, dest).normalize();
    }

    public Matrix4f viewMatrix() {
        Vector3f center = new Vector3f(position).add(forward);
        return new Matrix4f().lookAt(position, center, worldUp);
    }

    /**
     * Vulkan ma odwrócony (w porównaniu do OpenGL) kierunek osi Y w przestrzeni
     * clip space, dlatego trzeba odwrócić element m11 macierzy projekcji -
     * inaczej obraz wyjdzie postawiony "do góry nogami".
     */
    public Matrix4f projectionMatrix(float aspectRatio) {
        Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(fovDegrees), aspectRatio, nearPlane, farPlane);
        proj.m11(proj.m11() * -1);
        return proj;
    }
}

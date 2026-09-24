package engine.scene;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Prosta kamera "patrz na punkt" (lookAt). Trzyma pozycję i cel, a macierze
 * widoku/projekcji liczy na żądanie - w silniku produkcyjnym cache'owałoby
 * się je i przeliczało tylko gdy kamera faktycznie się poruszy.
 */
public final class Camera {

    private final Vector3f position;
    private final Vector3f target;
    private final Vector3f up;

    private float fovDegrees = 45.0f;
    private float nearPlane = 0.1f;
    private float farPlane = 10.0f;

    public Camera(Vector3f position, Vector3f target, Vector3f up) {
        this.position = position;
        this.target = target;
        this.up = up;
    }

    public Matrix4f viewMatrix() {
        return new Matrix4f().lookAt(position, target, up);
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

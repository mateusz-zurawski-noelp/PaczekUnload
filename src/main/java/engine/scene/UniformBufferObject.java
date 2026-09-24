package engine.scene;

import org.joml.Matrix4f;

/**
 * Dane wspólne dla całej klatki (kamera), wysyłane do shadera wierzchołków
 * przez uniform buffer - odpowiednik glUniformMatrix4fv z OpenGL, tylko że tu
 * sami zarządzamy pamięcią (patrz UniformBuffers). Macierz modelu jest
 * per obiekt, więc idzie osobno przez push constants (patrz Renderable).
 * Layout w GLSL to:
 *
 *   layout(set = 0, binding = 0) uniform UniformBufferObject {
 *       mat4 view; mat4 proj;
 *   } ubo;
 */
public final class UniformBufferObject {

    public static final int SIZEOF = 2 * 16 * Float.BYTES;

    public final Matrix4f view = new Matrix4f();
    public final Matrix4f proj = new Matrix4f();
}

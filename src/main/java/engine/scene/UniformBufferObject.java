package engine.scene;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.nio.ByteBuffer;

/**
 * Dane wspólne dla całej klatki (kamera + oświetlenie), wysyłane do
 * shaderów przez uniform buffer - odpowiednik glUniform* z OpenGL, tylko że
 * tu sami zarządzamy pamięcią (patrz UniformBuffers). Macierz modelu jest
 * per obiekt, więc idzie osobno przez push constants (patrz Renderable).
 *
 * Layout w GLSL (std140 - każdy vec3 zajmuje pełne 16 bajtów jak vec4, więc
 * pakujemy dane w vec4 i używamy czwartej składowej albo zostawiamy ją pustą):
 *
 *   layout(set = 0, binding = 0) uniform FrameUBO {
 *       mat4 view;
 *       mat4 proj;
 *       vec4 cameraPosition;   // xyz = pozycja kamery w świecie
 *       vec4 sunDirection;     // xyz = kierunek, w którym świeci słońce
 *       vec4 sunColor;         // rgb = kolor * natężenie
 *       vec4 skyColor;         // rgb = światło otoczenia z góry
 *       vec4 groundColor;      // rgb = światło otoczenia z dołu
 *       vec4 worldUp;          // xyz = oś "góra" świata
 *   } frame;
 */
public final class UniformBufferObject {

    public static final int SIZEOF = 2 * 16 * Float.BYTES + 6 * 4 * Float.BYTES;

    public final Matrix4f view = new Matrix4f();
    public final Matrix4f proj = new Matrix4f();
    public final Vector3f cameraPosition = new Vector3f();
    public final SceneLighting lighting;

    public UniformBufferObject(SceneLighting lighting) {
        this.lighting = lighting;
    }

    /** Zapisuje dane w układzie std140 opisanym wyżej, od pozycji 0 bufora. */
    public void write(ByteBuffer target) {
        view.get(0, target);
        proj.get(16 * Float.BYTES, target);

        int offset = 32 * Float.BYTES;
        offset = putVec4(target, offset, cameraPosition, 0.0f);
        offset = putVec4(target, offset, lighting.sunDirection, 0.0f);
        Vector3f sun = new Vector3f(lighting.sunColor).mul(lighting.sunIntensity);
        offset = putVec4(target, offset, sun, 0.0f);
        offset = putVec4(target, offset, lighting.skyColor, 0.0f);
        offset = putVec4(target, offset, lighting.groundColor, 0.0f);
        putVec4(target, offset, lighting.worldUp, 0.0f);
    }

    private static int putVec4(ByteBuffer target, int offset, Vector3f xyz, float w) {
        target.putFloat(offset, xyz.x)
                .putFloat(offset + 4, xyz.y)
                .putFloat(offset + 8, xyz.z)
                .putFloat(offset + 12, w);
        return offset + 16;
    }
}

package engine.scene;

import org.joml.Vector2fc;
import org.joml.Vector3fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Jeden wierzchołek siatki: pozycja, kolor (tu zawsze biały - służy do
 * ewentualnego tintowania tekstury), współrzędne UV tekstury i normalna
 * (wektor prostopadły do powierzchni - bez niej nie da się policzyć, pod
 * jakim kątem pada światło, patrz model.frag).
 *
 * getBindingDescription/getAttributeDescriptions mówią Vulkanowi, jak
 * odczytać te dane z bufora wierzchołków - to programowy odpowiednik
 * glVertexAttribPointer z OpenGL, tyle że skonfigurowany raz przy tworzeniu
 * pipeline'u, a nie co klatkę.
 */
public final class Vertex {

    public static final int SIZEOF = (3 + 3 + 2 + 3) * Float.BYTES;
    private static final int OFFSETOF_POS = 0;
    private static final int OFFSETOF_COLOR = 3 * Float.BYTES;
    private static final int OFFSETOF_TEXCOORD = (3 + 3) * Float.BYTES;
    private static final int OFFSETOF_NORMAL = (3 + 3 + 2) * Float.BYTES;

    public final Vector3fc pos;
    public final Vector3fc color;
    public final Vector2fc texCoords;
    public final Vector3fc normal;

    public Vertex(Vector3fc pos, Vector3fc color, Vector2fc texCoords, Vector3fc normal) {
        this.pos = pos;
        this.color = color;
        this.texCoords = texCoords;
        this.normal = normal;
    }

    public static VkVertexInputBindingDescription.Buffer bindingDescription(MemoryStack stack) {

        VkVertexInputBindingDescription.Buffer bindingDescription = VkVertexInputBindingDescription.calloc(1, stack);
        bindingDescription.binding(0);
        bindingDescription.stride(SIZEOF);
        bindingDescription.inputRate(VK_VERTEX_INPUT_RATE_VERTEX);

        return bindingDescription;
    }

    public static VkVertexInputAttributeDescription.Buffer attributeDescriptions(MemoryStack stack) {

        VkVertexInputAttributeDescription.Buffer attributeDescriptions =
                VkVertexInputAttributeDescription.calloc(4, stack);

        VkVertexInputAttributeDescription position = attributeDescriptions.get(0);
        position.binding(0);
        position.location(0);
        position.format(VK_FORMAT_R32G32B32_SFLOAT);
        position.offset(OFFSETOF_POS);

        VkVertexInputAttributeDescription color = attributeDescriptions.get(1);
        color.binding(0);
        color.location(1);
        color.format(VK_FORMAT_R32G32B32_SFLOAT);
        color.offset(OFFSETOF_COLOR);

        VkVertexInputAttributeDescription texCoord = attributeDescriptions.get(2);
        texCoord.binding(0);
        texCoord.location(2);
        texCoord.format(VK_FORMAT_R32G32_SFLOAT);
        texCoord.offset(OFFSETOF_TEXCOORD);

        VkVertexInputAttributeDescription normal = attributeDescriptions.get(3);
        normal.binding(0);
        normal.location(3);
        normal.format(VK_FORMAT_R32G32B32_SFLOAT);
        normal.offset(OFFSETOF_NORMAL);

        return attributeDescriptions.rewind();
    }
}

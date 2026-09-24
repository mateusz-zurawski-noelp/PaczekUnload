package engine.scene;

import org.joml.Vector2fc;
import org.joml.Vector3fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Wierzchołek prostego nakładkowego (2D, ekranowego) rysowania - np. licznika
 * FPS. W przeciwieństwie do {@link Vertex}, pozycja jest podawana od razu we
 * współrzędnych NDC (-1..1), bez przechodzenia przez macierze model/view/proj
 * - shader nakładki (overlay.vert) po prostu przepisuje ją do gl_Position.
 */
public final class OverlayVertex {

    public static final int SIZEOF = (2 + 3) * Float.BYTES;
    private static final int OFFSETOF_POS = 0;
    private static final int OFFSETOF_COLOR = 2 * Float.BYTES;

    public final Vector2fc pos;
    public final Vector3fc color;

    public OverlayVertex(Vector2fc pos, Vector3fc color) {
        this.pos = pos;
        this.color = color;
    }

    public static VkVertexInputBindingDescription.Buffer bindingDescription(MemoryStack stack) {
        VkVertexInputBindingDescription.Buffer binding = VkVertexInputBindingDescription.calloc(1, stack);
        binding.binding(0);
        binding.stride(SIZEOF);
        binding.inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
        return binding;
    }

    public static VkVertexInputAttributeDescription.Buffer attributeDescriptions(MemoryStack stack) {
        VkVertexInputAttributeDescription.Buffer attributes = VkVertexInputAttributeDescription.calloc(2, stack);

        VkVertexInputAttributeDescription position = attributes.get(0);
        position.binding(0);
        position.location(0);
        position.format(VK_FORMAT_R32G32_SFLOAT);
        position.offset(OFFSETOF_POS);

        VkVertexInputAttributeDescription color = attributes.get(1);
        color.binding(0);
        color.location(1);
        color.format(VK_FORMAT_R32G32B32_SFLOAT);
        color.offset(OFFSETOF_COLOR);

        return attributes.rewind();
    }
}

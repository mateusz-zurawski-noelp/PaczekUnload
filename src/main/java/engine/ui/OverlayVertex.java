package engine.ui;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Układ wierzchołka nakładki 2D (tekst, tła paneli). W przeciwieństwie do
 * {@link engine.scene.Vertex} pozycja jest podawana od razu we
 * współrzędnych NDC (-1..1), bez macierzy - shader overlay.vert przepisuje
 * ją do gl_Position.
 *
 *   location 0: vec2 pos    - pozycja w NDC
 *   location 1: vec2 uv     - współrzędne w atlasie czcionki
 *   location 2: vec4 color  - kolor RGBA (alfa = przezroczystość)
 *
 * Klasa opisuje tylko układ danych - same wierzchołki {@link TextBatch}
 * zapisuje prosto do tablicy float, bez tworzenia obiektu na każdy
 * wierzchołek (konsola z kilkudziesięcioma liniami tekstu to dziesiątki
 * tysięcy wierzchołków co klatkę).
 */
public final class OverlayVertex {

    public static final int FLOATS = 2 + 2 + 4;
    public static final int SIZEOF = FLOATS * Float.BYTES;
    private static final int OFFSETOF_POS = 0;
    private static final int OFFSETOF_UV = 2 * Float.BYTES;
    private static final int OFFSETOF_COLOR = 4 * Float.BYTES;

    private OverlayVertex() {
    }

    public static VkVertexInputBindingDescription.Buffer bindingDescription(MemoryStack stack) {
        VkVertexInputBindingDescription.Buffer binding = VkVertexInputBindingDescription.calloc(1, stack);
        binding.binding(0);
        binding.stride(SIZEOF);
        binding.inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
        return binding;
    }

    public static VkVertexInputAttributeDescription.Buffer attributeDescriptions(MemoryStack stack) {
        VkVertexInputAttributeDescription.Buffer attributes = VkVertexInputAttributeDescription.calloc(3, stack);

        attributes.get(0).binding(0).location(0).format(VK_FORMAT_R32G32_SFLOAT).offset(OFFSETOF_POS);
        attributes.get(1).binding(0).location(1).format(VK_FORMAT_R32G32_SFLOAT).offset(OFFSETOF_UV);
        attributes.get(2).binding(0).location(2).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(OFFSETOF_COLOR);

        return attributes;
    }
}

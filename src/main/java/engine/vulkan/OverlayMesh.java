package engine.vulkan;

import engine.ui.OverlayVertex;
import engine.ui.TextBatch;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Bufory wierzchołków nakładki 2D (tekst, tła paneli), których zawartość
 * zmienia się co klatkę - w przeciwieństwie do {@link engine.scene.Mesh}
 * nie ma sensu robić kosztownego stagingu przez pamięć DEVICE_LOCAL, bo i
 * tak nadpisujemy dane co klatkę. Bufory są więc "host visible" i
 * zmapowane na stałe (persistent mapping) - update() to zwykłe kopiowanie
 * z CPU, żadnych poleceń GPU.
 *
 * Jeden bufor na obraz swapchaina (jak UniformBuffers): GPU może jeszcze
 * rysować poprzednią klatkę z jednego bufora, kiedy CPU wypełnia następny.
 * Wspólny bufor nadpisywany co klatkę dawałby wyścig - migotanie napisów.
 *
 * Liczba wierzchołków może być różna w każdej klatce, bo command buffer i
 * tak jest nagrywany od nowa co klatkę (patrz CommandBuffers.record).
 */
public final class OverlayMesh {

    private final VulkanContext ctx;
    private final int capacity;
    private final List<Long> buffers = new ArrayList<>();
    private final List<Long> memories = new ArrayList<>();
    private final List<FloatBuffer> mapped = new ArrayList<>();
    private final int[] vertexCounts;

    public OverlayMesh(VulkanContext ctx, int imageCount, int capacityVertices) {
        this.ctx = ctx;
        this.capacity = capacityVertices;
        this.vertexCounts = new int[imageCount];

        long size = (long) OverlayVertex.SIZEOF * capacityVertices;

        try (MemoryStack stack = stackPush()) {
            for (int i = 0; i < imageCount; i++) {
                LongBuffer pBuffer = stack.mallocLong(1);
                LongBuffer pMemory = stack.mallocLong(1);

                VulkanBuffers.createBuffer(ctx, size,
                        VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                        pBuffer, pMemory);

                buffers.add(pBuffer.get(0));
                memories.add(pMemory.get(0));

                PointerBuffer data = stack.mallocPointer(1);
                vkMapMemory(ctx.device(), pMemory.get(0), 0, size, 0, data);
                mapped.add(data.getFloatBuffer(0, capacityVertices * OverlayVertex.FLOATS));
            }
        }
    }

    public int capacity() {
        return capacity;
    }

    /**
     * Kopiuje wierzchołki z batcha do bufora obrazu o danym indeksie. Wolno
     * wywołać dopiero, gdy GPU skończył poprzednią klatkę z tym obrazem
     * (patrz SyncObjects.waitIfImageInUse).
     */
    public void update(int imageIndex, TextBatch batch) {
        if (batch.vertexCount() > capacity) {
            throw new IllegalArgumentException("Nakładka ma " + batch.vertexCount() + " wierzchołków, a bufor mieści " + capacity);
        }
        FloatBuffer target = mapped.get(imageIndex);
        target.clear();
        target.put(batch.data(), 0, batch.floatCount());
        vertexCounts[imageIndex] = batch.vertexCount();
    }

    public int vertexCount(int imageIndex) {
        return vertexCounts[imageIndex];
    }

    public void bind(VkCommandBuffer commandBuffer, int imageIndex) {
        try (MemoryStack stack = stackPush()) {
            vkCmdBindVertexBuffers(commandBuffer, 0, stack.longs(buffers.get(imageIndex)), stack.longs(0));
        }
    }

    public void destroy() {
        for (int i = 0; i < buffers.size(); i++) {
            vkUnmapMemory(ctx.device(), memories.get(i));
            vkDestroyBuffer(ctx.device(), buffers.get(i), null);
            vkFreeMemory(ctx.device(), memories.get(i), null);
        }
    }
}

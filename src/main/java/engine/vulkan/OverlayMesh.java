package engine.vulkan;

import engine.scene.OverlayVertex;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Bufor wierzchołków nakładki 2D, którego zawartość zmienia się co klatkę
 * (np. cyfry licznika FPS) - w przeciwieństwie do {@link Mesh} (siatka
 * modelu), tu nie ma sensu robić kosztownego stagingu przez pamięć
 * DEVICE_LOCAL, bo i tak nadpisujemy dane co klatkę. Bufor jest więc
 * "host visible" i zmapowany na stałe (persistent mapping) - update() to
 * zwykłe memcpy z CPU, żadnych poleceń GPU.
 *
 * Liczba wierzchołków jest ZAWSZE stała (capacity) - niewykorzystane sloty
 * wypełnia się zdegenerowanymi (zerowej powierzchni) trójkątami zamiast
 * zmieniać liczbę rysowanych wierzchołków. Dzięki temu polecenie rysujące tę
 * nakładkę w {@link CommandBuffers} można nagrać raz i nigdy nie trzeba go
 * przebudowywać, mimo że treść nakładki zmienia się co klatkę.
 */
public final class OverlayMesh {

    private final VulkanContext ctx;
    private final int capacity;
    private final long buffer;
    private final long bufferMemory;
    private final ByteBuffer mapped;

    public OverlayMesh(VulkanContext ctx, int capacityVertices) {
        this.ctx = ctx;
        this.capacity = capacityVertices;

        long size = (long) OverlayVertex.SIZEOF * capacityVertices;

        try (MemoryStack stack = stackPush()) {
            LongBuffer pBuffer = stack.mallocLong(1);
            LongBuffer pMemory = stack.mallocLong(1);

            VulkanBuffers.createBuffer(ctx, size,
                    VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                    pBuffer, pMemory);

            buffer = pBuffer.get(0);
            bufferMemory = pMemory.get(0);

            PointerBuffer data = stack.mallocPointer(1);
            vkMapMemory(ctx.device(), bufferMemory, 0, size, 0, data);
            mapped = data.getByteBuffer(0, (int) size);
        }
    }

    public int capacity() {
        return capacity;
    }

    /** `vertices.size()` musi równać się {@link #capacity()} - patrz klasa {@link engine.scene.SevenSegmentDigits}. */
    public void update(List<OverlayVertex> vertices) {
        if (vertices.size() != capacity) {
            throw new IllegalArgumentException("Oczekiwano dokładnie " + capacity + " wierzchołków nakładki, otrzymano " + vertices.size());
        }

        mapped.clear();
        for (OverlayVertex vertex : vertices) {
            mapped.putFloat(vertex.pos.x()).putFloat(vertex.pos.y());
            mapped.putFloat(vertex.color.x()).putFloat(vertex.color.y()).putFloat(vertex.color.z());
        }
    }

    public void bind(VkCommandBuffer commandBuffer) {
        try (MemoryStack stack = stackPush()) {
            vkCmdBindVertexBuffers(commandBuffer, 0, stack.longs(buffer), stack.longs(0));
        }
    }

    public void destroy() {
        vkUnmapMemory(ctx.device(), bufferMemory);
        vkDestroyBuffer(ctx.device(), buffer, null);
        vkFreeMemory(ctx.device(), bufferMemory, null);
    }
}

package engine.vulkan;

import engine.scene.UniformBufferObject;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static engine.scene.UniformBufferObject.SIZEOF;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Jeden uniform buffer na obraz swapchaina - w locie może być rysowanych
 * kilka klatek naraz (patrz Frame/SyncObjects), więc każda potrzebuje
 * własnej kopii danych, żeby aktualizacja jednej nie nadpisała danych, z
 * których GPU jeszcze korzysta przy poprzedniej klatce.
 *
 * Bufory są "host visible" i pozostają zmapowane przez cały czas życia
 * (persistent mapping) - unikamy w ten sposób kosztu map/unmap co klatkę.
 */
public final class UniformBuffers {

    private final VulkanContext ctx;
    private final List<Long> buffers;
    private final List<Long> memories;
    private final List<ByteBuffer> mapped;

    public UniformBuffers(VulkanContext ctx, int count) {
        this.ctx = ctx;
        this.buffers = new ArrayList<>(count);
        this.memories = new ArrayList<>(count);
        this.mapped = new ArrayList<>(count);

        try (MemoryStack stack = stackPush()) {
            for (int i = 0; i < count; i++) {
                LongBuffer pBuffer = stack.mallocLong(1);
                LongBuffer pMemory = stack.mallocLong(1);

                VulkanBuffers.createBuffer(ctx, SIZEOF,
                        VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                        pBuffer, pMemory);

                buffers.add(pBuffer.get(0));
                memories.add(pMemory.get(0));

                PointerBuffer data = stack.mallocPointer(1);
                vkMapMemory(ctx.device(), pMemory.get(0), 0, SIZEOF, 0, data);
                mapped.add(data.getByteBuffer(0, SIZEOF));
            }
        }
    }

    public long buffer(int index) {
        return buffers.get(index);
    }

    public void update(int index, UniformBufferObject ubo) {
        ByteBuffer buffer = mapped.get(index);
        ubo.view.get(0, buffer);
        ubo.proj.get(16 * Float.BYTES, buffer);
    }

    public void destroy() {
        for (int i = 0; i < buffers.size(); i++) {
            vkUnmapMemory(ctx.device(), memories.get(i));
            vkDestroyBuffer(ctx.device(), buffers.get(i), null);
            vkFreeMemory(ctx.device(), memories.get(i), null);
        }
    }
}

package engine.scene;

import engine.vulkan.CommandPool;
import engine.vulkan.VulkanBuffers;
import engine.vulkan.VulkanContext;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.assimp.Assimp.aiProcess_DropNormals;
import static org.lwjgl.assimp.Assimp.aiProcess_FlipUVs;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Geometria gotowa do rysowania: bufor wierzchołków i bufor indeksów na GPU.
 *
 * Oba tworzone są tym samym wzorcem - "staging buffer": dane trafiają
 * najpierw do bufora widocznego z CPU (HOST_VISIBLE), a stamtąd są
 * kopiowane na GPU do pamięci DEVICE_LOCAL, która jest szybsza dla GPU, ale
 * niedostępna bezpośrednio z CPU.
 */
public final class Mesh {

    private final VulkanContext ctx;
    private final int indexCount;

    private final long vertexBuffer;
    private final long vertexBufferMemory;
    private final long indexBuffer;
    private final long indexBufferMemory;

    public Mesh(VulkanContext ctx, CommandPool commandPool, Vertex[] vertices, int[] indices) {
        this.ctx = ctx;
        this.indexCount = indices.length;

        try (MemoryStack stack = stackPush()) {
            long[] vb = createDeviceLocalBuffer(stack, commandPool,
                    (long) Vertex.SIZEOF * vertices.length,
                    VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                    buffer -> writeVertices(buffer, vertices));
            vertexBuffer = vb[0];
            vertexBufferMemory = vb[1];

            long[] ib = createDeviceLocalBuffer(stack, commandPool,
                    (long) Integer.BYTES * indices.length,
                    VK_BUFFER_USAGE_INDEX_BUFFER_BIT,
                    buffer -> writeIndices(buffer, indices));
            indexBuffer = ib[0];
            indexBufferMemory = ib[1];
        }
    }

    /** Wczytuje model z pliku i zamienia go na siatkę gotową do rysowania (biały tint na całej powierzchni). */
    public static Mesh loadFromFile(VulkanContext ctx, CommandPool commandPool, String modelResourcePath) {

        File modelFile = ModelLoader.resourceFile(modelResourcePath);
        ModelLoader.Model model = ModelLoader.load(modelFile, aiProcess_FlipUVs | aiProcess_DropNormals);

        Vector3fc white = new Vector3f(1.0f, 1.0f, 1.0f);

        Vertex[] vertices = new Vertex[model.positions.size()];
        for (int i = 0; i < vertices.length; i++) {
            vertices[i] = new Vertex(model.positions.get(i), white, model.texCoords.get(i));
        }

        int[] indices = new int[model.indices.size()];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = model.indices.get(i);
        }

        return new Mesh(ctx, commandPool, vertices, indices);
    }

    private interface BufferWriter {
        void write(ByteBuffer target);
    }

    private long[] createDeviceLocalBuffer(MemoryStack stack, CommandPool commandPool, long size, int usage, BufferWriter writer) {

        LongBuffer pStagingBuffer = stack.mallocLong(1);
        LongBuffer pStagingMemory = stack.mallocLong(1);
        VulkanBuffers.createBuffer(ctx, size,
                VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                pStagingBuffer, pStagingMemory);

        PointerBuffer data = stack.mallocPointer(1);
        vkMapMemory(ctx.device(), pStagingMemory.get(0), 0, size, 0, data);
        writer.write(data.getByteBuffer(0, (int) size));
        vkUnmapMemory(ctx.device(), pStagingMemory.get(0));

        LongBuffer pBuffer = stack.mallocLong(1);
        LongBuffer pBufferMemory = stack.mallocLong(1);
        VulkanBuffers.createBuffer(ctx, size,
                VK_BUFFER_USAGE_TRANSFER_DST_BIT | usage,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                pBuffer, pBufferMemory);

        VulkanBuffers.copyBuffer(ctx, commandPool, pStagingBuffer.get(0), pBuffer.get(0), size);

        vkDestroyBuffer(ctx.device(), pStagingBuffer.get(0), null);
        vkFreeMemory(ctx.device(), pStagingMemory.get(0), null);

        return new long[] {pBuffer.get(0), pBufferMemory.get(0)};
    }

    private static void writeVertices(ByteBuffer buffer, Vertex[] vertices) {
        for (Vertex vertex : vertices) {
            buffer.putFloat(vertex.pos.x()).putFloat(vertex.pos.y()).putFloat(vertex.pos.z());
            buffer.putFloat(vertex.color.x()).putFloat(vertex.color.y()).putFloat(vertex.color.z());
            buffer.putFloat(vertex.texCoords.x()).putFloat(vertex.texCoords.y());
        }
    }

    private static void writeIndices(ByteBuffer buffer, int[] indices) {
        for (int index : indices) {
            buffer.putInt(index);
        }
        buffer.rewind();
    }

    public int indexCount() {
        return indexCount;
    }

    public void bind(VkCommandBuffer commandBuffer) {
        try (MemoryStack stack = stackPush()) {
            vkCmdBindVertexBuffers(commandBuffer, 0, stack.longs(vertexBuffer), stack.longs(0));
            vkCmdBindIndexBuffer(commandBuffer, indexBuffer, 0, VK_INDEX_TYPE_UINT32);
        }
    }

    public void destroy() {
        vkDestroyBuffer(ctx.device(), indexBuffer, null);
        vkFreeMemory(ctx.device(), indexBufferMemory, null);
        vkDestroyBuffer(ctx.device(), vertexBuffer, null);
        vkFreeMemory(ctx.device(), vertexBufferMemory, null);
    }
}

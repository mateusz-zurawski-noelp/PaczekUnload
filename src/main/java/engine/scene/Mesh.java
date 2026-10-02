package engine.scene;

import engine.log.Log;
import engine.resource.ResourceManager;
import engine.vulkan.CommandPool;
import engine.vulkan.VulkanBuffers;
import engine.vulkan.VulkanContext;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.assimp.Assimp.aiProcess_FlipUVs;
import static org.lwjgl.assimp.Assimp.aiProcess_GenSmoothNormals;
import static org.lwjgl.assimp.Assimp.aiProcess_JoinIdenticalVertices;
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

    /**
     * Wczytuje model z pliku (classpath albo dysk - przez ResourceManager) i
     * zamienia go na siatkę gotową do rysowania (biały tint na całej powierzchni).
     */
    public static Mesh loadFromFile(VulkanContext ctx, CommandPool commandPool, ResourceManager resources, String modelPath) {

        long start = System.nanoTime();
        ByteBuffer fileData = resources.readBytes(modelPath);
        // GenSmoothNormals: jeśli plik nie ma normalnych (chalet.obj nie ma), Assimp
        // wylicza je, uśredniając normalne ścian stykających się w wierzchołku -
        // dzięki temu oświetlenie jest gładkie, a nie "kanciaste" na każdym trójkącie.
        //
        // JoinIdenticalVertices: OBJ i FBX dają osobny wierzchołek dla każdego rogu
        // każdego trójkąta, choć ten sam punkt należy zwykle do kilku trójkątów.
        // Assimp scala wierzchołki o identycznych WSZYSTKICH atrybutach (pozycja,
        // UV, normalna...) i odwołuje się do nich przez indeksy. Dla chalet.obj:
        // 1 500 000 -> ~266 000 wierzchołków (66 MB -> 12 MB), a GPU lepiej
        // wykorzystuje cache wierzchołków. Na szwach UV wierzchołki zostają osobno,
        // bo różnią się współrzędnymi tekstury. Kolejność flag nie ma znaczenia -
        // Assimp sam ustala kolejność kroków (normalne liczy przed scalaniem).
        ModelLoader.Model model;
        try {
            model = ModelLoader.load(fileData, modelPath,
                    aiProcess_FlipUVs | aiProcess_GenSmoothNormals | aiProcess_JoinIdenticalVertices);
        } finally {
            MemoryUtil.memFree(fileData);
        }

        Vector3fc white = new Vector3f(1.0f, 1.0f, 1.0f);

        Vertex[] vertices = new Vertex[model.positions.size()];
        for (int i = 0; i < vertices.length; i++) {
            vertices[i] = new Vertex(model.positions.get(i), white, model.texCoords.get(i), model.normals.get(i));
        }

        int[] indices = new int[model.indices.size()];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = model.indices.get(i);
        }

        Mesh mesh = new Mesh(ctx, commandPool, vertices, indices);
        Log.info("Resources", String.format("Model %s: %,d wierzchołków, %,d trójkątów (%d ms)",
                modelPath, vertices.length, indices.length / 3, (System.nanoTime() - start) / 1_000_000));
        return mesh;
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
            buffer.putFloat(vertex.normal.x()).putFloat(vertex.normal.y()).putFloat(vertex.normal.z());
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

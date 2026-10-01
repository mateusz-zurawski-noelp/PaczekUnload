package engine.vulkan;

import engine.scene.Renderable;
import org.joml.Matrix4f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Command buffer na każdy obraz swapchaina. Sekwencja poleceń GPU dla klatki:
 * rozpocznij render pass -> dla każdego obiektu i jego części: zbinduj
 * materiał, wyślij macierz modelu, zbinduj geometrię, narysuj -> nakładka
 * (FPS) -> zakończ render pass.
 *
 * Bufor jest nagrywany od nowa co klatkę ({@link #record}), bo macierz modelu
 * idzie przez push constants, które są "wpisane" w nagrane polecenia - a
 * obiekty się poruszają. Przy kilkudziesięciu obiektach to tanie; przy
 * tysiącach warto przejść na dane obiektów w osobnym buforze (dynamic
 * uniform / storage buffer / instancing) i wrócić do nagrywania raz.
 */
public final class CommandBuffers {

    private final VulkanContext ctx;
    private final CommandPool commandPool;
    private final Framebuffers framebuffers;
    private final RenderPass renderPass;
    private final VkExtent2D extent;
    private final GraphicsPipeline pipeline;
    private final DescriptorSets descriptorSets;
    private final OverlayPipeline overlayPipeline;
    private final OverlayMesh overlayMesh;
    private final List<VkCommandBuffer> buffers;

    public CommandBuffers(VulkanContext ctx, CommandPool commandPool, Framebuffers framebuffers, RenderPass renderPass,
                            VkExtent2D extent, GraphicsPipeline pipeline, DescriptorSets descriptorSets,
                            OverlayPipeline overlayPipeline, OverlayMesh overlayMesh) {
        this.ctx = ctx;
        this.commandPool = commandPool;
        this.framebuffers = framebuffers;
        this.renderPass = renderPass;
        this.extent = extent;
        this.pipeline = pipeline;
        this.descriptorSets = descriptorSets;
        this.overlayPipeline = overlayPipeline;
        this.overlayMesh = overlayMesh;

        int count = framebuffers.handles().size();
        buffers = new ArrayList<>(count);

        try (MemoryStack stack = stackPush()) {

            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack);
            allocInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
            allocInfo.commandPool(commandPool.handle());
            allocInfo.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY);
            allocInfo.commandBufferCount(count);

            PointerBuffer pCommandBuffers = stack.mallocPointer(count);
            if (vkAllocateCommandBuffers(ctx.device(), allocInfo, pCommandBuffers) != VK_SUCCESS) {
                throw new RuntimeException("Nie udało się zaalokować command bufferów");
            }
            for (int i = 0; i < count; i++) {
                buffers.add(new VkCommandBuffer(pCommandBuffers.get(i), ctx.device()));
            }
        }
    }

    /**
     * Nagrywa od nowa command buffer obrazu o danym indeksie. Wolno wywołać
     * dopiero, gdy GPU skończył go używać (patrz SyncObjects.waitIfImageInUse).
     *
     * @param alpha ułamek kroku symulacji (0..1) do interpolacji transformacji
     *              obiektów - patrz FixedTimestep.alpha() i Renderable.interpolatedModelMatrix()
     */
    public void record(int index, List<Renderable> renderables, float alpha) {
        VkCommandBuffer commandBuffer = buffers.get(index);

        try (MemoryStack stack = stackPush()) {

            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack);
            beginInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);

            VkRenderPassBeginInfo renderPassInfo = VkRenderPassBeginInfo.calloc(stack);
            renderPassInfo.sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO);
            renderPassInfo.renderPass(renderPass.handle());
            renderPassInfo.framebuffer(framebuffers.handles().get(index));

            VkRect2D renderArea = VkRect2D.calloc(stack);
            renderArea.offset(VkOffset2D.calloc(stack).set(0, 0));
            renderArea.extent(extent);
            renderPassInfo.renderArea(renderArea);

            VkClearValue.Buffer clearValues = VkClearValue.calloc(2, stack);
            clearValues.get(0).color().float32(stack.floats(0.0f, 0.0f, 0.0f, 1.0f));
            clearValues.get(1).depthStencil().set(1.0f, 0);
            renderPassInfo.pClearValues(clearValues);

            if (vkBeginCommandBuffer(commandBuffer, beginInfo) != VK_SUCCESS) {
                throw new RuntimeException("Nie udało się rozpocząć nagrywania command buffera");
            }

            vkCmdBeginRenderPass(commandBuffer, renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);
            {
                vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());

                // Set 0 (kamera) jest wspólny dla całej klatki.
                vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                        pipeline.layout(), 0, stack.longs(descriptorSets.set(index)), null);

                ByteBuffer modelMatrix = stack.malloc(GraphicsPipeline.PUSH_CONSTANT_SIZE);
                Matrix4f model = new Matrix4f();

                for (Renderable renderable : renderables) {
                    if (!renderable.visible) {
                        continue;
                    }

                    renderable.interpolatedModelMatrix(alpha, model).get(modelMatrix);
                    vkCmdPushConstants(commandBuffer, pipeline.layout(), VK_SHADER_STAGE_VERTEX_BIT, 0, modelMatrix);

                    for (Renderable.Part part : renderable.parts()) {
                        // Set 1 (materiał) zmienia się między częściami obiektu.
                        vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                                pipeline.layout(), 1, stack.longs(part.material().descriptorSet()), null);

                        part.mesh().bind(commandBuffer);
                        vkCmdDrawIndexed(commandBuffer, part.mesh().indexCount(), 1, 0, 0, 0);
                    }
                }

                // Nakładka (licznik FPS) rysowana na wierzchu, w tym samym render passie.
                // Liczba wierzchołków jest stała (patrz OverlayMesh) - tylko zawartość
                // bufora zmienia się co klatkę.
                vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, overlayPipeline.handle());
                overlayMesh.bind(commandBuffer);
                vkCmdDraw(commandBuffer, overlayMesh.capacity(), 1, 0, 0);
            }
            vkCmdEndRenderPass(commandBuffer);

            if (vkEndCommandBuffer(commandBuffer) != VK_SUCCESS) {
                throw new RuntimeException("Nie udało się zakończyć nagrywania command buffera");
            }
        }
    }

    public VkCommandBuffer get(int index) {
        return buffers.get(index);
    }

    public void destroy() {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pBuffers = stack.mallocPointer(buffers.size());
            buffers.forEach(pBuffers::put);
            pBuffers.rewind();
            vkFreeCommandBuffers(ctx.device(), commandPool.handle(), pBuffers);
        }
    }
}

package engine.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Polecenia dla GPU (rysuj, kopiuj bufor, zmień layout obrazu...) nie są
 * wykonywane od razu - nagrywa się je do VkCommandBuffer, a bufory alokuje
 * się z VkCommandPool. Ta klasa trzyma pulę i dorzuca wygodne "one-shot"
 * polecenia (np. kopiowanie danych z bufora stagingowego na GPU), które
 * wykonują się natychmiast i synchronicznie - świetne do nauki, kiepskie do
 * wydajności w prawdziwym silniku (tam wolałoby się osobną kolejkę transferu).
 */
public final class CommandPool {

    private final VulkanContext ctx;
    private final long handle;

    public CommandPool(VulkanContext ctx) {
        this.ctx = ctx;

        try (MemoryStack stack = stackPush()) {

            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack);
            poolInfo.sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO);
            poolInfo.queueFamilyIndex(ctx.graphicsQueueFamily());
            // Pozwala nagrywać command buffery od nowa co klatkę (potrzebne, bo transformacje obiektów się zmieniają).
            poolInfo.flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);

            LongBuffer pCommandPool = stack.mallocLong(1);
            if (vkCreateCommandPool(ctx.device(), poolInfo, null, pCommandPool) != VK_SUCCESS) {
                throw new RuntimeException("Nie udało się utworzyć command poola");
            }

            handle = pCommandPool.get(0);
        }
    }

    public long handle() {
        return handle;
    }

    public VkCommandBuffer beginSingleTimeCommands() {

        try (MemoryStack stack = stackPush()) {

            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack);
            allocInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
            allocInfo.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY);
            allocInfo.commandPool(handle);
            allocInfo.commandBufferCount(1);

            PointerBuffer pCommandBuffer = stack.mallocPointer(1);
            vkAllocateCommandBuffers(ctx.device(), allocInfo, pCommandBuffer);
            VkCommandBuffer commandBuffer = new VkCommandBuffer(pCommandBuffer.get(0), ctx.device());

            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack);
            beginInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            beginInfo.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

            vkBeginCommandBuffer(commandBuffer, beginInfo);

            return commandBuffer;
        }
    }

    public void endSingleTimeCommands(VkCommandBuffer commandBuffer) {

        try (MemoryStack stack = stackPush()) {

            vkEndCommandBuffer(commandBuffer);

            VkSubmitInfo.Buffer submitInfo = VkSubmitInfo.calloc(1, stack);
            submitInfo.sType(VK_STRUCTURE_TYPE_SUBMIT_INFO);
            submitInfo.pCommandBuffers(stack.pointers(commandBuffer));

            vkQueueSubmit(ctx.graphicsQueue(), submitInfo, VK_NULL_HANDLE);
            // Prostota kosztem wydajności: czekamy aż GPU skończy zamiast
            // np. zbierać wiele transferów i synchronizować semaforem.
            vkQueueWaitIdle(ctx.graphicsQueue());

            vkFreeCommandBuffers(ctx.device(), handle, commandBuffer);
        }
    }

    public void destroy() {
        vkDestroyCommandPool(ctx.device(), handle, null);
    }
}

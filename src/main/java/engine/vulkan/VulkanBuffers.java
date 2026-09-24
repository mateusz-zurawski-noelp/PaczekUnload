package engine.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Pomocnicze funkcje do tworzenia VkBuffer (pamięć widziana liniowo, np. bufor
 * wierzchołków, indeksów, uniformów) i kopiowania między buforami.
 *
 * Vulkan rozdziela tworzenie zasobu (VkBuffer - sam "opis") od alokacji
 * pamięci (VkDeviceMemory - realne bajty na GPU) - trzeba je ręcznie połączyć
 * przez vkBindBufferMemory. To jedna z rzeczy, które OpenGL robił za nas.
 */
public final class VulkanBuffers {

    private VulkanBuffers() {
    }

    public static void createBuffer(VulkanContext ctx, long size, int usage, int properties,
                                      LongBuffer pBuffer, LongBuffer pBufferMemory) {

        try (MemoryStack stack = stackPush()) {

            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack);
            bufferInfo.sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO);
            bufferInfo.size(size);
            bufferInfo.usage(usage);
            bufferInfo.sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            if (vkCreateBuffer(ctx.device(), bufferInfo, null, pBuffer) != VK_SUCCESS) {
                throw new RuntimeException("Nie udało się utworzyć VkBuffer");
            }

            VkMemoryRequirements memRequirements = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(ctx.device(), pBuffer.get(0), memRequirements);

            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack);
            allocInfo.sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO);
            allocInfo.allocationSize(memRequirements.size());
            allocInfo.memoryTypeIndex(findMemoryType(ctx, stack, memRequirements.memoryTypeBits(), properties));

            if (vkAllocateMemory(ctx.device(), allocInfo, null, pBufferMemory) != VK_SUCCESS) {
                throw new RuntimeException("Nie udało się zaalokować pamięci dla bufora");
            }

            vkBindBufferMemory(ctx.device(), pBuffer.get(0), pBufferMemory.get(0), 0);
        }
    }

    public static void copyBuffer(VulkanContext ctx, CommandPool commandPool, long srcBuffer, long dstBuffer, long size) {

        try (MemoryStack stack = stackPush()) {

            VkCommandBuffer commandBuffer = commandPool.beginSingleTimeCommands();

            VkBufferCopy.Buffer copyRegion = VkBufferCopy.calloc(1, stack);
            copyRegion.size(size);

            vkCmdCopyBuffer(commandBuffer, srcBuffer, dstBuffer, copyRegion);

            commandPool.endSingleTimeCommands(commandBuffer);
        }
    }

    /**
     * GPU-ki mają kilka rodzajów pamięci (VRAM szybka ale niewidoczna z CPU,
     * pamięć "host visible" wolniejsza ale mapowalna, ...). Trzeba samemu
     * znaleźć typ pamięci, który spełnia wymagania zasobu (typeFilter) i ma
     * potrzebne właściwości (np. HOST_VISIBLE żeby móc memcpy z CPU).
     */
    public static int findMemoryType(VulkanContext ctx, MemoryStack stack, int typeFilter, int properties) {

        VkPhysicalDeviceMemoryProperties memProperties = VkPhysicalDeviceMemoryProperties.malloc(stack);
        vkGetPhysicalDeviceMemoryProperties(ctx.physicalDevice(), memProperties);

        for (int i = 0; i < memProperties.memoryTypeCount(); i++) {
            boolean typeAllowed = (typeFilter & (1 << i)) != 0;
            boolean hasProperties = (memProperties.memoryTypes(i).propertyFlags() & properties) == properties;
            if (typeAllowed && hasProperties) {
                return i;
            }
        }

        throw new RuntimeException("Nie znaleziono odpowiedniego typu pamięci GPU");
    }
}

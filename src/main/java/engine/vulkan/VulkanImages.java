package engine.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Pomocnicze funkcje do tworzenia VkImage/VkImageView oraz zmiany layoutu obrazów. */
public final class VulkanImages {

    private VulkanImages() {
    }

    public static void createImage(VulkanContext ctx, int width, int height, int format, int tiling,
                                     int usage, int memProperties, LongBuffer pImage, LongBuffer pImageMemory) {

        try (MemoryStack stack = stackPush()) {

            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack);
            imageInfo.sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO);
            imageInfo.imageType(VK_IMAGE_TYPE_2D);
            imageInfo.extent().width(width).height(height).depth(1);
            imageInfo.mipLevels(1);
            imageInfo.arrayLayers(1);
            imageInfo.format(format);
            imageInfo.tiling(tiling);
            imageInfo.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            imageInfo.usage(usage);
            imageInfo.samples(VK_SAMPLE_COUNT_1_BIT);
            imageInfo.sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            if (vkCreateImage(ctx.device(), imageInfo, null, pImage) != VK_SUCCESS) {
                throw new RuntimeException("Nie udało się utworzyć VkImage");
            }

            VkMemoryRequirements memRequirements = VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(ctx.device(), pImage.get(0), memRequirements);

            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack);
            allocInfo.sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO);
            allocInfo.allocationSize(memRequirements.size());
            allocInfo.memoryTypeIndex(VulkanBuffers.findMemoryType(ctx, stack, memRequirements.memoryTypeBits(), memProperties));

            if (vkAllocateMemory(ctx.device(), allocInfo, null, pImageMemory) != VK_SUCCESS) {
                throw new RuntimeException("Nie udało się zaalokować pamięci dla obrazu");
            }

            vkBindImageMemory(ctx.device(), pImage.get(0), pImageMemory.get(0), 0);
        }
    }

    public static long createImageView(VulkanContext ctx, long image, int format, int aspectFlags) {

        try (MemoryStack stack = stackPush()) {

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack);
            viewInfo.sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO);
            viewInfo.image(image);
            viewInfo.viewType(VK_IMAGE_VIEW_TYPE_2D);
            viewInfo.format(format);
            viewInfo.subresourceRange().aspectMask(aspectFlags);
            viewInfo.subresourceRange().baseMipLevel(0);
            viewInfo.subresourceRange().levelCount(1);
            viewInfo.subresourceRange().baseArrayLayer(0);
            viewInfo.subresourceRange().layerCount(1);

            LongBuffer pImageView = stack.mallocLong(1);
            if (vkCreateImageView(ctx.device(), viewInfo, null, pImageView) != VK_SUCCESS) {
                throw new RuntimeException("Nie udało się utworzyć VkImageView");
            }

            return pImageView.get(0);
        }
    }

    /**
     * Vulkan wymaga jawnego przejścia obrazu między layoutami (np. "cel kopiowania"
     * -> "tylko do odczytu w shaderze") za pomocą bariery pamięci. To pozwala
     * sterownikowi zoptymalizować układ danych obrazu pod konkretne użycie, ale
     * oznacza, że programista musi sam wiedzieć, kiedy taka zmiana jest potrzebna.
     */
    public static void transitionImageLayout(VulkanContext ctx, CommandPool commandPool,
                                                long image, int format, int oldLayout, int newLayout) {

        try (MemoryStack stack = stackPush()) {

            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
            barrier.sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
            barrier.oldLayout(oldLayout);
            barrier.newLayout(newLayout);
            barrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.image(image);
            barrier.subresourceRange().baseMipLevel(0);
            barrier.subresourceRange().levelCount(1);
            barrier.subresourceRange().baseArrayLayer(0);
            barrier.subresourceRange().layerCount(1);

            if (newLayout == VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL) {
                int aspect = VK_IMAGE_ASPECT_DEPTH_BIT;
                if (hasStencilComponent(format)) {
                    aspect |= VK_IMAGE_ASPECT_STENCIL_BIT;
                }
                barrier.subresourceRange().aspectMask(aspect);
            } else {
                barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            }

            int sourceStage;
            int destinationStage;

            if (oldLayout == VK_IMAGE_LAYOUT_UNDEFINED && newLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {

                barrier.srcAccessMask(0);
                barrier.dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                sourceStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
                destinationStage = VK_PIPELINE_STAGE_TRANSFER_BIT;

            } else if (oldLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL && newLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {

                barrier.srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
                barrier.dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
                sourceStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
                destinationStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;

            } else if (oldLayout == VK_IMAGE_LAYOUT_UNDEFINED && newLayout == VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL) {

                barrier.srcAccessMask(0);
                barrier.dstAccessMask(VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
                sourceStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
                destinationStage = VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT;

            } else {
                throw new IllegalArgumentException("Nieobsługiwane przejście layoutu: " + oldLayout + " -> " + newLayout);
            }

            VkCommandBuffer commandBuffer = commandPool.beginSingleTimeCommands();

            vkCmdPipelineBarrier(commandBuffer, sourceStage, destinationStage, 0, null, null, barrier);

            commandPool.endSingleTimeCommands(commandBuffer);
        }
    }

    public static void copyBufferToImage(VulkanContext ctx, CommandPool commandPool, long buffer, long image, int width, int height) {

        try (MemoryStack stack = stackPush()) {

            VkCommandBuffer commandBuffer = commandPool.beginSingleTimeCommands();

            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.bufferOffset(0);
            region.bufferRowLength(0);
            region.bufferImageHeight(0);
            region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            region.imageSubresource().mipLevel(0);
            region.imageSubresource().baseArrayLayer(0);
            region.imageSubresource().layerCount(1);
            region.imageOffset().set(0, 0, 0);
            region.imageExtent(VkExtent3D.calloc(stack).set(width, height, 1));

            vkCmdCopyBufferToImage(commandBuffer, buffer, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);

            commandPool.endSingleTimeCommands(commandBuffer);
        }
    }

    public static boolean hasStencilComponent(int format) {
        return format == VK_FORMAT_D32_SFLOAT_S8_UINT || format == VK_FORMAT_D24_UNORM_S8_UINT;
    }

    public static int findSupportedFormat(VulkanContext ctx, int[] candidates, int tiling, int features) {

        try (MemoryStack stack = stackPush()) {

            VkFormatProperties props = VkFormatProperties.calloc(stack);

            for (int format : candidates) {
                vkGetPhysicalDeviceFormatProperties(ctx.physicalDevice(), format, props);

                if (tiling == VK_IMAGE_TILING_LINEAR && (props.linearTilingFeatures() & features) == features) {
                    return format;
                } else if (tiling == VK_IMAGE_TILING_OPTIMAL && (props.optimalTilingFeatures() & features) == features) {
                    return format;
                }
            }
        }

        throw new RuntimeException("Nie znaleziono wspieranego formatu obrazu");
    }

    public static int findDepthFormat(VulkanContext ctx) {
        return findSupportedFormat(ctx,
                new int[] {VK_FORMAT_D32_SFLOAT, VK_FORMAT_D32_SFLOAT_S8_UINT, VK_FORMAT_D24_UNORM_S8_UINT},
                VK_IMAGE_TILING_OPTIMAL,
                VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT);
    }
}

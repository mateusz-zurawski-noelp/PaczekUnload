package engine.vulkan;

import org.lwjgl.vulkan.VkExtent2D;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Bufor głębi (z-bufor) pozwala GPU odrzucać fragmenty zasłonięte przez inne,
 * bliższe kamerze, zamiast rysować trójkąty w kolejności, w jakiej je wysłano
 * (malarski algorytm). To zwykły VkImage, tyle że z formatem przechowującym
 * głębię zamiast koloru i użyciem DEPTH_STENCIL_ATTACHMENT.
 */
public final class DepthResources {

    private final VulkanContext ctx;

    private final long image;
    private final long imageMemory;
    private final long imageView;
    private final int format;

    public DepthResources(VulkanContext ctx, CommandPool commandPool, VkExtent2D extent) {
        this.ctx = ctx;

        format = VulkanImages.findDepthFormat(ctx);

        try (var stack = stackPush()) {
            LongBuffer pImage = stack.mallocLong(1);
            LongBuffer pImageMemory = stack.mallocLong(1);

            VulkanImages.createImage(ctx, extent.width(), extent.height(), format,
                    VK_IMAGE_TILING_OPTIMAL,
                    VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                    pImage, pImageMemory);

            image = pImage.get(0);
            imageMemory = pImageMemory.get(0);
        }

        imageView = VulkanImages.createImageView(ctx, image, format, VK_IMAGE_ASPECT_DEPTH_BIT);

        VulkanImages.transitionImageLayout(ctx, commandPool, image, format,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
    }

    public long imageView() {
        return imageView;
    }

    public int format() {
        return format;
    }

    public void destroy() {
        vkDestroyImageView(ctx.device(), imageView, null);
        vkDestroyImage(ctx.device(), image, null);
        vkFreeMemory(ctx.device(), imageMemory, null);
    }
}

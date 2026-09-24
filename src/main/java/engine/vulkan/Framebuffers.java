package engine.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkExtent2D;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Framebuffer spina konkretne VkImageView (obraz swapchaina + obraz głębi) z
 * render passem, który opisuje jak te załączniki są traktowane. Potrzeba
 * jednego framebuffera na każdy obraz swapchaina.
 */
public final class Framebuffers {

    private final VulkanContext ctx;
    private final List<Long> handles;

    public Framebuffers(VulkanContext ctx, RenderPass renderPass, List<Long> colorImageViews,
                          long depthImageView, VkExtent2D extent) {
        this.ctx = ctx;
        this.handles = new ArrayList<>(colorImageViews.size());

        try (MemoryStack stack = stackPush()) {

            LongBuffer attachments = stack.longs(VK_NULL_HANDLE, depthImageView);
            LongBuffer pFramebuffer = stack.mallocLong(1);

            VkFramebufferCreateInfo framebufferInfo = VkFramebufferCreateInfo.calloc(stack);
            framebufferInfo.sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO);
            framebufferInfo.renderPass(renderPass.handle());
            framebufferInfo.width(extent.width());
            framebufferInfo.height(extent.height());
            framebufferInfo.layers(1);

            for (long colorImageView : colorImageViews) {
                attachments.put(0, colorImageView);
                framebufferInfo.pAttachments(attachments);

                if (vkCreateFramebuffer(ctx.device(), framebufferInfo, null, pFramebuffer) != VK_SUCCESS) {
                    throw new RuntimeException("Nie udało się utworzyć framebuffera");
                }

                handles.add(pFramebuffer.get(0));
            }
        }
    }

    public List<Long> handles() {
        return handles;
    }

    public void destroy() {
        handles.forEach(fb -> vkDestroyFramebuffer(ctx.device(), fb, null));
    }
}

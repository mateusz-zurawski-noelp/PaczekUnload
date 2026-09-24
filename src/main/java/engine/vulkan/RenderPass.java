package engine.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Render pass opisuje z góry, jakie "załączniki" (attachments - obrazy, do
 * których się rysuje: kolor, głębia...) będą użyte, w jakim stanie zaczynają
 * i kończą, i jak są ułożone podpasy (subpasses). Znając to z wyprzedzeniem,
 * sterownik GPU może mocno zoptymalizować przejścia między etapami renderu -
 * to jedna z kluczowych różnic filozofii Vulkana względem OpenGL.
 *
 * Tu mamy jeden podpas z dwoma załącznikami: kolor (obraz swapchaina) i głębia
 * (z-bufor, patrz DepthResources).
 */
public final class RenderPass {

    private final VulkanContext ctx;
    private final long handle;

    public RenderPass(VulkanContext ctx, int colorFormat, int depthFormat) {
        this.ctx = ctx;

        try (MemoryStack stack = stackPush()) {

            VkAttachmentDescription.Buffer attachments = VkAttachmentDescription.calloc(2, stack);
            VkAttachmentReference.Buffer attachmentRefs = VkAttachmentReference.calloc(2, stack);

            VkAttachmentDescription colorAttachment = attachments.get(0);
            colorAttachment.format(colorFormat);
            colorAttachment.samples(VK_SAMPLE_COUNT_1_BIT);
            colorAttachment.loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR);
            colorAttachment.storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            colorAttachment.stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE);
            colorAttachment.stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE);
            colorAttachment.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            colorAttachment.finalLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);

            VkAttachmentReference colorAttachmentRef = attachmentRefs.get(0);
            colorAttachmentRef.attachment(0);
            colorAttachmentRef.layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

            VkAttachmentDescription depthAttachment = attachments.get(1);
            depthAttachment.format(depthFormat);
            depthAttachment.samples(VK_SAMPLE_COUNT_1_BIT);
            depthAttachment.loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR);
            depthAttachment.storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE);
            depthAttachment.stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE);
            depthAttachment.stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE);
            depthAttachment.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            depthAttachment.finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

            VkAttachmentReference depthAttachmentRef = attachmentRefs.get(1);
            depthAttachmentRef.attachment(1);
            depthAttachmentRef.layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack);
            subpass.pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS);
            subpass.colorAttachmentCount(1);
            subpass.pColorAttachments(VkAttachmentReference.calloc(1, stack).put(0, colorAttachmentRef));
            subpass.pDepthStencilAttachment(depthAttachmentRef);

            VkSubpassDependency.Buffer dependency = VkSubpassDependency.calloc(1, stack);
            dependency.srcSubpass(VK_SUBPASS_EXTERNAL);
            dependency.dstSubpass(0);
            dependency.srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
            dependency.srcAccessMask(0);
            dependency.dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
            dependency.dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);

            VkRenderPassCreateInfo renderPassInfo = VkRenderPassCreateInfo.calloc(stack);
            renderPassInfo.sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO);
            renderPassInfo.pAttachments(attachments);
            renderPassInfo.pSubpasses(subpass);
            renderPassInfo.pDependencies(dependency);

            LongBuffer pRenderPass = stack.mallocLong(1);
            if (vkCreateRenderPass(ctx.device(), renderPassInfo, null, pRenderPass) != VK_SUCCESS) {
                throw new RuntimeException("Nie udało się utworzyć render passa");
            }

            handle = pRenderPass.get(0);
        }
    }

    public long handle() {
        return handle;
    }

    public void destroy() {
        vkDestroyRenderPass(ctx.device(), handle, null);
    }
}

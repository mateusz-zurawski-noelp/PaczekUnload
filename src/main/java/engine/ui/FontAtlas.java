package engine.ui;

import engine.vulkan.CommandPool;
import engine.vulkan.DescriptorSetLayout;
import engine.vulkan.Texture;
import engine.vulkan.VulkanContext;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Część GPU czcionki: bitmapa z {@link BakedFont} wgrana jako tekstura
 * RGBA (kolor biały, alfa = pokrycie piksela literą) + descriptor set, przez
 * który shader nakładki ją widzi. Shader mnoży biel przez kolor
 * wierzchołka, więc ten sam atlas daje tekst w dowolnym kolorze.
 */
public final class FontAtlas {

    private final VulkanContext ctx;
    private final Texture texture;
    private final long descriptorPool;
    private final long descriptorSet;

    public FontAtlas(VulkanContext ctx, CommandPool commandPool, DescriptorSetLayout layout, BakedFont font) {
        this.ctx = ctx;

        byte[] coverage = font.coverage();
        ByteBuffer rgba = MemoryUtil.memAlloc(coverage.length * 4);
        try {
            for (byte alpha : coverage) {
                rgba.put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).put(alpha);
            }
            rgba.flip();
            texture = Texture.fromRgba(ctx, commandPool, rgba, BakedFont.ATLAS_WIDTH, BakedFont.ATLAS_HEIGHT);
        } finally {
            MemoryUtil.memFree(rgba);
        }

        descriptorPool = createDescriptorPool(ctx);
        descriptorSet = allocateDescriptorSet(ctx, descriptorPool, layout, texture);
    }

    private static long createDescriptorPool(VulkanContext ctx) {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
            poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack);
            poolInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO);
            poolInfo.pPoolSizes(poolSizes);
            poolInfo.maxSets(1);

            LongBuffer pPool = stack.mallocLong(1);
            if (vkCreateDescriptorPool(ctx.device(), poolInfo, null, pPool) != VK_SUCCESS) {
                throw new RuntimeException("Nie udało się utworzyć descriptor poola atlasu czcionki");
            }
            return pPool.get(0);
        }
    }

    private static long allocateDescriptorSet(VulkanContext ctx, long pool, DescriptorSetLayout layout, Texture texture) {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack);
            allocInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO);
            allocInfo.descriptorPool(pool);
            allocInfo.pSetLayouts(stack.longs(layout.handle()));

            LongBuffer pSet = stack.mallocLong(1);
            if (vkAllocateDescriptorSets(ctx.device(), allocInfo, pSet) != VK_SUCCESS) {
                throw new RuntimeException("Nie udało się zaalokować descriptor setu atlasu czcionki");
            }
            long set = pSet.get(0);

            VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack);
            imageInfo.imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            imageInfo.imageView(texture.imageView());
            imageInfo.sampler(texture.sampler());

            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
            write.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(set)
                    .dstBinding(0)
                    .dstArrayElement(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    .pImageInfo(imageInfo);
            vkUpdateDescriptorSets(ctx.device(), write, null);
            return set;
        }
    }

    /** Descriptor set z teksturą atlasu - do zbindowania przed rysowaniem nakładki. */
    public long descriptorSet() {
        return descriptorSet;
    }

    public void destroy() {
        vkDestroyDescriptorPool(ctx.device(), descriptorPool, null);
        texture.destroy();
    }
}

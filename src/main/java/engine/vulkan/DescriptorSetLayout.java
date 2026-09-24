package engine.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Opisuje, jakie zasoby (uniform buffer, sampler tekstury...) widzi shader i
 * pod jakimi "binding" numerami - odpowiednik layout(binding = N) w GLSL.
 * Sam layout nie zawiera żadnych danych, to tylko "kształt"; realne dane
 * wpina się później przez DescriptorSets.
 */
public final class DescriptorSetLayout {

    private final VulkanContext ctx;
    private final long handle;

    /** Zestaw "per klatka" (set = 0): macierze view/proj w uniform bufferze. */
    public static DescriptorSetLayout perFrame(VulkanContext ctx) {
        return new DescriptorSetLayout(ctx,
                new int[] {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER},
                new int[] {VK_SHADER_STAGE_VERTEX_BIT});
    }

    /** Zestaw "per materiał" (set = 1): parametry materiału i dwie tekstury (patrz Material). */
    public static DescriptorSetLayout perMaterial(VulkanContext ctx) {
        return new DescriptorSetLayout(ctx,
                new int[] {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,
                        VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
                        VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER},
                new int[] {VK_SHADER_STAGE_FRAGMENT_BIT, VK_SHADER_STAGE_FRAGMENT_BIT, VK_SHADER_STAGE_FRAGMENT_BIT});
    }

    private DescriptorSetLayout(VulkanContext ctx, int[] types, int[] stages) {
        this.ctx = ctx;

        try (MemoryStack stack = stackPush()) {

            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(types.length, stack);
            for (int i = 0; i < types.length; i++) {
                VkDescriptorSetLayoutBinding binding = bindings.get(i);
                binding.binding(i);
                binding.descriptorCount(1);
                binding.descriptorType(types[i]);
                binding.pImmutableSamplers(null);
                binding.stageFlags(stages[i]);
            }

            VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack);
            layoutInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO);
            layoutInfo.pBindings(bindings);

            LongBuffer pLayout = stack.mallocLong(1);
            if (vkCreateDescriptorSetLayout(ctx.device(), layoutInfo, null, pLayout) != VK_SUCCESS) {
                throw new RuntimeException("Nie udało się utworzyć descriptor set layoutu");
            }

            handle = pLayout.get(0);
        }
    }

    public long handle() {
        return handle;
    }

    public void destroy() {
        vkDestroyDescriptorSetLayout(ctx.device(), handle, null);
    }
}

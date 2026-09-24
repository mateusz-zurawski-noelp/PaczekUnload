package engine.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Zestawy deskryptorów "per klatka" (set = 0): wskazują, KTÓRY uniform buffer
 * z danymi kamery ma być użyty. Potrzeba jednego zestawu na obraz swapchaina
 * (tak jak z uniform bufferami), a wszystkie alokuje się z jednej puli
 * (VkDescriptorPool). Zasoby materiału (tekstury itd.) mają własne zestawy,
 * patrz Material.
 */
public final class DescriptorSets {

    private final VulkanContext ctx;
    private final long pool;
    private final List<Long> sets;

    public DescriptorSets(VulkanContext ctx, DescriptorSetLayout layout, int count,
                            UniformBuffers uniformBuffers) {
        this.ctx = ctx;

        try (MemoryStack stack = stackPush()) {

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(1, stack);
            poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(count);

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack);
            poolInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO);
            poolInfo.pPoolSizes(poolSizes);
            poolInfo.maxSets(count);

            LongBuffer pPool = stack.mallocLong(1);
            if (vkCreateDescriptorPool(ctx.device(), poolInfo, null, pPool) != VK_SUCCESS) {
                throw new RuntimeException("Nie udało się utworzyć descriptor poola");
            }
            pool = pPool.get(0);

            LongBuffer layouts = stack.mallocLong(count);
            for (int i = 0; i < count; i++) {
                layouts.put(i, layout.handle());
            }

            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack);
            allocInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO);
            allocInfo.descriptorPool(pool);
            allocInfo.pSetLayouts(layouts);

            LongBuffer pSets = stack.mallocLong(count);
            if (vkAllocateDescriptorSets(ctx.device(), allocInfo, pSets) != VK_SUCCESS) {
                throw new RuntimeException("Nie udało się zaalokować descriptor setów");
            }

            sets = new ArrayList<>(count);

            VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
            bufferInfo.offset(0);
            bufferInfo.range(engine.scene.UniformBufferObject.SIZEOF);

            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(1, stack);

            VkWriteDescriptorSet uboWrite = writes.get(0);
            uboWrite.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
            uboWrite.dstBinding(0);
            uboWrite.dstArrayElement(0);
            uboWrite.descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
            uboWrite.descriptorCount(1);
            uboWrite.pBufferInfo(bufferInfo);

            for (int i = 0; i < count; i++) {
                long set = pSets.get(i);

                bufferInfo.buffer(uniformBuffers.buffer(i));
                uboWrite.dstSet(set);

                vkUpdateDescriptorSets(ctx.device(), writes, null);

                sets.add(set);
            }
        }
    }

    public long set(int index) {
        return sets.get(index);
    }

    public void destroy() {
        vkDestroyDescriptorPool(ctx.device(), pool, null);
    }
}

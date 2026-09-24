package engine.scene;

import engine.vulkan.DescriptorSetLayout;
import engine.vulkan.Texture;
import engine.vulkan.VulkanBuffers;
import engine.vulkan.VulkanContext;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Opis wyglądu powierzchni: to, CZYM rysujemy mesh (kolor, tekstury,
 * właściwości odbicia światła), w odróżnieniu od samej geometrii (Mesh).
 * Ten sam Material może być użyty przez wiele meshy i obiektów.
 *
 * Materiał jest niemutowalny - parametry trafiają do uniform bufferu raz,
 * przy tworzeniu (przez {@link #builder}). Dzięki temu nie trzeba ich
 * synchronizować z klatkami "w locie". Zmiana wyglądu = nowy Material.
 *
 * Tekstury są tylko referencjami - materiał ich nie zwalnia, bo mogą być
 * współdzielone przez kilka materiałów (odpowiada za nie właściciel, np. Engine).
 *
 * Layout uniform bufferu w GLSL (std140), set = 1, binding = 0:
 *
 *   vec4 baseColor;     // mnożnik koloru tekstury; alfa = nieprzezroczystość
 *   vec4 specular;      // rgb = kolor odblasku, a = połysk (shininess)
 *   vec4 emissive;      // rgb = światło własne, a = reflectivity (współczynnik odbicia)
 *   vec4 params;        // x = siła wpływu drugiej tekstury (0..1)
 *
 * Tekstury: binding = 1 -> baseColorTexture, binding = 2 -> secondTexture.
 *
 * Uwaga: specular/shininess/reflectivity są już przekazywane do shadera, ale
 * dopóki silnik nie ma normalnych i oświetlenia, nie mają żadnego wpływu na obraz.
 */
public final class Material {

    private static final int UNIFORM_SIZEOF = 4 * 4 * Float.BYTES;

    private final VulkanContext ctx;
    private final String name;

    private final Vector4fc baseColor;
    private final Vector3fc specularColor;
    private final float shininess;
    private final Vector3fc emissive;
    private final float reflectivity;
    private final float secondTextureBlend;
    private final Texture baseColorTexture;
    private final Texture secondTexture;

    private final long uniformBuffer;
    private final long uniformBufferMemory;
    private final long descriptorPool;
    private final long descriptorSet;

    public static Builder builder(VulkanContext ctx, DescriptorSetLayout materialLayout, Texture fallbackTexture) {
        return new Builder(ctx, materialLayout, fallbackTexture);
    }

    private Material(Builder b) {
        this.ctx = b.ctx;
        this.name = b.name;
        this.baseColor = new Vector4f(b.baseColor);
        this.specularColor = new Vector3f(b.specularColor);
        this.shininess = b.shininess;
        this.emissive = new Vector3f(b.emissive);
        this.reflectivity = b.reflectivity;
        this.secondTextureBlend = b.secondTextureBlend;
        this.baseColorTexture = b.baseColorTexture != null ? b.baseColorTexture : b.fallbackTexture;
        this.secondTexture = b.secondTexture != null ? b.secondTexture : b.fallbackTexture;

        try (MemoryStack stack = stackPush()) {

            LongBuffer pBuffer = stack.mallocLong(1);
            LongBuffer pMemory = stack.mallocLong(1);
            VulkanBuffers.createBuffer(ctx, UNIFORM_SIZEOF,
                    VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                    pBuffer, pMemory);
            uniformBuffer = pBuffer.get(0);
            uniformBufferMemory = pMemory.get(0);

            PointerBuffer data = stack.mallocPointer(1);
            vkMapMemory(ctx.device(), uniformBufferMemory, 0, UNIFORM_SIZEOF, 0, data);
            writeUniforms(data.getByteBuffer(0, UNIFORM_SIZEOF));
            vkUnmapMemory(ctx.device(), uniformBufferMemory);

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(2, stack);
            poolSizes.get(0).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1);
            poolSizes.get(1).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(2);

            VkDescriptorPoolCreateInfo poolInfo = VkDescriptorPoolCreateInfo.calloc(stack);
            poolInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO);
            poolInfo.pPoolSizes(poolSizes);
            poolInfo.maxSets(1);

            LongBuffer pPool = stack.mallocLong(1);
            if (vkCreateDescriptorPool(ctx.device(), poolInfo, null, pPool) != VK_SUCCESS) {
                throw new RuntimeException("Nie udało się utworzyć descriptor poola materiału " + name);
            }
            descriptorPool = pPool.get(0);

            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack);
            allocInfo.sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO);
            allocInfo.descriptorPool(descriptorPool);
            allocInfo.pSetLayouts(stack.longs(b.materialLayout.handle()));

            LongBuffer pSet = stack.mallocLong(1);
            if (vkAllocateDescriptorSets(ctx.device(), allocInfo, pSet) != VK_SUCCESS) {
                throw new RuntimeException("Nie udało się zaalokować descriptor setu materiału " + name);
            }
            descriptorSet = pSet.get(0);

            VkDescriptorBufferInfo.Buffer bufferInfo = VkDescriptorBufferInfo.calloc(1, stack);
            bufferInfo.buffer(uniformBuffer);
            bufferInfo.offset(0);
            bufferInfo.range(UNIFORM_SIZEOF);

            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(3, stack);

            writes.get(0)
                    .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(descriptorSet)
                    .dstBinding(0)
                    .dstArrayElement(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .descriptorCount(1)
                    .pBufferInfo(bufferInfo);

            writeSampler(stack, writes.get(1), 1, baseColorTexture);
            writeSampler(stack, writes.get(2), 2, secondTexture);

            vkUpdateDescriptorSets(ctx.device(), writes, null);
        }
    }

    private void writeSampler(MemoryStack stack, VkWriteDescriptorSet write, int binding, Texture texture) {
        VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack);
        imageInfo.imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        imageInfo.imageView(texture.imageView());
        imageInfo.sampler(texture.sampler());

        write.sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
        write.dstSet(descriptorSet);
        write.dstBinding(binding);
        write.dstArrayElement(0);
        write.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
        write.descriptorCount(1);
        write.pImageInfo(imageInfo);
    }

    private void writeUniforms(ByteBuffer target) {
        target.putFloat(baseColor.x()).putFloat(baseColor.y()).putFloat(baseColor.z()).putFloat(baseColor.w());
        target.putFloat(specularColor.x()).putFloat(specularColor.y()).putFloat(specularColor.z()).putFloat(shininess);
        target.putFloat(emissive.x()).putFloat(emissive.y()).putFloat(emissive.z()).putFloat(reflectivity);
        target.putFloat(secondTextureBlend).putFloat(0.0f).putFloat(0.0f).putFloat(0.0f);
    }

    public String name() {
        return name;
    }

    public Vector4fc baseColor() {
        return baseColor;
    }

    public Vector3fc specularColor() {
        return specularColor;
    }

    public float shininess() {
        return shininess;
    }

    public Vector3fc emissive() {
        return emissive;
    }

    public float reflectivity() {
        return reflectivity;
    }

    public float secondTextureBlend() {
        return secondTextureBlend;
    }

    public Texture baseColorTexture() {
        return baseColorTexture;
    }

    public Texture secondTexture() {
        return secondTexture;
    }

    /** Descriptor set (set = 1) do zbindowania przed rysowaniem meshy z tym materiałem. */
    public long descriptorSet() {
        return descriptorSet;
    }

    public void destroy() {
        vkDestroyDescriptorPool(ctx.device(), descriptorPool, null);
        vkDestroyBuffer(ctx.device(), uniformBuffer, null);
        vkFreeMemory(ctx.device(), uniformBufferMemory, null);
    }

    public static final class Builder {

        private final VulkanContext ctx;
        private final DescriptorSetLayout materialLayout;
        private final Texture fallbackTexture;

        private String name = "material";
        private final Vector4f baseColor = new Vector4f(1.0f, 1.0f, 1.0f, 1.0f);
        private final Vector3f specularColor = new Vector3f(1.0f, 1.0f, 1.0f);
        private float shininess = 32.0f;
        private final Vector3f emissive = new Vector3f(0.0f, 0.0f, 0.0f);
        private float reflectivity = 0.0f;
        private float secondTextureBlend = 1.0f;
        private Texture baseColorTexture;
        private Texture secondTexture;

        private Builder(VulkanContext ctx, DescriptorSetLayout materialLayout, Texture fallbackTexture) {
            this.ctx = ctx;
            this.materialLayout = materialLayout;
            this.fallbackTexture = fallbackTexture;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder baseColor(float r, float g, float b, float a) {
            baseColor.set(r, g, b, a);
            return this;
        }

        public Builder specularColor(float r, float g, float b) {
            specularColor.set(r, g, b);
            return this;
        }

        public Builder shininess(float shininess) {
            this.shininess = shininess;
            return this;
        }

        public Builder emissive(float r, float g, float b) {
            emissive.set(r, g, b);
            return this;
        }

        public Builder reflectivity(float reflectivity) {
            this.reflectivity = reflectivity;
            return this;
        }

        /** Główna tekstura koloru (albedo). Bez niej materiał używa tekstury zastępczej (białej). */
        public Builder baseColorTexture(Texture texture) {
            this.baseColorTexture = texture;
            return this;
        }

        /** Druga tekstura mnożona z pierwszą (np. lightmapa, detal, brud). Bez niej nie ma wpływu. */
        public Builder secondTexture(Texture texture) {
            this.secondTexture = texture;
            return this;
        }

        public Builder secondTextureBlend(float blend) {
            this.secondTextureBlend = blend;
            return this;
        }

        public Material build() {
            return new Material(this);
        }
    }
}

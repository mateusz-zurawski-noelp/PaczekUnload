package engine.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static engine.vulkan.VulkanImages.*;
import static org.lwjgl.stb.STBImage.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Tekstura 2D: obraz + widok obrazu + sampler (opisuje jak GPU filtruje/
 * powtarza teksturę przy próbkowaniu). Wgrywanie pikseli z CPU na GPU idzie
 * tą samą drogą co dane geometrii - przez bufor "staging" widoczny z CPU,
 * skopiowany później do szybkiej pamięci urządzenia (patrz Mesh dla
 * analogicznego wzorca przy buforach wierzchołków).
 */
public final class Texture {

    private static final int FORMAT = VK_FORMAT_R8G8B8A8_SRGB;

    private final VulkanContext ctx;
    private final long image;
    private final long imageMemory;
    private final long imageView;
    private final long sampler;

    /** Piksele RGBA8 gotowe do wgrania na GPU wraz ze sposobem ich zwolnienia. */
    private record Pixels(ByteBuffer data, int width, int height, Runnable release) {
    }

    public Texture(VulkanContext ctx, CommandPool commandPool, String resourcePath) {
        this(ctx, commandPool, decode(resourcePath));
    }

    /** Jednokolorowa tekstura 1x1 - neutralny zamiennik, gdy materiał nie ma danej tekstury (biała = brak wpływu). */
    public static Texture solidColor(VulkanContext ctx, CommandPool commandPool, int r, int g, int b, int a) {
        ByteBuffer data = MemoryUtil.memAlloc(4);
        data.put((byte) r).put((byte) g).put((byte) b).put((byte) a).flip();
        return new Texture(ctx, commandPool, new Pixels(data, 1, 1, () -> MemoryUtil.memFree(data)));
    }

    private static Pixels decode(String resourcePath) {
        try (MemoryStack stack = stackPush()) {
            ByteBuffer fileContents = readResourceToDirectBuffer(resourcePath);

            IntBuffer pWidth = stack.mallocInt(1);
            IntBuffer pHeight = stack.mallocInt(1);
            IntBuffer pChannels = stack.mallocInt(1);

            ByteBuffer pixels = stbi_load_from_memory(fileContents, pWidth, pHeight, pChannels, STBI_rgb_alpha);
            MemoryUtil.memFree(fileContents);

            if (pixels == null) {
                throw new RuntimeException("Nie udało się wczytać tekstury " + resourcePath + ": " + stbi_failure_reason());
            }

            return new Pixels(pixels, pWidth.get(0), pHeight.get(0), () -> stbi_image_free(pixels));
        }
    }

    private Texture(VulkanContext ctx, CommandPool commandPool, Pixels source) {
        this.ctx = ctx;

        try (MemoryStack stack = stackPush()) {

            ByteBuffer pixels = source.data();
            int width = source.width();
            int height = source.height();
            long imageSize = (long) width * height * 4;

            LongBuffer pStagingBuffer = stack.mallocLong(1);
            LongBuffer pStagingBufferMemory = stack.mallocLong(1);
            VulkanBuffers.createBuffer(ctx, imageSize,
                    VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                    pStagingBuffer, pStagingBufferMemory);

            PointerBuffer data = stack.mallocPointer(1);
            vkMapMemory(ctx.device(), pStagingBufferMemory.get(0), 0, imageSize, 0, data);
            MemoryUtil.memCopy(MemoryUtil.memAddress(pixels), data.get(0), imageSize);
            vkUnmapMemory(ctx.device(), pStagingBufferMemory.get(0));

            source.release().run();

            LongBuffer pImage = stack.mallocLong(1);
            LongBuffer pImageMemory = stack.mallocLong(1);
            createImage(ctx, width, height, FORMAT, VK_IMAGE_TILING_OPTIMAL,
                    VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                    pImage, pImageMemory);

            image = pImage.get(0);
            imageMemory = pImageMemory.get(0);

            transitionImageLayout(ctx, commandPool, image, FORMAT,
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

            copyBufferToImage(ctx, commandPool, pStagingBuffer.get(0), image, width, height);

            transitionImageLayout(ctx, commandPool, image, FORMAT,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

            vkDestroyBuffer(ctx.device(), pStagingBuffer.get(0), null);
            vkFreeMemory(ctx.device(), pStagingBufferMemory.get(0), null);
        }

        imageView = createImageView(ctx, image, FORMAT, VK_IMAGE_ASPECT_COLOR_BIT);
        sampler = createSampler(ctx);
    }

    private static long createSampler(VulkanContext ctx) {
        try (MemoryStack stack = stackPush()) {

            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack);
            samplerInfo.sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO);
            samplerInfo.magFilter(VK_FILTER_LINEAR);
            samplerInfo.minFilter(VK_FILTER_LINEAR);
            samplerInfo.addressModeU(VK_SAMPLER_ADDRESS_MODE_REPEAT);
            samplerInfo.addressModeV(VK_SAMPLER_ADDRESS_MODE_REPEAT);
            samplerInfo.addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT);
            samplerInfo.anisotropyEnable(true);
            samplerInfo.maxAnisotropy(16.0f);
            samplerInfo.borderColor(VK_BORDER_COLOR_INT_OPAQUE_BLACK);
            samplerInfo.unnormalizedCoordinates(false);
            samplerInfo.compareEnable(false);
            samplerInfo.compareOp(VK_COMPARE_OP_ALWAYS);
            samplerInfo.mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR);

            LongBuffer pSampler = stack.mallocLong(1);
            if (vkCreateSampler(ctx.device(), samplerInfo, null, pSampler) != VK_SUCCESS) {
                throw new RuntimeException("Nie udało się utworzyć samplera tekstury");
            }

            return pSampler.get(0);
        }
    }

    private static ByteBuffer readResourceToDirectBuffer(String resourcePath) {
        try (InputStream in = Texture.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new RuntimeException("Nie znaleziono tekstury na classpath: " + resourcePath);
            }
            byte[] bytes = in.readAllBytes();
            ByteBuffer buffer = MemoryUtil.memAlloc(bytes.length);
            buffer.put(bytes).flip();
            return buffer;
        } catch (IOException e) {
            throw new RuntimeException("Nie udało się wczytać tekstury " + resourcePath, e);
        }
    }

    public long imageView() {
        return imageView;
    }

    public long sampler() {
        return sampler;
    }

    public void destroy() {
        vkDestroySampler(ctx.device(), sampler, null);
        vkDestroyImageView(ctx.device(), imageView, null);
        vkDestroyImage(ctx.device(), image, null);
        vkFreeMemory(ctx.device(), imageMemory, null);
    }
}

package engine.vulkan;

import engine.core.Window;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static engine.vulkan.VulkanImages.createImageView;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Vulkan nie rysuje bezpośrednio na ekran - rysuje do jednego z kilku
 * obrazów w łańcuchu wymiany (swapchain), a system prezentacji wystawia
 * gotowy obraz na ekran, podając w zamian następny wolny. Dzięki temu GPU
 * może przygotowywać kolejną klatkę, gdy poprzednia jest jeszcze wyświetlana.
 *
 * Swapchain trzeba odtworzyć, kiedy zmieni się rozmiar okna - stąd ta klasa
 * jest tworzona na nowo przy każdym resize zamiast modyfikowana w miejscu
 * (patrz Engine.recreateSwapChain()).
 */
public final class SwapChain {

    private final VulkanContext ctx;

    private final long handle;
    private final List<Long> images;
    private final int imageFormat;
    private final VkExtent2D extent;
    private final List<Long> imageViews;

    public SwapChain(VulkanContext ctx, Window window) {
        this.ctx = ctx;

        try (MemoryStack stack = stackPush()) {

            SwapChainSupportDetails support = ctx.querySwapChainSupport(stack);

            VkSurfaceFormatKHR surfaceFormat = chooseSurfaceFormat(support.formats);
            int presentMode = choosePresentMode(support.presentModes);
            VkExtent2D chosenExtent = chooseExtent(stack, window, support.capabilities);

            IntBuffer imageCount = stack.ints(support.capabilities.minImageCount() + 1);
            if (support.capabilities.maxImageCount() > 0 && imageCount.get(0) > support.capabilities.maxImageCount()) {
                imageCount.put(0, support.capabilities.maxImageCount());
            }

            VkSwapchainCreateInfoKHR createInfo = VkSwapchainCreateInfoKHR.calloc(stack);
            createInfo.sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR);
            createInfo.surface(ctx.surface());
            createInfo.minImageCount(imageCount.get(0));
            createInfo.imageFormat(surfaceFormat.format());
            createInfo.imageColorSpace(surfaceFormat.colorSpace());
            createInfo.imageExtent(chosenExtent);
            createInfo.imageArrayLayers(1);
            createInfo.imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT);

            QueueFamilyIndices indices = VulkanContext.findQueueFamilies(ctx.physicalDevice(), ctx.surface());
            if (!indices.graphicsFamily.equals(indices.presentFamily)) {
                createInfo.imageSharingMode(VK_SHARING_MODE_CONCURRENT);
                createInfo.pQueueFamilyIndices(stack.ints(indices.graphicsFamily, indices.presentFamily));
            } else {
                createInfo.imageSharingMode(VK_SHARING_MODE_EXCLUSIVE);
            }

            createInfo.preTransform(support.capabilities.currentTransform());
            createInfo.compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR);
            createInfo.presentMode(presentMode);
            createInfo.clipped(true);
            createInfo.oldSwapchain(VK_NULL_HANDLE);

            LongBuffer pSwapChain = stack.longs(VK_NULL_HANDLE);
            if (vkCreateSwapchainKHR(ctx.device(), createInfo, null, pSwapChain) != VK_SUCCESS) {
                throw new RuntimeException("Nie udało się utworzyć swapchaina");
            }
            handle = pSwapChain.get(0);

            vkGetSwapchainImagesKHR(ctx.device(), handle, imageCount, null);
            LongBuffer pImages = stack.mallocLong(imageCount.get(0));
            vkGetSwapchainImagesKHR(ctx.device(), handle, imageCount, pImages);

            images = new ArrayList<>(imageCount.get(0));
            for (int i = 0; i < pImages.capacity(); i++) {
                images.add(pImages.get(i));
            }

            imageFormat = surfaceFormat.format();
            extent = VkExtent2D.create().set(chosenExtent);

            imageViews = new ArrayList<>(images.size());
            for (long image : images) {
                imageViews.add(createImageView(ctx, image, imageFormat, VK_IMAGE_ASPECT_COLOR_BIT));
            }
        }
    }

    public long handle() {
        return handle;
    }

    public int imageCount() {
        return images.size();
    }

    public int imageFormat() {
        return imageFormat;
    }

    public VkExtent2D extent() {
        return extent;
    }

    public List<Long> imageViews() {
        return imageViews;
    }

    private static VkSurfaceFormatKHR chooseSurfaceFormat(VkSurfaceFormatKHR.Buffer available) {
        return available.stream()
                .filter(f -> f.format() == VK_FORMAT_B8G8R8A8_SRGB)
                .filter(f -> f.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR)
                .findAny()
                .orElse(available.get(0));
    }

    private static int choosePresentMode(IntBuffer available) {
        for (int i = 0; i < available.capacity(); i++) {
            // MAILBOX = potrójne buforowanie: GPU nigdy nie czeka, a mimo to nie ma tearingu.
            if (available.get(i) == VK_PRESENT_MODE_MAILBOX_KHR) {
                return available.get(i);
            }
        }
        // FIFO jest zawsze dostępny - to odpowiednik VSync.
        return VK_PRESENT_MODE_FIFO_KHR;
    }

    private static VkExtent2D chooseExtent(MemoryStack stack, Window window, VkSurfaceCapabilitiesKHR capabilities) {

        if (capabilities.currentExtent().width() != 0xFFFFFFFF) {
            return capabilities.currentExtent();
        }

        int[] framebufferSize = window.framebufferSize();

        VkExtent2D actualExtent = VkExtent2D.malloc(stack).set(framebufferSize[0], framebufferSize[1]);

        VkExtent2D min = capabilities.minImageExtent();
        VkExtent2D max = capabilities.maxImageExtent();
        actualExtent.width(clamp(min.width(), max.width(), actualExtent.width()));
        actualExtent.height(clamp(min.height(), max.height(), actualExtent.height()));

        return actualExtent;
    }

    private static int clamp(int min, int max, int value) {
        return Math.max(min, Math.min(max, value));
    }

    public void destroy() {
        imageViews.forEach(view -> vkDestroyImageView(ctx.device(), view, null));
        vkDestroySwapchainKHR(ctx.device(), handle, null);
    }
}

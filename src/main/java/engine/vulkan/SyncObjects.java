package engine.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Zarządza obiektami synchronizacji CPU<->GPU dla klatek "w locie"
 * (patrz Frame). maxFramesInFlight (z konfiguracji silnika) ogranicza, ile klatek CPU może
 * przygotować, zanim zacznie czekać na GPU - więcej niż 1 pozwala CPU i GPU
 * pracować równolegle, ale zbyt dużo zwiększa opóźnienie wejścia (input lag).
 *
 * imagesInFlight pilnuje osobnego przypadku: liczba obrazów swapchaina i
 * liczba klatek w locie nie muszą się pokrywać, więc trzeba wiedzieć, czy
 * konkretny obraz nie jest wciąż używany przez inną, wcześniejszą klatkę.
 */
public final class SyncObjects {

    private final VulkanContext ctx;
    private final int maxFramesInFlight;
    private final List<Frame> inFlightFrames;
    private final Map<Integer, Frame> imagesInFlight;
    private int currentFrame;

    public SyncObjects(VulkanContext ctx, int swapChainImageCount, int maxFramesInFlight) {
        this.ctx = ctx;
        this.maxFramesInFlight = maxFramesInFlight;
        this.inFlightFrames = new ArrayList<>(maxFramesInFlight);
        this.imagesInFlight = new HashMap<>(swapChainImageCount);

        try (MemoryStack stack = stackPush()) {

            VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack);
            semaphoreInfo.sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);

            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack);
            fenceInfo.sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
            fenceInfo.flags(VK_FENCE_CREATE_SIGNALED_BIT);

            for (int i = 0; i < maxFramesInFlight; i++) {
                LongBuffer pImageAvailable = stack.mallocLong(1);
                LongBuffer pRenderFinished = stack.mallocLong(1);
                LongBuffer pFence = stack.mallocLong(1);

                if (vkCreateSemaphore(ctx.device(), semaphoreInfo, null, pImageAvailable) != VK_SUCCESS
                        || vkCreateSemaphore(ctx.device(), semaphoreInfo, null, pRenderFinished) != VK_SUCCESS
                        || vkCreateFence(ctx.device(), fenceInfo, null, pFence) != VK_SUCCESS) {
                    throw new RuntimeException("Nie udało się utworzyć obiektów synchronizacji dla klatki " + i);
                }

                inFlightFrames.add(new Frame(pImageAvailable.get(0), pRenderFinished.get(0), pFence.get(0)));
            }
        }
    }

    public Frame currentFrame() {
        return inFlightFrames.get(currentFrame);
    }

    public void waitForFrame(Frame frame) {
        // Własna ramka stosu: wywołanie poza drawFrame() inaczej alokowałoby
        // z bazowego stosu wątku, który nigdy nie jest zwalniany (wyciek -> OOM).
        try (MemoryStack stack = stackPush()) {
            vkWaitForFences(ctx.device(), frame.pFence(), true, Long.MAX_VALUE);
        }
    }

    public void waitIfImageInUse(int imageIndex) {
        Frame frameUsingImage = imagesInFlight.get(imageIndex);
        if (frameUsingImage != null) {
            waitForFrame(frameUsingImage);
        }
    }

    public void markImageInUse(int imageIndex, Frame frame) {
        imagesInFlight.put(imageIndex, frame);
    }

    public void advance() {
        currentFrame = (currentFrame + 1) % maxFramesInFlight;
    }

    public void destroy() {
        inFlightFrames.forEach(frame -> {
            vkDestroySemaphore(ctx.device(), frame.renderFinishedSemaphore(), null);
            vkDestroySemaphore(ctx.device(), frame.imageAvailableSemaphore(), null);
            vkDestroyFence(ctx.device(), frame.fence(), null);
        });
    }
}

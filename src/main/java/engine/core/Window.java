package engine.core;

import engine.log.Log;
import org.lwjgl.glfw.GLFWErrorCallback;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Cienka nakładka na okno GLFW. Vulkan sam w sobie nic nie wie o oknach ani
 * wejściu - tym zajmuje się GLFW, a Vulkan dostaje od niego jedynie
 * VkSurfaceKHR (patrz {@link engine.vulkan.VulkanContext#createSurface}).
 */
public final class Window {

    private final long handle;
    private boolean framebufferResized;

    public Window(int width, int height, String title) {
        // Błędy GLFW (np. brak wsparcia Vulkana, zły tryb okna) domyślnie giną bez śladu -
        // przekierowujemy je do logu. Callback ustawia się jeszcze przed glfwInit.
        glfwSetErrorCallback((error, description) ->
                Log.error("GLFW", "Błąd 0x" + Integer.toHexString(error) + ": " + GLFWErrorCallback.getDescription(description)));

        if (!glfwInit()) {
            throw new RuntimeException("Nie udało się zainicjalizować GLFW");
        }

        // GLFW domyślnie tworzy kontekst OpenGL - my go nie potrzebujemy.
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

        handle = glfwCreateWindow(width, height, title, NULL, NULL);
        if (handle == NULL) {
            throw new RuntimeException("Nie udało się utworzyć okna GLFW");
        }

        glfwSetFramebufferSizeCallback(handle, (window, w, h) -> framebufferResized = true);
    }

    public long handle() {
        return handle;
    }

    public boolean shouldClose() {
        return glfwWindowShouldClose(handle);
    }

    /** Prośba o zamknięcie - pętla główna skończy się po bieżącej klatce. */
    public void requestClose() {
        glfwSetWindowShouldClose(handle, true);
    }

    public static void pollEvents() {
        glfwPollEvents();
    }

    public static void waitEvents() {
        glfwWaitEvents();
    }

    public int[] framebufferSize() {
        int[] w = new int[1];
        int[] h = new int[1];
        glfwGetFramebufferSize(handle, w, h);
        return new int[] {w[0], h[0]};
    }

    public boolean consumeFramebufferResized() {
        boolean was = framebufferResized;
        framebufferResized = false;
        return was;
    }

    public void destroy() {
        // Callbacki (resize, wejście...) to natywne obiekty LWJGL - trzeba je zwolnić ręcznie.
        glfwFreeCallbacks(handle);
        glfwDestroyWindow(handle);
        glfwTerminate();
        GLFWErrorCallback previous = glfwSetErrorCallback(null);
        if (previous != null) {
            previous.free();
        }
    }
}

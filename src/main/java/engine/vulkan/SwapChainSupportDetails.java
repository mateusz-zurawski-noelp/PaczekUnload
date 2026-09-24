package engine.vulkan;

import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;

import java.nio.IntBuffer;

/**
 * Vulkan nie zakłada nic o tym, jak dana karta graficzna potrafi wyświetlać
 * obrazy na danej powierzchni (VkSurfaceKHR) - trzeba to sprawdzić w
 * runtime: jakie formaty pikseli, tryby prezentacji i limity obsługuje.
 */
final class SwapChainSupportDetails {

    VkSurfaceCapabilitiesKHR capabilities;
    VkSurfaceFormatKHR.Buffer formats;
    IntBuffer presentModes;
}

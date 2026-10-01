package engine.vulkan;

import engine.core.Window;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Set;
import java.util.stream.Collectors;

import static org.lwjgl.glfw.GLFWVulkan.glfwCreateWindowSurface;
import static org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Właściciel wszystkich "globalnych" obiektów Vulkana, które żyją tak długo
 * jak cała aplikacja i od których zależy reszta silnika:
 *
 *   VkInstance -> VkSurfaceKHR -> VkPhysicalDevice -> VkDevice -> VkQueue
 *
 * To jest odpowiednik "połączenia z GPU" - reszta klas w pakiecie
 * engine.vulkan bierze ten kontekst w konstruktorze i tworzy na jego bazie
 * kolejne warstwy (swapchain, pipeline, bufory...).
 */
public final class VulkanContext {

    private static final Set<String> VALIDATION_LAYERS = Set.of("VK_LAYER_KHRONOS_validation");

    // Warstwy walidacyjne (VK_LAYER_KHRONOS_validation) to pakiet Vulkan SDK
    // wykrywający błędne użycie API - bezcenny przy nauce, ale nie zawsze
    // zainstalowany (np. `apt install vulkan-validationlayers` na Ubuntu).
    // Zamiast twardo wymagać go, po prostu ostrzegamy i jedziemy dalej bez
    // walidacji, żeby silnik działał "out of the box" wszędzie tam, gdzie
    // jest jakikolwiek sterownik Vulkana. Wyłączyć je można w konfiguracji
    // silnika (graphics.validationLayers).
    private static boolean resolveValidationLayersEnabled(boolean requested) {
        if (!requested) {
            return false;
        }
        if (validationLayersSupported()) {
            return true;
        }
        System.err.println("[Vulkan] Warstwy walidacyjne (VK_LAYER_KHRONOS_validation) nie są zainstalowane - " +
                "uruchamiam bez nich. Zainstaluj Vulkan SDK albo pakiet vulkan-validationlayers, " +
                "żeby dostawać czytelne komunikaty o błędnym użyciu API.");
        return false;
    }
    private static final Set<String> DEVICE_EXTENSIONS = Set.of(VK_KHR_SWAPCHAIN_EXTENSION_NAME);

    private final VkInstance instance;
    private final long debugMessenger;
    private final long surface;
    private final VkPhysicalDevice physicalDevice;
    private final VkDevice device;
    private final VkQueue graphicsQueue;
    private final VkQueue presentQueue;
    private final int graphicsQueueFamily;
    private final int presentQueueFamily;
    private final boolean validationLayersEnabled;

    public VulkanContext(Window window, boolean validationLayersRequested) {
        validationLayersEnabled = resolveValidationLayersEnabled(validationLayersRequested);
        instance = createInstance(validationLayersEnabled);
        debugMessenger = validationLayersEnabled ? setupDebugMessenger(instance) : VK_NULL_HANDLE;
        surface = createSurface(instance, window);
        physicalDevice = pickPhysicalDevice(instance, surface);

        QueueFamilyIndices indices = findQueueFamilies(physicalDevice, surface);
        graphicsQueueFamily = indices.graphicsFamily;
        presentQueueFamily = indices.presentFamily;

        device = createLogicalDevice(physicalDevice, indices, validationLayersEnabled);

        try (MemoryStack stack = stackPush()) {
            PointerBuffer pQueue = stack.pointers(VK_NULL_HANDLE);

            vkGetDeviceQueue(device, graphicsQueueFamily, 0, pQueue);
            graphicsQueue = new VkQueue(pQueue.get(0), device);

            vkGetDeviceQueue(device, presentQueueFamily, 0, pQueue);
            presentQueue = new VkQueue(pQueue.get(0), device);
        }
    }

    // ===== getters ===== //

    public VkInstance instance() {
        return instance;
    }

    public long surface() {
        return surface;
    }

    public VkPhysicalDevice physicalDevice() {
        return physicalDevice;
    }

    public VkDevice device() {
        return device;
    }

    public VkQueue graphicsQueue() {
        return graphicsQueue;
    }

    public VkQueue presentQueue() {
        return presentQueue;
    }

    public int graphicsQueueFamily() {
        return graphicsQueueFamily;
    }

    // ===== instance & debug messenger ===== //

    private static VkInstance createInstance(boolean validationLayersEnabled) {

        try (MemoryStack stack = stackPush()) {

            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack);
            appInfo.sType(VK_STRUCTURE_TYPE_APPLICATION_INFO);
            appInfo.pApplicationName(stack.UTF8Safe("Vulkan Engine"));
            appInfo.applicationVersion(VK_MAKE_VERSION(1, 0, 0));
            appInfo.pEngineName(stack.UTF8Safe("No Engine"));
            appInfo.engineVersion(VK_MAKE_VERSION(1, 0, 0));
            appInfo.apiVersion(VK_API_VERSION_1_0);

            VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack);
            createInfo.sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO);
            createInfo.pApplicationInfo(appInfo);
            createInfo.ppEnabledExtensionNames(requiredExtensions(stack, validationLayersEnabled));

            if (validationLayersEnabled) {
                createInfo.ppEnabledLayerNames(asPointerBuffer(stack, VALIDATION_LAYERS));

                VkDebugUtilsMessengerCreateInfoEXT debugCreateInfo = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack);
                populateDebugMessengerCreateInfo(debugCreateInfo);
                createInfo.pNext(debugCreateInfo.address());
            }

            PointerBuffer pInstance = stack.mallocPointer(1);
            if (vkCreateInstance(createInfo, null, pInstance) != VK_SUCCESS) {
                throw new RuntimeException("Nie udało się utworzyć VkInstance");
            }

            return new VkInstance(pInstance.get(0), createInfo);
        }
    }

    private static PointerBuffer requiredExtensions(MemoryStack stack, boolean validationLayersEnabled) {

        PointerBuffer glfwExtensions = glfwGetRequiredInstanceExtensions();
        if (glfwExtensions == null) {
            throw new RuntimeException("GLFW zgłasza brak wsparcia dla Vulkana na tym systemie");
        }

        if (!validationLayersEnabled) {
            return glfwExtensions;
        }

        PointerBuffer extensions = stack.mallocPointer(glfwExtensions.capacity() + 1);
        extensions.put(glfwExtensions);
        extensions.put(stack.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME));
        return extensions.rewind();
    }

    private static boolean validationLayersSupported() {
        try (MemoryStack stack = stackPush()) {

            IntBuffer layerCount = stack.ints(0);
            vkEnumerateInstanceLayerProperties(layerCount, null);

            VkLayerProperties.Buffer availableLayers = VkLayerProperties.malloc(layerCount.get(0), stack);
            vkEnumerateInstanceLayerProperties(layerCount, availableLayers);

            Set<String> availableNames = availableLayers.stream()
                    .map(VkLayerProperties::layerNameString)
                    .collect(Collectors.toSet());

            return availableNames.containsAll(VALIDATION_LAYERS);
        }
    }

    private static void populateDebugMessengerCreateInfo(VkDebugUtilsMessengerCreateInfoEXT createInfo) {
        createInfo.sType(VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT);
        createInfo.messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT
                | VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT
                | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT);
        createInfo.messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT
                | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                | VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT);
        createInfo.pfnUserCallback(VulkanContext::debugCallback);
    }

    private static int debugCallback(int messageSeverity, int messageType, long pCallbackData, long pUserData) {
        VkDebugUtilsMessengerCallbackDataEXT callbackData = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData);
        System.err.println("[validation layer] " + callbackData.pMessageString());
        return VK_FALSE;
    }

    private static long setupDebugMessenger(VkInstance instance) {
        try (MemoryStack stack = stackPush()) {

            VkDebugUtilsMessengerCreateInfoEXT createInfo = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack);
            populateDebugMessengerCreateInfo(createInfo);

            LongBuffer pDebugMessenger = stack.longs(VK_NULL_HANDLE);
            if (vkGetInstanceProcAddr(instance, "vkCreateDebugUtilsMessengerEXT") == NULL
                    || vkCreateDebugUtilsMessengerEXT(instance, createInfo, null, pDebugMessenger) != VK_SUCCESS) {
                throw new RuntimeException("Nie udało się ustawić debug messengera");
            }

            return pDebugMessenger.get(0);
        }
    }

    // ===== surface ===== //

    private static long createSurface(VkInstance instance, Window window) {
        try (MemoryStack stack = stackPush()) {
            LongBuffer pSurface = stack.longs(VK_NULL_HANDLE);
            if (glfwCreateWindowSurface(instance, window.handle(), null, pSurface) != VK_SUCCESS) {
                throw new RuntimeException("Nie udało się utworzyć VkSurfaceKHR");
            }
            return pSurface.get(0);
        }
    }

    // ===== physical device ===== //

    private static VkPhysicalDevice pickPhysicalDevice(VkInstance instance, long surface) {
        try (MemoryStack stack = stackPush()) {

            IntBuffer deviceCount = stack.ints(0);
            vkEnumeratePhysicalDevices(instance, deviceCount, null);

            if (deviceCount.get(0) == 0) {
                throw new RuntimeException("Żadna karta graficzna w tym systemie nie wspiera Vulkana");
            }

            PointerBuffer devices = stack.mallocPointer(deviceCount.get(0));
            vkEnumeratePhysicalDevices(instance, deviceCount, devices);

            for (int i = 0; i < devices.capacity(); i++) {
                VkPhysicalDevice candidate = new VkPhysicalDevice(devices.get(i), instance);
                if (isDeviceSuitable(candidate, surface)) {
                    return candidate;
                }
            }

            throw new RuntimeException("Żadna z dostępnych kart graficznych nie spełnia wymagań silnika " +
                    "(swapchain + anizotropowe próbkowanie tekstur)");
        }
    }

    private static boolean isDeviceSuitable(VkPhysicalDevice device, long surface) {

        QueueFamilyIndices indices = findQueueFamilies(device, surface);
        boolean extensionsSupported = checkDeviceExtensionSupport(device);
        boolean swapChainAdequate = false;
        boolean anisotropySupported;

        try (MemoryStack stack = stackPush()) {
            if (extensionsSupported) {
                SwapChainSupportDetails swapChainSupport = querySwapChainSupport(device, surface, stack);
                swapChainAdequate = swapChainSupport.formats.hasRemaining() && swapChainSupport.presentModes.hasRemaining();
            }

            VkPhysicalDeviceFeatures supportedFeatures = VkPhysicalDeviceFeatures.malloc(stack);
            vkGetPhysicalDeviceFeatures(device, supportedFeatures);
            anisotropySupported = supportedFeatures.samplerAnisotropy();
        }

        return indices.isComplete() && extensionsSupported && swapChainAdequate && anisotropySupported;
    }

    private static boolean checkDeviceExtensionSupport(VkPhysicalDevice device) {
        try (MemoryStack stack = stackPush()) {

            IntBuffer extensionCount = stack.ints(0);
            vkEnumerateDeviceExtensionProperties(device, (String) null, extensionCount, null);

            VkExtensionProperties.Buffer availableExtensions = VkExtensionProperties.malloc(extensionCount.get(0), stack);
            vkEnumerateDeviceExtensionProperties(device, (String) null, extensionCount, availableExtensions);

            Set<String> availableNames = availableExtensions.stream()
                    .map(VkExtensionProperties::extensionNameString)
                    .collect(Collectors.toSet());

            return availableNames.containsAll(DEVICE_EXTENSIONS);
        }
    }

    static QueueFamilyIndices findQueueFamilies(VkPhysicalDevice device, long surface) {

        QueueFamilyIndices indices = new QueueFamilyIndices();

        try (MemoryStack stack = stackPush()) {

            IntBuffer queueFamilyCount = stack.ints(0);
            vkGetPhysicalDeviceQueueFamilyProperties(device, queueFamilyCount, null);

            VkQueueFamilyProperties.Buffer queueFamilies = VkQueueFamilyProperties.malloc(queueFamilyCount.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(device, queueFamilyCount, queueFamilies);

            IntBuffer presentSupport = stack.ints(VK_FALSE);

            for (int i = 0; i < queueFamilies.capacity() && !indices.isComplete(); i++) {

                if ((queueFamilies.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) {
                    indices.graphicsFamily = i;
                }

                vkGetPhysicalDeviceSurfaceSupportKHR(device, i, surface, presentSupport);
                if (presentSupport.get(0) == VK_TRUE) {
                    indices.presentFamily = i;
                }
            }
        }

        return indices;
    }

    /** Wynik korzysta z pamięci `stack` - musi być użyty zanim wywołujący zamknie swój MemoryStack. */
    static SwapChainSupportDetails querySwapChainSupport(VkPhysicalDevice device, long surface, MemoryStack stack) {

        SwapChainSupportDetails details = new SwapChainSupportDetails();

        details.capabilities = VkSurfaceCapabilitiesKHR.malloc(stack);
        vkGetPhysicalDeviceSurfaceCapabilitiesKHR(device, surface, details.capabilities);

        IntBuffer count = stack.ints(0);

        vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface, count, null);
        if (count.get(0) != 0) {
            details.formats = VkSurfaceFormatKHR.malloc(count.get(0), stack);
            vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface, count, details.formats);
        }

        vkGetPhysicalDeviceSurfacePresentModesKHR(device, surface, count, null);
        if (count.get(0) != 0) {
            details.presentModes = stack.mallocInt(count.get(0));
            vkGetPhysicalDeviceSurfacePresentModesKHR(device, surface, count, details.presentModes);
        }

        return details;
    }

    public SwapChainSupportDetails querySwapChainSupport(MemoryStack stack) {
        return querySwapChainSupport(physicalDevice, surface, stack);
    }

    // ===== logical device ===== //

    private static VkDevice createLogicalDevice(VkPhysicalDevice physicalDevice, QueueFamilyIndices indices,
                                                boolean validationLayersEnabled) {
        try (MemoryStack stack = stackPush()) {

            int[] uniqueQueueFamilies = indices.unique();

            VkDeviceQueueCreateInfo.Buffer queueCreateInfos = VkDeviceQueueCreateInfo.calloc(uniqueQueueFamilies.length, stack);
            for (int i = 0; i < uniqueQueueFamilies.length; i++) {
                queueCreateInfos.get(i)
                        .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                        .queueFamilyIndex(uniqueQueueFamilies[i])
                        .pQueuePriorities(stack.floats(1.0f));
            }

            VkPhysicalDeviceFeatures deviceFeatures = VkPhysicalDeviceFeatures.calloc(stack);
            deviceFeatures.samplerAnisotropy(true);

            VkDeviceCreateInfo createInfo = VkDeviceCreateInfo.calloc(stack);
            createInfo.sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO);
            createInfo.pQueueCreateInfos(queueCreateInfos);
            createInfo.pEnabledFeatures(deviceFeatures);
            createInfo.ppEnabledExtensionNames(asPointerBuffer(stack, DEVICE_EXTENSIONS));

            if (validationLayersEnabled) {
                createInfo.ppEnabledLayerNames(asPointerBuffer(stack, VALIDATION_LAYERS));
            }

            PointerBuffer pDevice = stack.pointers(VK_NULL_HANDLE);
            if (vkCreateDevice(physicalDevice, createInfo, null, pDevice) != VK_SUCCESS) {
                throw new RuntimeException("Nie udało się utworzyć logicznego urządzenia (VkDevice)");
            }

            return new VkDevice(pDevice.get(0), physicalDevice, createInfo);
        }
    }

    private static PointerBuffer asPointerBuffer(MemoryStack stack, Set<String> strings) {
        PointerBuffer buffer = stack.mallocPointer(strings.size());
        strings.stream().map(stack::UTF8).forEach(buffer::put);
        return buffer.rewind();
    }

    // ===== cleanup ===== //

    public void destroy() {
        vkDestroyDevice(device, null);

        if (validationLayersEnabled && vkGetInstanceProcAddr(instance, "vkDestroyDebugUtilsMessengerEXT") != NULL) {
            vkDestroyDebugUtilsMessengerEXT(instance, debugMessenger, null);
        }

        vkDestroySurfaceKHR(instance, surface, null);
        vkDestroyInstance(instance, null);
    }
}

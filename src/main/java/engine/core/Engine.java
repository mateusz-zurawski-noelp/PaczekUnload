package engine.core;

import engine.scene.Camera;
import engine.scene.FpsCounter;
import engine.scene.Material;
import engine.scene.Mesh;
import engine.scene.Renderable;
import engine.scene.SevenSegmentDigits;
import engine.scene.UniformBufferObject;
import engine.vulkan.*;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkSubmitInfo;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.glfwGetTime;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Punkt spięcia całego silnika. Tworzy okno i kontekst Vulkana, buduje
 * wszystkie obiekty potrzebne do renderu, i napędza pętlę główną.
 *
 * Kluczowy podział, który warto zrozumieć ucząc się Vulkana: część zasobów
 * żyje tak długo jak aplikacja (okno, urządzenie, tekstura, geometria...),
 * a część zależy od rozmiaru okna i musi być odtwarzana przy każdym resize
 * (swapchain, render pass, pipeline, framebuffery...) - patrz
 * {@link #createSwapChainDependentObjects()} i {@link #recreateSwapChain()}.
 */
public final class Engine {

    private static final int DEFAULT_WIDTH = 1280;
    private static final int DEFAULT_HEIGHT = 720;

    private static final String MODEL_PATH = "models/chalet.obj";
    private static final String TEXTURE_PATH = "textures/chalet.jpg";
    private static final String VERTEX_SHADER_PATH = "shaders/model.vert";
    private static final String FRAGMENT_SHADER_PATH = "shaders/model.frag";
    private static final String OVERLAY_VERTEX_SHADER_PATH = "shaders/overlay.vert";
    private static final String OVERLAY_FRAGMENT_SHADER_PATH = "shaders/overlay.frag";

    // Zasoby żyjące przez cały czas trwania aplikacji
    private Window window;
    private VulkanContext ctx;
    private CommandPool commandPool;
    private DescriptorSetLayout frameLayout;
    private DescriptorSetLayout materialLayout;
    private Texture whiteTexture;
    private Texture chaletTexture;
    private Mesh chaletMesh;
    private Material chaletMaterial;
    private Renderable chalet;
    private final List<Renderable> renderables = new ArrayList<>();
    private Camera camera;
    private SyncObjects sync;
    private FpsCounter fpsCounter;
    private OverlayMesh overlayMesh;

    // Zasoby zależne od swapchaina - odtwarzane przy resize okna
    private SwapChain swapChain;
    private RenderPass renderPass;
    private DepthResources depthResources;
    private GraphicsPipeline pipeline;
    private OverlayPipeline overlayPipeline;
    private Framebuffers framebuffers;
    private UniformBuffers uniformBuffers;
    private DescriptorSets descriptorSets;
    private CommandBuffers commandBuffers;

    public void run() {
        init();
        loop();
        cleanup();
    }

    private void init() {
        window = new Window(DEFAULT_WIDTH, DEFAULT_HEIGHT, "Vulkan Engine");
        ctx = new VulkanContext(window);
        commandPool = new CommandPool(ctx);

        frameLayout = DescriptorSetLayout.perFrame(ctx);
        materialLayout = DescriptorSetLayout.perMaterial(ctx);

        whiteTexture = Texture.solidColor(ctx, commandPool, 255, 255, 255, 255);
        chaletTexture = new Texture(ctx, commandPool, TEXTURE_PATH);
        chaletMesh = Mesh.loadFromFile(ctx, commandPool, MODEL_PATH);
        chaletMaterial = Material.builder(ctx, materialLayout, whiteTexture)
                .name("chalet")
                .baseColorTexture(chaletTexture)
                .build();

        chalet = new Renderable("chalet").add(chaletMesh, chaletMaterial);
        renderables.add(chalet);

        camera = new Camera(new Vector3f(2.0f, 2.0f, 2.0f), new Vector3f(0.0f, 0.0f, 0.0f), new Vector3f(0.0f, 0.0f, 1.0f));

        fpsCounter = new FpsCounter();
        overlayMesh = new OverlayMesh(ctx, SevenSegmentDigits.VERTEX_COUNT);

        createSwapChainDependentObjects();

        sync = new SyncObjects(ctx, swapChain.imageCount());
    }

    private void createSwapChainDependentObjects() {
        swapChain = new SwapChain(ctx, window);
        renderPass = new RenderPass(ctx, swapChain.imageFormat(), VulkanImages.findDepthFormat(ctx));
        depthResources = new DepthResources(ctx, commandPool, swapChain.extent());
        pipeline = new GraphicsPipeline(ctx, renderPass, frameLayout, materialLayout, swapChain.extent(),
                VERTEX_SHADER_PATH, FRAGMENT_SHADER_PATH);
        overlayPipeline = new OverlayPipeline(ctx, renderPass, swapChain.extent(),
                OVERLAY_VERTEX_SHADER_PATH, OVERLAY_FRAGMENT_SHADER_PATH);
        framebuffers = new Framebuffers(ctx, renderPass, swapChain.imageViews(), depthResources.imageView(), swapChain.extent());
        uniformBuffers = new UniformBuffers(ctx, swapChain.imageCount());
        descriptorSets = new DescriptorSets(ctx, frameLayout, swapChain.imageCount(), uniformBuffers);
        commandBuffers = new CommandBuffers(ctx, commandPool, framebuffers, renderPass, swapChain.extent(),
                pipeline, descriptorSets, overlayPipeline, overlayMesh);
    }

    private void cleanupSwapChainDependentObjects() {
        commandBuffers.destroy();
        descriptorSets.destroy();
        uniformBuffers.destroy();
        framebuffers.destroy();
        overlayPipeline.destroy();
        pipeline.destroy();
        depthResources.destroy();
        renderPass.destroy();
        swapChain.destroy();
    }

    private void recreateSwapChain() {

        // Okno zminimalizowane -> rozmiar 0x0. Nie ma sensu (ani prawnie
        // można) tworzyć swapchaina o zerowym rozmiarze, więc czekamy.
        int[] size = window.framebufferSize();
        while (size[0] == 0 && size[1] == 0) {
            Window.waitEvents();
            size = window.framebufferSize();
        }

        vkDeviceWaitIdle(ctx.device());

        cleanupSwapChainDependentObjects();
        createSwapChainDependentObjects();
    }

    private void loop() {
        while (!window.shouldClose()) {
            Window.pollEvents();
            drawFrame();
        }
        vkDeviceWaitIdle(ctx.device());
    }

    private void drawFrame() {

        Frame frame = sync.currentFrame();
        sync.waitForFrame(frame);

        try (MemoryStack stack = stackPush()) {

            IntBuffer pImageIndex = stack.mallocInt(1);
            int acquireResult = vkAcquireNextImageKHR(ctx.device(), swapChain.handle(), Long.MAX_VALUE,
                    frame.imageAvailableSemaphore(), VK_NULL_HANDLE, pImageIndex);

            if (acquireResult == VK_ERROR_OUT_OF_DATE_KHR) {
                recreateSwapChain();
                return;
            } else if (acquireResult != VK_SUCCESS && acquireResult != VK_SUBOPTIMAL_KHR) {
                throw new RuntimeException("Nie udało się pobrać obrazu ze swapchaina");
            }

            int imageIndex = pImageIndex.get(0);

            // Dopiero gdy GPU skończył używać tego obrazu, wolno nadpisać jego uniform buffer i command buffer.
            sync.waitIfImageInUse(imageIndex);
            sync.markImageInUse(imageIndex, frame);

            fpsCounter.onFrameRendered();
            updateScene();
            updateUniformBuffer(imageIndex);
            overlayMesh.update(SevenSegmentDigits.buildFps(fpsCounter.fps(), swapChain.extent().width(), swapChain.extent().height()));
            commandBuffers.record(imageIndex, renderables);

            VkSubmitInfo submitInfo = VkSubmitInfo.calloc(stack);
            submitInfo.sType(VK_STRUCTURE_TYPE_SUBMIT_INFO);
            submitInfo.waitSemaphoreCount(1);
            submitInfo.pWaitSemaphores(frame.pImageAvailableSemaphore());
            submitInfo.pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT));
            submitInfo.pSignalSemaphores(frame.pRenderFinishedSemaphore());
            submitInfo.pCommandBuffers(stack.pointers(commandBuffers.get(imageIndex)));

            vkResetFences(ctx.device(), frame.pFence());

            if (vkQueueSubmit(ctx.graphicsQueue(), submitInfo, frame.fence()) != VK_SUCCESS) {
                vkResetFences(ctx.device(), frame.pFence());
                throw new RuntimeException("Nie udało się wysłać command buffera do kolejki");
            }

            VkPresentInfoKHR presentInfo = VkPresentInfoKHR.calloc(stack);
            presentInfo.sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR);
            presentInfo.pWaitSemaphores(frame.pRenderFinishedSemaphore());
            presentInfo.swapchainCount(1);
            presentInfo.pSwapchains(stack.longs(swapChain.handle()));
            presentInfo.pImageIndices(pImageIndex);

            int presentResult = vkQueuePresentKHR(ctx.presentQueue(), presentInfo);

            if (presentResult == VK_ERROR_OUT_OF_DATE_KHR || presentResult == VK_SUBOPTIMAL_KHR
                    || window.consumeFramebufferResized()) {
                recreateSwapChain();
            } else if (presentResult != VK_SUCCESS) {
                throw new RuntimeException("Nie udało się zaprezentować obrazu");
            }
        }

        sync.advance();
    }

    private void updateScene() {
        // Obracaj model o 90 stopni na sekundę wokół osi Z - najprostszy
        // dowód, że cały potok faktycznie się aktualizuje co klatkę.
        chalet.rotation.identity().rotateZ((float) (glfwGetTime() * Math.toRadians(90)));
    }

    private void updateUniformBuffer(int imageIndex) {

        UniformBufferObject ubo = new UniformBufferObject();

        float aspectRatio = (float) swapChain.extent().width() / (float) swapChain.extent().height();
        ubo.view.set(camera.viewMatrix());
        ubo.proj.set(camera.projectionMatrix(aspectRatio));

        uniformBuffers.update(imageIndex, ubo);
    }

    private void cleanup() {
        cleanupSwapChainDependentObjects();

        sync.destroy();
        overlayMesh.destroy();
        chaletMaterial.destroy();
        chaletMesh.destroy();
        chaletTexture.destroy();
        whiteTexture.destroy();
        materialLayout.destroy();
        frameLayout.destroy();
        commandPool.destroy();
        ctx.destroy();
        window.destroy();
    }
}

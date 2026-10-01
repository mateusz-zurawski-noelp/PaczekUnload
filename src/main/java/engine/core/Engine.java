package engine.core;

import engine.config.EngineConfig;
import engine.input.InputManager;
import engine.resource.ResourceManager;
import engine.scene.Camera;
import engine.scene.FlyCameraController;
import engine.scene.FpsCounter;
import engine.scene.Material;
import engine.scene.Mesh;
import engine.scene.Renderable;
import engine.scene.SevenSegmentDigits;
import engine.scene.UniformBufferObject;
import engine.vulkan.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkSubmitInfo;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
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

    private final EngineConfig config;
    private final FixedTimestep timestep;

    // Zasoby żyjące przez cały czas trwania aplikacji
    private Window window;
    private InputManager input;
    private VulkanContext ctx;
    private CommandPool commandPool;
    private DescriptorSetLayout frameLayout;
    private DescriptorSetLayout materialLayout;
    private ResourceManager resources;
    private Texture chaletTexture;
    private Mesh chaletMesh;
    private Material chaletMaterial;
    private Renderable chalet;
    private final List<Renderable> renderables = new ArrayList<>();
    private Camera camera;
    private FlyCameraController cameraController;
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

    public Engine(EngineConfig config) {
        this.config = config;
        this.timestep = new FixedTimestep(config.simulation().updatesPerSecond(), config.simulation().maxFrameTime());
    }

    public void run() {
        init();
        loop();
        cleanup();
    }

    private void init() {
        EngineConfig.Window windowConfig = config.window();
        window = new Window(windowConfig.width(), windowConfig.height(), windowConfig.title());
        input = new InputManager(window);
        ctx = new VulkanContext(window, config.graphics().validationLayers());
        commandPool = new CommandPool(ctx);

        frameLayout = DescriptorSetLayout.perFrame(ctx);
        materialLayout = DescriptorSetLayout.perMaterial(ctx);

        resources = new ResourceManager(ctx, commandPool);

        chaletTexture = resources.textures().acquire(config.scene().texture());
        chaletMesh = Mesh.loadFromFile(ctx, commandPool, config.scene().model());
        chaletMaterial = Material.builder(ctx, materialLayout, resources.textures().white())
                .name("chalet")
                .baseColorTexture(chaletTexture)
                .build();

        chalet = new Renderable("chalet").add(chaletMesh, chaletMaterial);
        chalet.resetInterpolation(); // pozycja startowa = "poprzednia", bez przelotu z (0,0,0) w 1. klatce
        renderables.add(chalet);

        EngineConfig.Camera cameraConfig = config.camera();
        camera = new Camera(cameraConfig.positionVec(), cameraConfig.targetVec(), cameraConfig.upVec());
        camera.setPerspective(cameraConfig.fovDegrees(), cameraConfig.nearPlane(), cameraConfig.farPlane());
        cameraController = new FlyCameraController(camera, input, config.controls());

        fpsCounter = new FpsCounter();
        overlayMesh = new OverlayMesh(ctx, SevenSegmentDigits.VERTEX_COUNT);

        createSwapChainDependentObjects();

        sync = new SyncObjects(ctx, swapChain.imageCount(), config.graphics().maxFramesInFlight());
    }

    private void createSwapChainDependentObjects() {
        swapChain = new SwapChain(ctx, window, config.graphics().vsync());
        renderPass = new RenderPass(ctx, swapChain.imageFormat(), VulkanImages.findDepthFormat(ctx));
        depthResources = new DepthResources(ctx, commandPool, swapChain.extent());
        pipeline = new GraphicsPipeline(ctx, renderPass, frameLayout, materialLayout, swapChain.extent(),
                config.shaders().modelVertex(), config.shaders().modelFragment());
        overlayPipeline = new OverlayPipeline(ctx, renderPass, swapChain.extent(),
                config.shaders().overlayVertex(), config.shaders().overlayFragment());
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

    /**
     * Pętla główna ze stałym krokiem symulacji (patrz FixedTimestep):
     *
     *   1. wejście      - input.beginFrame() + pollEvents()
     *   2. frameUpdate  - raz na klatkę, zmienny deltaTime: rzeczy, które mają
     *                     reagować natychmiast (kamera sterowana myszą, Esc)
     *   3. fixedUpdate  - 0..N razy, zawsze ten sam krok: symulacja świata
     *                     (ruch obiektów, a w przyszłości fizyka i animacja)
     *   4. drawFrame    - rysuje stan interpolowany o timestep.alpha()
     */
    private void loop() {
        while (!window.shouldClose()) {
            input.beginFrame();
            Window.pollEvents();

            float frameTime = (float) timestep.beginFrame(glfwGetTime());
            frameUpdate(frameTime);

            while (timestep.consumeStep()) {
                fixedUpdate(timestep.stepSeconds());
            }

            drawFrame(timestep.alpha());
        }
        vkDeviceWaitIdle(ctx.device());
    }

    private void drawFrame(float alpha) {

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
            updateUniformBuffer(imageIndex);
            overlayMesh.update(SevenSegmentDigits.buildFps(fpsCounter.fps(), swapChain.extent().width(), swapChain.extent().height()));
            commandBuffers.record(imageIndex, renderables, alpha);

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

    /**
     * Aktualizacja raz na klatkę, ze zmiennym krokiem. Tu trafia to, co ma
     * reagować bez opóźnienia na wejście - kamera nie jest interpolowana,
     * więc musi być aktualna w każdej narysowanej klatce.
     *
     * Zbocza wejścia (wasKeyPressed) obsługujemy TYLKO tutaj: w jednej klatce
     * może wykonać się 0 albo kilka kroków fixedUpdate, więc tam takie
     * zdarzenie zostałoby zgubione albo obsłużone kilka razy. fixedUpdate może
     * czytać stan ciągły (isKeyDown).
     */
    private void frameUpdate(float frameTime) {
        if (input.wasKeyPressed(GLFW_KEY_ESCAPE)) {
            window.requestClose();
        }
        cameraController.update(frameTime);
    }

    /** Jeden krok symulacji świata - zawsze o tej samej długości (stepSeconds). */
    private void fixedUpdate(float step) {
        for (Renderable renderable : renderables) {
            renderable.savePreviousTransform();
        }
        updateScene(step);
    }

    private void updateScene(float step) {
        // Obracaj model wokół osi Z (domyślnie 90 stopni na sekundę) - najprostszy
        // dowód, że symulacja faktycznie "tyka". Obrót przyrostowy o stały krok:
        // wynik jest taki sam przy każdym FPS, a płynność daje interpolacja w renderze.
        double degreesPerSecond = config.scene().rotationDegreesPerSecond();
        //chalet.rotation.rotateZ((float) Math.toRadians(degreesPerSecond * step));
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
        resources.textures().release(chaletTexture);
        resources.shutdown();
        materialLayout.destroy();
        frameLayout.destroy();
        commandPool.destroy();
        ctx.destroy();
        window.destroy();
    }
}

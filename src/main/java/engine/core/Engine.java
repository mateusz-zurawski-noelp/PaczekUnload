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
import engine.scene.SceneLighting;
import engine.scene.UniformBufferObject;
import engine.ui.BakedFont;
import engine.ui.FontAtlas;
import engine.ui.TextBatch;
import engine.vulkan.*;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkSubmitInfo;

import java.nio.ByteBuffer;
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

    /** Najwięcej wierzchołków nakładki 2D na klatkę (6 na znak lub prostokąt). */
    private static final int OVERLAY_CAPACITY = 65536;

    private static final Vector4fc PANEL_BACKGROUND = new Vector4f(0.0f, 0.0f, 0.0f, 0.55f);
    private static final Vector4fc FPS_COLOR = new Vector4f(0.95f, 0.85f, 0.15f, 1.0f);

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
    private SceneLighting lighting;
    private FlyCameraController cameraController;
    private SyncObjects sync;
    private FpsCounter fpsCounter;
    private DescriptorSetLayout overlayLayout;
    private FontAtlas font;
    private TextBatch overlay;

    // Zasoby zależne od swapchaina - odtwarzane przy resize okna
    private SwapChain swapChain;
    private RenderPass renderPass;
    private DepthResources depthResources;
    private GraphicsPipeline pipeline;
    private OverlayPipeline overlayPipeline;
    private OverlayMesh overlayMesh;
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
        chaletMesh = Mesh.loadFromFile(ctx, commandPool, resources, config.scene().model());
        // Drewno i dachówki: słaby, szeroki odblask (niski połysk) i brak odbić -
        // domyślny biały odblask z połyskiem 32 wyglądałby jak plastik.
        chaletMaterial = Material.builder(ctx, materialLayout, resources.textures().white())
                .name("chalet")
                .baseColorTexture(chaletTexture)
                .specularColor(0.15f, 0.15f, 0.15f)
                .shininess(16.0f)
                .reflectivity(0.0f)
                .build();

        chalet = new Renderable("chalet").add(chaletMesh, chaletMaterial);
        chalet.resetInterpolation(); // pozycja startowa = "poprzednia", bez przelotu z (0,0,0) w 1. klatce
        renderables.add(chalet);

        EngineConfig.Camera cameraConfig = config.camera();
        camera = new Camera(cameraConfig.positionVec(), cameraConfig.targetVec(), cameraConfig.upVec());
        camera.setPerspective(cameraConfig.fovDegrees(), cameraConfig.nearPlane(), cameraConfig.farPlane());
        cameraController = new FlyCameraController(camera, input, config.controls());

        lighting = createLighting(config.lighting(), cameraConfig.upVec());

        fpsCounter = new FpsCounter();
        overlayLayout = DescriptorSetLayout.singleTexture(ctx);
        BakedFont bakedFont = bakeFont(config.ui());
        font = new FontAtlas(ctx, commandPool, overlayLayout, bakedFont);
        overlay = new TextBatch(bakedFont, OVERLAY_CAPACITY);

        createSwapChainDependentObjects();

        sync = new SyncObjects(ctx, swapChain.imageCount(), config.graphics().maxFramesInFlight());
    }

    private BakedFont bakeFont(EngineConfig.Ui uiConfig) {
        ByteBuffer fontData = resources.readBytes(uiConfig.font());
        try {
            return BakedFont.bake(fontData, uiConfig.font(), uiConfig.fontSize());
        } finally {
            MemoryUtil.memFree(fontData);
        }
    }

    private static SceneLighting createLighting(EngineConfig.Lighting lightingConfig, Vector3f worldUp) {
        SceneLighting result = new SceneLighting();
        result.sunDirection.set(lightingConfig.sunDirection()).normalize();
        result.sunColor.set(lightingConfig.sunColor());
        result.sunIntensity = lightingConfig.sunIntensity();
        result.skyColor.set(lightingConfig.skyColor());
        result.groundColor.set(lightingConfig.groundColor());
        result.worldUp.set(worldUp).normalize();
        return result;
    }

    private void createSwapChainDependentObjects() {
        swapChain = new SwapChain(ctx, window, config.graphics().vsync());
        renderPass = new RenderPass(ctx, swapChain.imageFormat(), VulkanImages.findDepthFormat(ctx));
        depthResources = new DepthResources(ctx, commandPool, swapChain.extent());
        pipeline = new GraphicsPipeline(ctx, renderPass, frameLayout, materialLayout, swapChain.extent(),
                config.shaders().modelVertex(), config.shaders().modelFragment());
        overlayPipeline = new OverlayPipeline(ctx, renderPass, overlayLayout, swapChain.extent(),
                config.shaders().overlayVertex(), config.shaders().overlayFragment());
        framebuffers = new Framebuffers(ctx, renderPass, swapChain.imageViews(), depthResources.imageView(), swapChain.extent());
        uniformBuffers = new UniformBuffers(ctx, swapChain.imageCount());
        overlayMesh = new OverlayMesh(ctx, swapChain.imageCount(), OVERLAY_CAPACITY);
        descriptorSets = new DescriptorSets(ctx, frameLayout, swapChain.imageCount(), uniformBuffers);
        commandBuffers = new CommandBuffers(ctx, commandPool, framebuffers, renderPass, swapChain.extent(),
                pipeline, descriptorSets, overlayPipeline, overlayMesh, font.descriptorSet());
    }

    private void cleanupSwapChainDependentObjects() {
        commandBuffers.destroy();
        descriptorSets.destroy();
        overlayMesh.destroy();
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
            updateOverlay(imageIndex);
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

        // Opcjonalny obieg słońca wokół osi "góra" - widać, jak zmienia się
        // cieniowanie ścian i odblaski. Domyślnie 0 (słońce stoi).
        float sunOrbit = config.lighting().sunOrbitDegreesPerSecond();
        if (sunOrbit != 0.0f) {
            lighting.rotateSun((float) Math.toRadians(sunOrbit * step));
        }
    }

    /** Buduje nakładkę 2D tej klatki: na razie licznik FPS w prawym górnym rogu. */
    private void updateOverlay(int imageIndex) {
        int width = swapChain.extent().width();
        int height = swapChain.extent().height();
        overlay.begin(width, height);

        String fps = fpsCounter.fps() + " FPS";
        float padding = 8.0f;
        float margin = 16.0f;
        float textWidth = overlay.measure(fps);
        float right = width - margin;
        float left = right - textWidth - 2 * padding;
        float bottom = margin + overlay.lineHeight() + 2 * padding;

        overlay.rect(left, margin, right, bottom, PANEL_BACKGROUND);
        overlay.text(fps, left + padding, margin + padding, FPS_COLOR);

        overlayMesh.update(imageIndex, overlay);
    }

    private void updateUniformBuffer(int imageIndex) {

        UniformBufferObject ubo = new UniformBufferObject(lighting);

        float aspectRatio = (float) swapChain.extent().width() / (float) swapChain.extent().height();
        ubo.view.set(camera.viewMatrix());
        ubo.proj.set(camera.projectionMatrix(aspectRatio));
        ubo.cameraPosition.set(camera.position());

        uniformBuffers.update(imageIndex, ubo);
    }

    private void cleanup() {
        cleanupSwapChainDependentObjects();

        sync.destroy();
        font.destroy();
        overlayLayout.destroy();
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

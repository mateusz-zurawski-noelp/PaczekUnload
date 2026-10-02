# Diagram klas

Diagram w formacie Mermaid (renderuje go GitHub, IntelliJ z pluginem Mermaid, VS Code itd.).
Materiał (`Material`) i obiekt sceny (`Renderable`) są opisane w `Renderable.java` / `Material.java`; `Renderable` nie jest właścicielem meshy ani materiałów.
Tekstury z plików należą do `TextureManager` (pakiet `engine.resource`) - kod gry pobiera je przez `acquire(path)` i oddaje przez `release(texture)`.
Strzałki `-->` to posiadanie/użycie (kompozycja w `Engine`), `*--` to kompozycja wewnątrz klasy, `..>` to zależność (parametr konstruktora / wywołanie statyczne).
Pominięte zostały zależności od `VulkanContext`, które ma prawie każda klasa z pakietu `vulkan` (poza kilkoma pokazowymi), oraz metody `destroy()` - ma je każdy obiekt trzymający zasoby Vulkana.

```mermaid
classDiagram
    direction TB

    class Main {
        +main(String[])$
    }

    %% ---------- config ----------
    class ConfigLoader {
        +DEFAULT_USER_CONFIG$ String
        +fromArgs(String[])$ EngineConfig
        +load(Path)$ EngineConfig
    }
    class EngineConfig {
        <<record>>
        +window() Window
        +graphics() Graphics
        +simulation() Simulation
        +camera() Camera
        +controls() Controls
        +lighting() Lighting
        +shaders() Shaders
        +scene() Scene
    }
    class ConfigException
    class WindowConfig["EngineConfig.Window"] {
        <<record>>
        +int width
        +int height
        +String title
    }
    class GraphicsConfig["EngineConfig.Graphics"] {
        <<record>>
        +boolean vsync
        +int maxFramesInFlight
        +boolean validationLayers
    }
    class SimulationConfig["EngineConfig.Simulation"] {
        <<record>>
        +double updatesPerSecond
        +double maxFrameTime
    }
    class CameraConfig["EngineConfig.Camera"] {
        <<record>>
        +float[] position
        +float[] target
        +float[] up
        +float fovDegrees
        +float nearPlane
        +float farPlane
    }
    class ControlsConfig["EngineConfig.Controls"] {
        <<record>>
        +float moveSpeed
        +float fastMultiplier
        +float mouseSensitivity
        +boolean invertY
        +float zoomStepDegrees
    }
    class LightingConfig["EngineConfig.Lighting"] {
        <<record>>
        +float[] sunDirection
        +float[] sunColor
        +float sunIntensity
        +float[] skyColor
        +float[] groundColor
        +float sunOrbitDegreesPerSecond
    }
    class ShadersConfig["EngineConfig.Shaders"] {
        <<record>>
        +String modelVertex
        +String modelFragment
        +String overlayVertex
        +String overlayFragment
    }
    class SceneConfig["EngineConfig.Scene"] {
        <<record>>
        +String model
        +String texture
        +float rotationDegreesPerSecond
    }

    %% ---------- log ----------
    class Log {
        +configure(LogLevel, int historySize)$
        +debug(channel, message)$
        +debug(channel, Supplier)$
        +info(channel, message)$
        +warn(channel, message)$
        +error(channel, message)$
        +error(channel, message, Throwable)$
        +log(LogLevel, channel, message)$
        +isEnabled(LogLevel)$ boolean
        +recent(int max)$ List~LogEntry~
        +lastSequence()$ long
    }
    class LogLevel {
        <<enumeration>>
        DEBUG
        INFO
        WARN
        ERROR
    }
    class LogEntry {
        <<record>>
        +long sequence
        +double timeSeconds
        +LogLevel level
        +String channel
        +String message
        +String thread
        +format() String
    }
    class LoggingConfig["EngineConfig.Logging"] {
        <<record>>
        +LogLevel level
        +int historySize
    }

    %% ---------- core ----------
    class Engine {
        -EngineConfig config
        -FixedTimestep timestep
        -Window window
        -InputManager input
        -VulkanContext ctx
        -CommandPool commandPool
        -DescriptorSetLayout frameLayout
        -DescriptorSetLayout materialLayout
        -ResourceManager resources
        -Texture chaletTexture
        -Mesh chaletMesh
        -Material chaletMaterial
        -Renderable chalet
        -List~Renderable~ renderables
        -Camera camera
        -SceneLighting lighting
        -FlyCameraController cameraController
        -SyncObjects sync
        -FpsCounter fpsCounter
        -DescriptorSetLayout overlayLayout
        -FontAtlas font
        -TextBatch overlay
        -OverlayMesh overlayMesh
        -SwapChain swapChain
        -RenderPass renderPass
        -DepthResources depthResources
        -GraphicsPipeline pipeline
        -OverlayPipeline overlayPipeline
        -Framebuffers framebuffers
        -UniformBuffers uniformBuffers
        -DescriptorSets descriptorSets
        -CommandBuffers commandBuffers
        +run()
        -init()
        -createSwapChainDependentObjects()
        -cleanupSwapChainDependentObjects()
        -recreateSwapChain()
        -loop()
        -frameUpdate(float frameTime)
        -fixedUpdate(float step)
        -updateScene(float step)
        -drawFrame(float alpha)
        -updateUniformBuffer(int)
        -cleanup()
    }
    class FixedTimestep {
        +FixedTimestep(updatesPerSecond, maxFrameTime)
        +beginFrame(double now) double
        +consumeStep() boolean
        +stepSeconds() float
        +alpha() float
        +totalSteps() long
    }
    %% ---------- input ----------
    class InputManager {
        +beginFrame()
        +isKeyDown(int) boolean
        +wasKeyPressed(int) boolean
        +wasKeyReleased(int) boolean
        +isMouseButtonDown(int) boolean
        +wasMouseButtonPressed(int) boolean
        +wasMouseButtonReleased(int) boolean
        +cursorPosition() Vector2dc
        +mouseDelta() Vector2dc
        +scrollDelta() Vector2dc
        +setCursorCaptured(boolean)
        +isCursorCaptured() boolean
    }

    class Window {
        +handle() long
        +shouldClose() boolean
        +requestClose()
        +framebufferSize() int[]
        +consumeFramebufferResized() boolean
        +pollEvents()$
        +waitEvents()$
    }

    %% ---------- resource ----------
    class ResourceManager {
        +textures() TextureManager
        +readBytes(String) ByteBuffer
        +collectGarbage()
        +shutdown()
    }
    class TextureManager {
        +acquire(String) Texture
        +release(Texture)
        +white() Texture
        +refCount(String) int
        +loadedCount() int
    }
    class ResourceCache~T~ {
        ~acquire(String) T
        ~release(T)
        ~destroyUnused() int
        ~destroyAll() List~String~
        ~refCount(String) int
        ~size() int
    }
    class ResourcePaths {
        ~normalize(String)$ String
    }

    %% ---------- scene ----------
    class Camera {
        +setPerspective(fov, near, far)
        +lookAt(Vector3fc, Vector3fc)
        +rotate(yaw, pitch)
        +move(forward, right, up)
        +zoom(float)
        +position() Vector3fc
        +forward() Vector3fc
        +fovDegrees() float
        +viewMatrix() Matrix4f
        +projectionMatrix(float) Matrix4f
    }
    class FlyCameraController {
        +update(float deltaTime)
    }
    class FpsCounter {
        +onFrameRendered()
        +fps() int
    }
    class Mesh {
        +Mesh(ctx, pool, Vertex[], int[])
        +loadFromFile(ctx, pool, resources, path)$ Mesh
        +indexCount() int
        +bind(VkCommandBuffer)
    }
    class ModelLoader {
        +load(ByteBuffer, name, flags)$ Model
    }
    class Model["ModelLoader.Model"] {
        +List~Vector3fc~ positions
        +List~Vector2fc~ texCoords
        +List~Vector3fc~ normals
        +List~Integer~ indices
    }
    class Renderable {
        +Vector3f position
        +Quaternionf rotation
        +Vector3f scale
        +boolean visible
        +name() String
        +add(Mesh, Material) Renderable
        +parts() List~Part~
        +modelMatrix(Matrix4f) Matrix4f
        +savePreviousTransform()
        +resetInterpolation()
        +interpolatedModelMatrix(float alpha, Matrix4f) Matrix4f
    }
    class Part {
        <<record>>
        +Mesh mesh
        +Material material
    }
    class Material {
        +name() String
        +baseColor() Vector4fc
        +specularColor() Vector3fc
        +shininess() float
        +emissive() Vector3fc
        +reflectivity() float
        +secondTextureBlend() float
        +baseColorTexture() Texture
        +secondTexture() Texture
        +descriptorSet() long
        +builder(ctx, layout, fallbackTexture)$ Builder
    }
    class MaterialBuilder["Material.Builder"] {
        +name(String)
        +baseColor(r, g, b, a)
        +specularColor(r, g, b)
        +shininess(float)
        +emissive(r, g, b)
        +reflectivity(float)
        +baseColorTexture(Texture)
        +secondTexture(Texture)
        +secondTextureBlend(float)
        +build() Material
    }
    class Vertex {
        +Vector3fc pos
        +Vector3fc color
        +Vector2fc texCoords
        +Vector3fc normal
        +bindingDescription(stack)$
        +attributeDescriptions(stack)$
    }
    %% ---------- ui ----------
    class OverlayVertex {
        +FLOATS$ int
        +SIZEOF$ int
        +bindingDescription(stack)$
        +attributeDescriptions(stack)$
    }
    class BakedFont {
        +bake(ByteBuffer, name, pixelHeight)$ BakedFont
        +glyph(int codepoint) Glyph
        +ascent() float
        +lineHeight() float
        +whiteU() float
        +whiteV() float
        +coverage() byte[]
    }
    class Glyph["BakedFont.Glyph"] {
        <<record>>
        +float x0, y0, x1, y1
        +float u0, v0, u1, v1
        +float advance
    }
    class FontAtlas {
        +FontAtlas(ctx, pool, layout, BakedFont)
        +descriptorSet() long
    }
    class TextBatch {
        +begin(width, height)
        +rect(x0, y0, x1, y1, Vector4fc)
        +text(String, x, y, Vector4fc) float
        +measure(String) float
        +lineHeight() float
        +vertexCount() int
    }
    class UniformBufferObject {
        +SIZEOF$ int
        +Matrix4f view
        +Matrix4f proj
        +Vector3f cameraPosition
        +SceneLighting lighting
        +write(ByteBuffer)
    }
    class SceneLighting {
        +Vector3f sunDirection
        +Vector3f sunColor
        +float sunIntensity
        +Vector3f skyColor
        +Vector3f groundColor
        +Vector3f worldUp
        +rotateSun(float radians)
    }

    %% ---------- vulkan ----------
    class VulkanContext {
        +instance() VkInstance
        +physicalDevice() VkPhysicalDevice
        +device() VkDevice
        +surface() long
        +graphicsQueue() VkQueue
        +presentQueue() VkQueue
        +graphicsQueueFamily() int
        +querySwapChainSupport(stack) SwapChainSupportDetails
    }
    class QueueFamilyIndices
    class SwapChainSupportDetails
    class SwapChain {
        +handle() long
        +imageCount() int
        +imageFormat() int
        +imageViews() List~Long~
        +extent() VkExtent2D
    }
    class RenderPass {
        +handle() long
    }
    class DepthResources {
        +imageView() long
        +format() int
    }
    class GraphicsPipeline {
        +handle() long
        +layout() long
    }
    class OverlayPipeline {
        +handle() long
        +layout() long
    }
    class ShaderCompiler {
        +compileFromResource(path, kind)$ SPIRV
    }
    class ShaderKind["ShaderCompiler.ShaderKind"] {
        <<enumeration>>
        VERTEX_SHADER
        FRAGMENT_SHADER
        GEOMETRY_SHADER
    }
    class SPIRV["ShaderCompiler.SPIRV"] {
        +bytecode() ByteBuffer
        +free()
    }
    class Framebuffers {
        +handles() List~Long~
    }
    class CommandPool {
        +handle() long
        +beginSingleTimeCommands() VkCommandBuffer
        +endSingleTimeCommands(VkCommandBuffer)
    }
    class CommandBuffers {
        +record(int, List~Renderable~, float alpha)
        +get(int) VkCommandBuffer
    }
    class DescriptorSetLayout {
        +perFrame(ctx)$ DescriptorSetLayout
        +perMaterial(ctx)$ DescriptorSetLayout
        +singleTexture(ctx)$ DescriptorSetLayout
        +handle() long
    }
    class DescriptorSets {
        +set(int) long
    }
    class UniformBuffers {
        +buffer(int) long
        +update(int, UniformBufferObject)
    }
    class Texture {
        +fromEncodedImage(ctx, pool, bytes, name)$ Texture
        +solidColor(ctx, pool, r, g, b, a)$ Texture
        +fromRgba(ctx, pool, ByteBuffer, w, h)$ Texture
        +imageView() long
        +sampler() long
    }
    class OverlayMesh {
        +capacity() int
        +update(int imageIndex, TextBatch)
        +vertexCount(int imageIndex) int
        +bind(VkCommandBuffer, int imageIndex)
    }
    class SyncObjects {
        +currentFrame() Frame
        +waitForFrame(Frame)
        +waitIfImageInUse(int)
        +markImageInUse(int, Frame)
        +advance()
    }
    class Frame {
        +imageAvailableSemaphore() long
        +renderFinishedSemaphore() long
        +fence() long
    }
    class VulkanBuffers {
        +createBuffer(...)$
        +copyBuffer(...)$
        +findMemoryType(...)$ int
    }
    class VulkanImages {
        +createImage(...)$
        +createImageView(...)$ long
        +transitionImageLayout(...)$
        +copyBufferToImage(...)$
        +findSupportedFormat(...)$ int
        +findDepthFormat(ctx)$ int
    }

    Main ..> Engine
    Main ..> ConfigLoader
    Main ..> Log : configure()
    Log *-- LogEntry
    LogEntry --> LogLevel
    EngineConfig *-- LoggingConfig
    LoggingConfig --> LogLevel
    ConfigLoader ..> Log
    VulkanContext ..> Log : warstwy walidacyjne
    Window ..> Log : błędy GLFW
    Engine ..> Log

    %% konfiguracja
    ConfigLoader ..> EngineConfig : load()
    ConfigLoader ..> ConfigException
    EngineConfig *-- WindowConfig
    EngineConfig *-- GraphicsConfig
    EngineConfig *-- SimulationConfig
    EngineConfig *-- CameraConfig
    EngineConfig *-- ControlsConfig
    EngineConfig *-- LightingConfig
    EngineConfig *-- ShadersConfig
    EngineConfig *-- SceneConfig
    EngineConfig ..> ConfigException : walidacja
    Engine --> EngineConfig
    Engine --> FixedTimestep
    FixedTimestep ..> SimulationConfig

    %% Engine posiada zasoby
    Engine --> Window
    Engine --> VulkanContext
    Engine --> CommandPool
    Engine --> "2" DescriptorSetLayout
    Engine --> ResourceManager
    Engine --> Texture
    Engine --> Mesh
    Engine --> Material
    Engine --> "*" Renderable
    Engine --> Camera
    Engine --> InputManager
    Engine --> FlyCameraController
    InputManager ..> Window : callbacki GLFW
    FlyCameraController --> Camera
    FlyCameraController --> InputManager
    FlyCameraController ..> ControlsConfig
    Engine --> SyncObjects
    Engine --> FpsCounter
    Engine --> OverlayMesh
    Engine --> SwapChain
    Engine --> RenderPass
    Engine --> DepthResources
    Engine --> GraphicsPipeline
    Engine --> OverlayPipeline
    Engine --> Framebuffers
    Engine --> UniformBuffers
    Engine --> DescriptorSets
    Engine --> CommandBuffers
    Engine ..> UniformBufferObject
    Engine --> SceneLighting
    Engine ..> LightingConfig : createLighting()
    UniformBufferObject --> SceneLighting
    Engine --> FontAtlas
    Engine --> TextBatch
    Engine ..> BakedFont : bakeFont()
    BakedFont *-- Glyph
    FontAtlas ..> BakedFont
    FontAtlas ..> Texture
    TextBatch --> BakedFont
    TextBatch ..> OverlayVertex
    OverlayMesh ..> TextBatch
    OverlayPipeline ..> DescriptorSetLayout
    Engine ..> VulkanImages : findDepthFormat()

    %% zasoby
    ResourceManager *-- TextureManager
    TextureManager *-- ResourceCache
    TextureManager --> "*" Texture
    TextureManager ..> ResourceManager : readBytes()
    ResourceManager ..> ResourcePaths
    ResourceCache ..> ResourcePaths

    %% zależności od VulkanContext
    VulkanContext ..> Window
    VulkanContext ..> QueueFamilyIndices
    VulkanContext ..> SwapChainSupportDetails
    SwapChain ..> VulkanContext
    SwapChain ..> Window
    SwapChain ..> SwapChainSupportDetails
    SwapChain ..> QueueFamilyIndices
    CommandPool ..> VulkanContext
    RenderPass ..> VulkanContext

    %% obrazy i bufory
    Texture ..> CommandPool
    Texture ..> VulkanImages
    Texture ..> VulkanBuffers
    DepthResources ..> CommandPool
    DepthResources ..> VulkanImages
    VulkanImages ..> CommandPool
    VulkanImages ..> VulkanBuffers
    VulkanBuffers ..> CommandPool

    %% potoki i shadery
    GraphicsPipeline ..> RenderPass
    GraphicsPipeline ..> "2" DescriptorSetLayout
    GraphicsPipeline ..> ShaderCompiler
    GraphicsPipeline ..> Vertex
    OverlayPipeline ..> RenderPass
    OverlayPipeline ..> ShaderCompiler
    OverlayPipeline ..> OverlayVertex
    ShaderCompiler ..> ShaderKind
    ShaderCompiler ..> SPIRV : compileFromResource()

    %% rysowanie
    Framebuffers ..> RenderPass
    DescriptorSets ..> DescriptorSetLayout
    DescriptorSets ..> UniformBuffers
    DescriptorSets ..> UniformBufferObject : SIZEOF
    UniformBuffers ..> VulkanBuffers
    UniformBuffers ..> UniformBufferObject
    CommandBuffers ..> CommandPool
    CommandBuffers ..> Framebuffers
    CommandBuffers ..> RenderPass
    CommandBuffers ..> GraphicsPipeline
    CommandBuffers ..> OverlayPipeline
    CommandBuffers ..> Renderable
    CommandBuffers ..> OverlayMesh
    CommandBuffers ..> DescriptorSets

    %% geometria
    Mesh ..> ModelLoader
    Mesh ..> ResourceManager : readBytes()
    ModelLoader ..> Model : load()
    Mesh ..> Vertex
    Mesh ..> VulkanBuffers
    Mesh ..> CommandPool
    OverlayMesh ..> OverlayVertex
    OverlayMesh ..> VulkanBuffers

    %% obiekty sceny i materiały
    Renderable "1" *-- "n" Part
    Part --> Mesh
    Part --> Material
    Material --> "2" Texture
    Material ..> DescriptorSetLayout
    Material ..> VulkanBuffers
    Material ..> MaterialBuilder
    MaterialBuilder ..> Material : build()

    %% synchronizacja
    SyncObjects "1" *-- "n" Frame
```

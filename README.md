# Vulkan Engine

Edukacyjny szkielet silnika 3D w Javie, oparty na [LWJGL](https://www.lwjgl.org/)
i Vulkanie. Powstał jako uporządkowana, wieloklasowa wersja
[Vulkan-Tutorial-Java](https://github.com/Naitsirc98/Vulkan-Tutorial-Java)
(konkretnie ostatniego rozdziału, [Ch27ModelLoading](https://github.com/Naitsirc98/Vulkan-Tutorial-Java/blob/master/src/main/java/javavulkantutorial/Ch27ModelLoading.java)) —
zamiast jednego 2000-liniowego pliku, kod jest podzielony na klasy
odpowiadające konkretnym obiektom/koncepcjom Vulkana, żeby łatwiej się było
tego uczyć.

Po uruchomieniu zobaczysz obracający się, oteksturowany model domku (ten sam
`chalet.obj`, którego używa oryginalny tutorial) oraz licznik FPS w prawym
górnym rogu, narysowany jako prosty wyświetlacz 7-segmentowy (bez żadnego
fontu - same proceduralne prostokąty).

## Uruchomienie

Nie musisz mieć zainstalowanego Mavena — w repo jest wrapper:

```bash
./mvnw exec:java
```

(Windows: `mvnw.cmd exec:java`). Pierwsze uruchomienie pobierze Mavena i
zależności (LWJGL, JOML) — potrwa chwilę, potem jest szybciej.

### Warstwy walidacyjne (polecane!)

Silnik próbuje włączyć `VK_LAYER_KHRONOS_validation` — warstwę, która
sprawdza w locie, czy poprawnie używasz Vulkan API, i wypisuje czytelne
błędy zamiast czarnego ekranu albo segfaulta. Jeśli nie jest zainstalowana,
silnik działa dalej bez niej i tylko o tym informuje w konsoli. Warto ją
mieć podczas nauki:

```bash
sudo apt install vulkan-validationlayers   # Debian/Ubuntu
```

(na innych systemach: zainstaluj [Vulkan SDK](https://vulkan.lunarg.com/)).

## Struktura projektu

```
src/main/java/engine/
  Main.java                 punkt wejścia
  core/
    Window.java              okno GLFW
    Engine.java              spina wszystko: init, pętla główna, resize, cleanup
  vulkan/
    VulkanContext.java        VkInstance, debug messenger, surface, fizyczne/logiczne urządzenie, kolejki
    SwapChain.java             swapchain + image views
    RenderPass.java            opis załączników koloru/głębi i podpasów
    DescriptorSetLayout.java   "kształt" danych widocznych dla shaderów (UBO + sampler)
    GraphicsPipeline.java      shadery + cały zamrożony stan rysowania (model 3D)
    OverlayPipeline.java       drugi, prostszy pipeline dla nakładki 2D (bez descriptor setów, bez testu głębi)
    OverlayMesh.java           bufor wierzchołków nakładki o stałym rozmiarze, aktualizowany co klatkę
    DepthResources.java        bufor głębi (z-bufor)
    Framebuffers.java          framebuffer na każdy obraz swapchaina
    CommandPool.java           pula command bufferów + pomocnicze "one-shot" polecenia
    VulkanBuffers.java         tworzenie VkBuffer, kopiowanie, dobór typu pamięci
    VulkanImages.java          tworzenie VkImage/VkImageView, zmiana layoutów
    Texture.java                obraz + widok + sampler tekstury (stb_image)
    UniformBuffers.java         bufory UBO (macierze model/view/proj), po jednym na obraz swapchaina
    DescriptorSets.java         konkretne "wpięcie" buforów/tekstury pod DescriptorSetLayout
    CommandBuffers.java         nagrywanie poleceń rysowania
    SyncObjects.java / Frame.java   semafory i fence'y do synchronizacji CPU<->GPU
    shader/ShaderCompiler.java  kompilacja GLSL -> SPIR-V w locie (shaderc)
  scene/
    Vertex.java                 layout jednego wierzchołka (model 3D)
    UniformBufferObject.java    dane wysyłane do shadera co klatkę
    Mesh.java                    geometria + jej bufory GPU
    ModelLoader.java             wczytywanie modeli (Assimp) - .obj/.fbx/.gltf/...
    Camera.java                  prosta kamera lookAt + projekcja perspektywiczna
    OverlayVertex.java           layout wierzchołka nakładki 2D (pozycja NDC + kolor)
    FpsCounter.java               liczy FPS uśredniając w oknach czasowych
    SevenSegmentDigits.java       generuje geometrię licznika FPS (wyświetlacz 7-segmentowy)

src/main/resources/
  shaders/model.vert, model.frag       shadery GLSL modelu 3D
  shaders/overlay.vert, overlay.frag   shadery GLSL nakładki 2D (licznik FPS)
  textures/chalet.jpg                   tekstura domku
  models/chalet.obj                     geometria domku
```

`Engine` jest właścicielem wszystkiego i dzieli zasoby na dwie grupy:

- **stałe** (okno, `VulkanContext`, `CommandPool`, tekstura, siatka,
  `DescriptorSetLayout`, `SyncObjects`) — żyją tak długo jak aplikacja,
- **zależne od swapchaina** (`SwapChain`, `RenderPass`, `GraphicsPipeline`,
  `DepthResources`, `Framebuffers`, `UniformBuffers`, `DescriptorSets`,
  `CommandBuffers`) — niszczone i tworzone od nowa przy każdej zmianie
  rozmiaru okna, patrz `Engine.recreateSwapChain()`.

## Pomysły na dalszą naukę

To jest **szkielet**, nie gotowy silnik — świetny punkt wyjścia do
eksperymentów:

- Podmień `chalet.obj`/`chalet.jpg` na własny model (dowolny format, który
  ogarnia Assimp) — wystarczy zmienić `MODEL_PATH`/`TEXTURE_PATH` w `Engine`.
- Dodaj sterowanie kamerą myszką/klawiaturą zamiast statycznego `lookAt`
  (rozszerz `Camera` i podłącz callbacki GLFW w `Window`).
- Dorzuć mipmapping do `Texture` (następny rozdział oryginalnego tutoriala).
- Dodaj multisampling (MSAA) w `RenderPass`/`GraphicsPipeline`.
- Wyciągnij `UniformBufferObject` per-obiekt i narysuj więcej niż jeden model.
- Zamień pojedynczy `graphicsQueue` na osobną kolejkę transferu do
  `CommandPool.beginSingleTimeCommands` (wydajniejsze wgrywanie zasobów).

## Otwieranie w IntelliJ

Projekt jest teraz zwykłym projektem Maven (`pom.xml` w katalogu głównym).
Otwórz `pom.xml` przez *File → Open* — IntelliJ sam zaimportuje moduł i
zależności. Stary plik `.iml` (z szablonu "prosty projekt Javy") został
usunięty, bo kolidowałby z importem Mavena.

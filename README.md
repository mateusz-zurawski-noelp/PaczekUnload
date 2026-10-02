# Vulkan Engine

Edukacyjny szkielet silnika 3D w Javie, oparty na [LWJGL](https://www.lwjgl.org/)
i Vulkanie. Powstał jako uporządkowana, wieloklasowa wersja
[Vulkan-Tutorial-Java](https://github.com/Naitsirc98/Vulkan-Tutorial-Java)
(konkretnie ostatniego rozdziału, [Ch27ModelLoading](https://github.com/Naitsirc98/Vulkan-Tutorial-Java/blob/master/src/main/java/javavulkantutorial/Ch27ModelLoading.java)) —
zamiast jednego 2000-liniowego pliku, kod jest podzielony na klasy
odpowiadające konkretnym obiektom/koncepcjom Vulkana, żeby łatwiej się było
tego uczyć.

Po uruchomieniu zobaczysz oświetlony, oteksturowany model domku (ten sam
`chalet.obj`, którego używa oryginalny tutorial) oraz licznik FPS w prawym
górnym rogu, narysowany prawdziwą czcionką (DejaVu Sans Mono wypalona do
tekstury przez `stb_truetype`).

## Uruchomienie

Nie musisz mieć zainstalowanego Mavena — w repo jest wrapper:

```bash
./mvnw exec:java
```

(Windows: `mvnw.cmd exec:java`). Pierwsze uruchomienie pobierze Mavena i
zależności (LWJGL, JOML) — potrwa chwilę, potem jest szybciej.

### Sterowanie

| Klawisz / mysz | Akcja |
|---|---|
| prawy przycisk myszy (przytrzymany) + ruch myszy | rozglądanie się |
| W / S, A / D | przód / tył, lewo / prawo |
| E / Q (albo Spacja / lewy Ctrl) | w górę / w dół |
| Shift | szybszy ruch |
| kółko myszy | zoom (pole widzenia) |
| R | powrót kamery do pozycji startowej |
| Esc | zamknięcie okna (gdy konsola jest otwarta - zamknięcie konsoli) |
| ~ (klawisz pod Esc) | konsola silnika: otwórz / zamknij |
| PageUp / PageDown, kółko myszy | przewijanie konsoli (gdy otwarta) |
| Home / End | początek historii / powrót do najnowszych wpisów |

Konsola pokazuje na żywo wpisy z logu silnika (kolory wg poziomu). Gdy jest
otwarta, kamera nie reaguje na klawisze i mysz. Gdy jest zamknięta, a
pojawią się nowe ostrzeżenia lub błędy, w lewym górnym rogu wyświetla się
czerwona plakietka z ich liczbą.

Prędkość, czułość myszy i odwrócenie osi Y ustawisz w sekcji `controls`
konfiguracji (patrz niżej).

### Konfiguracja

Ustawienia silnika (poziom logowania, okno, vsync, liczba klatek "w locie",
warstwy walidacyjne, krok symulacji, kamera, sterowanie, oświetlenie,
czcionka nakładki, ścieżki shaderów/modelu/tekstury) są w
`src/main/resources/config/engine-defaults.json`. Żeby je zmienić bez
przebudowy, połóż w katalogu roboczym plik `engine.json` z samymi kluczami,
które chcesz nadpisać, np.:

```json
{
  "window": { "width": 1920, "height": 1080 },
  "graphics": { "vsync": true }
}
```

Można też podać ścieżkę jawnie: `java -jar target/vulkan-engine-1.0-SNAPSHOT.jar moj-config.json`
albo `./mvnw exec:java -Dexec.args="moj-config.json"`. Nieznane klucze
(np. literówka `"widht"`) są zgłaszane w konsoli, a nieprawidłowe wartości
zatrzymują start z opisem, co jest nie tak.

### Log

Silnik wypisuje komunikaty w formacie `[czas od startu] POZIOM kanał | treść`,
np. `[   0.412] INFO  Vulkan     | GPU: ...`. Ostrzeżenia i błędy idą na
stderr. Więcej szczegółów (m.in. gadatliwe komunikaty warstw walidacyjnych)
pokaże `"log": { "level": "DEBUG" }` w `engine.json`.

### Samodzielny jar

```bash
./mvnw package
java -jar target/vulkan-engine-1.0-SNAPSHOT.jar
```

`maven-shade-plugin` pakuje do jednego jara kod, zasoby, LWJGL i JOML.
Biblioteki natywne są tylko dla systemu, na którym zbudowano jar (jar
zbudowany na Linuksie nie ruszy na Windowsie). Tekstury spoza jara
`ResourceManager` szuka względem katalogu, z którego uruchamiasz `java`.

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
  Main.java                 punkt wejścia (wczytuje konfigurację i startuje Engine)
  log/
    Log.java                 centralne logowanie: poziomy, kanały, konsola systemowa + bufor ostatnich wpisów
    LogLevel.java            DEBUG / INFO / WARN / ERROR
    LogEntry.java            jeden wpis: czas od startu, poziom, kanał, treść, wątek
  config/
    EngineConfig.java        rekordy konfiguracji z walidacją (okno, grafika, kamera, sterowanie, shadery, scena)
    ConfigLoader.java        wczytywanie JSON: wartości domyślne + nadpisania z engine.json
    ConfigException.java     błąd w pliku konfiguracji
  core/
    Window.java              okno GLFW
    Engine.java              spina wszystko: init, pętla główna (input -> frameUpdate -> fixedUpdate -> draw), resize, cleanup
    FixedTimestep.java       stały krok symulacji z akumulatorem + alpha do interpolacji
  input/
    InputManager.java        callbacki GLFW -> stan klawiatury/myszy do odpytywania (isKeyDown, mouseDelta...)
  vulkan/
    VulkanContext.java        VkInstance, debug messenger, surface, fizyczne/logiczne urządzenie, kolejki
    SwapChain.java             swapchain + image views
    RenderPass.java            opis załączników koloru/głębi i podpasów
    DescriptorSetLayout.java   "kształt" danych widocznych dla shaderów (UBO + sampler)
    GraphicsPipeline.java      shadery + cały zamrożony stan rysowania (model 3D)
    OverlayPipeline.java       drugi, prostszy pipeline dla nakładki 2D (atlas czcionki, mieszanie alfa, bez testu głębi)
    OverlayMesh.java           bufory wierzchołków nakładki (po jednym na obraz swapchaina), aktualizowane co klatkę
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
  resource/
    ResourceManager.java        centralny podsystem zasobów: odczyt plików (classpath/dysk), sprzątanie, shutdown
    TextureManager.java          wczytywanie tekstur z plików z cache'em i zliczaniem referencji
    ResourceCache.java           wspólny cache "ścieżka -> zasób" z licznikiem referencji
    ResourcePaths.java           normalizacja ścieżek (klucze cache'u)
  scene/
    Vertex.java                 layout jednego wierzchołka (pozycja, kolor, UV, normalna)
    UniformBufferObject.java    dane wysyłane do shaderów co klatkę (kamera + oświetlenie, układ std140)
    SceneLighting.java           słońce (światło kierunkowe) + ambient półsferyczny niebo/ziemia
    Mesh.java                    geometria + jej bufory GPU
    ModelLoader.java             wczytywanie modeli z pamięci (Assimp) - .obj/.fbx/.glb/...; generuje normalne
    Camera.java                  kamera swobodna (pozycja + kierunek, obrót/ruch/zoom) + projekcja
    FlyCameraController.java     sterowanie kamerą z klawiatury i myszy (WASD + PPM)
    FpsCounter.java               liczy FPS uśredniając w oknach czasowych
  ui/
    BakedFont.java               czcionka TTF wypalona do bitmapy (stb_truetype) + położenie każdego znaku
    FontAtlas.java               ta bitmapa jako tekstura na GPU + descriptor set dla shadera
    TextBatch.java               składa napisy i prostokąty (tła) w wierzchołki nakładki na jedną klatkę
    DebugConsole.java            konsola w stylu Quake (~): wpisy z logu, zawijanie, przewijanie, plakietka błędów
    OverlayVertex.java           layout wierzchołka nakładki 2D (pozycja NDC, UV w atlasie, kolor RGBA)

src/main/resources/
  shaders/model.vert, model.frag       shadery GLSL modelu 3D (oświetlenie: Lambert + Blinn-Phong + ambient półsferyczny)
  shaders/overlay.vert, overlay.frag   shadery GLSL nakładki 2D (tekst z atlasu czcionki, tła paneli)
  fonts/DejaVuSansMono.ttf              czcionka nakładki (licencja: fonts/DejaVu-LICENSE.txt)
  textures/chalet.jpg                   tekstura domku
  config/engine-defaults.json           domyślna konfiguracja silnika
  models/chalet.obj                     geometria domku
```

`Engine` jest właścicielem wszystkiego i dzieli zasoby na dwie grupy:

- **stałe** (okno, `VulkanContext`, `CommandPool`, `ResourceManager`, siatka,
  `DescriptorSetLayout`, `SyncObjects`) — żyją tak długo jak aplikacja;
  tekstury z plików nie są tworzone bezpośrednio, tylko pobierane przez
  `resources.textures().acquire(path)` i oddawane przez `release(texture)`,
- **zależne od swapchaina** (`SwapChain`, `RenderPass`, `GraphicsPipeline`,
  `DepthResources`, `Framebuffers`, `UniformBuffers`, `DescriptorSets`,
  `CommandBuffers`) — niszczone i tworzone od nowa przy każdej zmianie
  rozmiaru okna, patrz `Engine.recreateSwapChain()`.

## Pomysły na dalszą naukę

To jest **szkielet**, nie gotowy silnik — świetny punkt wyjścia do
eksperymentów:

- Podmień `chalet.obj`/`chalet.jpg` na własny model (dowolny format, który
  ogarnia Assimp) — wystarczy zmienić `scene.model`/`scene.texture` w
  `engine.json` (oba mogą leżeć na classpath albo na dysku). Formaty
  odwołujące się do innych plików (`.obj` z `mtllib`, `.gltf` z osobnym
  `.bin`) nie zadziałają, bo Assimp dostaje sam plik z pamięci.
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

# Plan rozbudowy: od renderera do silnika gry

Ramowy plan podsystemów, luźno w kolejności z *Game Engine Architecture* Jasona
Gregory'ego, ale dopasowany do tego, co już jest w kodzie. Przy każdym
podsystemie: co mówi o nim książka, co z tego już mamy, i konkretny następny
krok w tym repo. Kolejność w książce nie musi być kolejnością wdrażania -
u dołu jest rekomendacja "co dalej", bo część rozdziałów (audio, AI, skrypty)
ma sens dopiero, gdy jest coś, co przypomina grę, a nie tylko obracający się
domek.

## Stan wyjściowy

Silnik ma warstwę niskiego poziomu Vulkana (instancja, urządzenie, swapchain,
render pass, pipeline, sync) i świeżo wydzieloną warstwę sceny (`Mesh`,
`Material`, `Renderable`, `Camera`). Nie ma jeszcze: systemu wejścia poza
`shouldClose`, prawdziwej pętli gry z krokiem czasowym, menedżera zasobów,
oświetlenia, fizyki, dźwięku, ani pojęcia "obiektu gry" odrębnego od samej
geometrii do narysowania.

Projekt nie jest jeszcze repozytorium gita - warto to zrobić jako pierwszy
krok, żeby każdy podsystem z listy poniżej odpowiadał osobnemu, czytelnemu
commitowi albo branchowi. Ułatwia to też wracanie do konkretnego etapu nauki.

## 0. Fundamenty procesu (rozdz. "Tools and the Development Environment",
   "Review of Software Engineering Principles")

- **Kontrola wersji.** `git init`, `.gitignore` na `target/`. Bez tego nie da
  się porównać "jak wyglądał silnik przed dodaniem fizyki".
- **Logowanie.** Dziś błędy to `throw new RuntimeException("Nie udało się...")`
  rozsiane po klasach Vulkana. Osobny `Logger` z poziomami (`info/warn/error`)
  ułatwi diagnozowanie, gdy dojdą podsystemy działające asynchronicznie
  (audio, streaming zasobów).
- **Asercje.** Gregory mocno naciska na asercje jako narzędzie odławiania
  błędów w debug buildzie. Warto opakować powtarzalny wzorzec
  `if (vkXxx(...) != VK_SUCCESS) throw ...` w jedną metodę `VkCheck.require(...)`.

## 1. Systemy niskopoziomowe (rozdz. "Engine Support Systems")

- **Cykl życia podsystemów.** `Engine.init()` dziś ręcznie tworzy obiekty we
  właściwej kolejności. Gdy dojdzie input, audio, fizyka - warto mieć
  interfejs `Subsystem { init(); update(float dt); shutdown(); }` i listę
  podsystemów w `Engine`, żeby kolejność inicjalizacji/niszczenia była
  jawna i w jednym miejscu, a nie rozproszona.
- **Konfiguracja silnika.** Stałe jak `DEFAULT_WIDTH`, ścieżki zasobów,
  `MAX_FRAMES_IN_FLIGHT` są dziś zaszyte w `Engine`/`SyncObjects`. Wydzielenie
  pliku konfiguracyjnego (properties/JSON) to mały, ale realny krok w stronę
  "silnika", a nie "programu".
  - **Zrobione:** pakiet `engine.config` - `EngineConfig` (rekordy z
    walidacją: okno, grafika, kamera, shadery, scena) i `ConfigLoader`
    (Gson). Domyślne wartości w `config/engine-defaults.json` na classpath,
    plik użytkownika (`./engine.json` albo ścieżka z pierwszego argumentu)
    nadpisuje tylko podane klucze; nieznane klucze są zgłaszane jako
    ostrzeżenie (literówki). Konfigurowalne są m.in. rozmiar okna, vsync,
    `maxFramesInFlight`, warstwy walidacyjne, kamera i ścieżki zasobów.
- **Pamięć.** W C++ Gregory poświęca temu osobny rozdział (własne alokatory).
  W Javie GC zdejmuje większość tego problemu - `MemoryStack` już pokrywa
  rolę alokatora "per klatka" po stronie Vulkana. Nie ma potrzeby budować
  własnych poolów, dopóki profiler czegoś nie pokaże.

## 2. Zasoby i system plików (rozdz. "Resources and the Resource Manager")

- Dziś `Mesh.loadFromFile` i `new Texture(path)` wczytują z dysku za każdym
  wywołaniem, bez cache'u i bez zliczania referencji - dwa `Renderable`
  używające tej samej tekstury musiałyby dziś jawnie współdzielić ten sam
  obiekt `Texture`, o czym trzeba pamiętać ręcznie.
- **Krok:** `ResourceManager` cache'ujący po ścieżce (mapa `String -> Texture`/
  `String -> Mesh`), zwracający ten sam obiekt przy powtórnym żądaniu, ze
  zliczaniem referencji do bezpiecznego `destroy()`.
- **Zrobione (tekstury):** pakiet `engine.resource`:
  - `ResourceManager` - centralny podsystem: warstwa plików (`readBytes`
    szuka najpierw na classpath, potem na dysku), właściciel menedżerów
    typów zasobów, `collectGarbage()` i `shutdown()` z raportem wycieków.
  - `TextureManager` - jedyne miejsce wczytujące tekstury z plików
    (`acquire(path)` / `release(texture)`), plus wbudowana `white()`.
  - `ResourceCache<T>` - wspólny cache ze zliczaniem referencji; zasób z
    licznikiem 0 czeka na `collectGarbage()` zamiast ginąć od razu, bo GPU
    może go jeszcze używać w klatce "w locie".
- **Następny krok:** `MeshManager` na tym samym `ResourceCache` (dziś
  `Mesh.loadFromFile` wciąż czyta plik przy każdym wywołaniu).
- **Opcjonalnie:** hot-reload shaderów - `ShaderCompiler` już kompiluje GLSL
  w locie, więc dopięcie obserwatora plików i przebudowy `GraphicsPipeline`
  na zmianę pliku `.frag`/`.vert` to tania i satysfakcjonująca funkcja przy
  dalszej pracy nad oświetleniem.

## 3. Pętla gry i wejście (rozdz. "The Game Loop and Real-Time Simulation",
   "Human Interface Devices")

- Dziś `loop()` robi `pollEvents()` + `drawFrame()` bez jawnego kroku czasu -
  `glfwGetTime()` jest odczytywane bezpośrednio w `Engine.updateScene()`.
  Zanim dojdzie fizyka, potrzebny jest jawny `deltaTime` i rozdzielenie
  "aktualizacji stanu gry" od "renderowania" (dziś to jedno wywołanie).
- **Krok:** stały krok czasowy (fixed timestep) z akumulatorem dla logiki
  gry/fizyki, renderowanie z interpolacją - to jeden z bardziej cytowanych
  fragmentów książki i realnie zapobiega problemom typu "fizyka zależna od
  FPS-ów".
- **Wejście:** `Window` dziś eksponuje tylko `shouldClose`/rozmiar
  framebuffera. `InputManager` opakowujący callbacki GLFW (klawiatura, mysz)
  z odpytywalnym stanem (`isKeyDown`, `mouseDelta`) pozwoli sterować
  `Camera` z klawiatury - dobry, namacalny sprawdzian tego podsystemu.
- **Zrobione (wejście i kamera):**
  - `engine.input.InputManager` - callbacki GLFW (klawiatura, przyciski
    myszy, kursor, kółko) zamienione na stan do odpytywania: `isKeyDown`,
    zbocza `wasKeyPressed`/`wasKeyReleased` (prawdziwe przez jedną klatkę),
    `mouseDelta`, `scrollDelta`, przechwytywanie kursora z surowym ruchem myszy.
  - `Camera` - kamera swobodna (pozycja + kierunek, `rotate`/`move`/`zoom`,
    blokada pitch przy pionie) zamiast stałego `lookAt`.
  - `FlyCameraController` - sterowanie jak w edytorach: PPM + mysz,
    WASD, Q/E, Shift, kółko = zoom, R = reset; parametry w sekcji
    `controls` konfiguracji.
- **Zrobione (stały krok czasowy):**
  - `engine.core.FixedTimestep` - akumulator czasu, stały krok
    (`simulation.updatesPerSecond`, domyślnie 60), limit długości klatki
    (`simulation.maxFrameTime`, 0,25 s) przeciw "spirali śmierci" i
    `alpha()` - ułamek kroku do interpolacji.
  - Pętla: `input.beginFrame()` -> `pollEvents()` -> `frameUpdate(frameTime)`
    (raz na klatkę: kamera, Esc) -> `fixedUpdate(step)` 0..N razy (symulacja
    świata) -> `drawFrame(alpha)`.
  - `Renderable` pamięta transformację sprzed kroku
    (`savePreviousTransform`), a renderer rysuje stan pośredni
    (`interpolatedModelMatrix`: lerp pozycji/skali, slerp obrotu).
  - Zasada: zbocza wejścia (`wasKeyPressed`) tylko we `frameUpdate` - w
    `fixedUpdate` mogłyby zostać zgubione (0 kroków w klatce) albo
    obsłużone wielokrotnie (kilka kroków).
- **Następny krok tutaj:** podsystemy (`Subsystem` z punktu 1) z własnymi
  `fixedUpdate`/`frameUpdate`, gdy dojdą fizyka i animacja.

## 4. Matematyka 3D (rozdz. "3D Math for Games")

W większości gotowe - JOML (`Matrix4f`, `Vector3f`, `Quaternionf`) robi to, co
Gregory opisuje od podstaw w C++. Do dodania w miarę potrzeb kolejnych
podsystemów: `Frustum` (culling), `AABB`/bounding volume (culling i wstęp do
fizyki), `Ray` (picking myszą).

## 5. Silnik renderujący (rozdz. "The Rendering Engine")

To już jest główny ciężar tego repo, więc naturalnie najdalej zaszedł.

- **Zrobione:** niskopoziomowy renderer Vulkana (urządzenie, swapchain,
  render pass, pipeline), `Material`/`Renderable` jako warstwa "sceny".
- **Brakuje (rosnąco po trudności):**
  1. **Oświetlenie** - normalne w `Vertex`/`ModelLoader` (dziś świadomie
     odrzucane przez `aiProcess_DropNormals`), dane światła w UBO "per
     klatka", Lambert/Blinn-Phong w `model.frag` wykorzystujący pola
     `specularColor`/`shininess`/`reflectivity`, które `Material` już ma, ale
     które dziś nic nie robią. To naturalna kontynuacja wcześniejszej
     rozmowy o oświetleniu.
  2. **Wiele świateł** - tablica świateł w buforze zamiast jednego.
  3. **Culling i sortowanie** - przy większej liczbie `Renderable` filtrowanie
     po frustumie kamery i sortowanie rysowania po materiale/pipeline, żeby
     ograniczyć zmiany stanu na GPU.
  4. **Cienie** - dodatkowy render pass (mapa głębi z punktu widzenia światła).
  5. **Post-processing** - pełnoekranowy przebieg (tone mapping, bloom),
     strukturalnie podobny do już istniejącego `OverlayPipeline`.

## 6. Animacja (rozdz. "Animation Systems")

Potrzebne tylko jeśli mają się pojawić modele z animacją (np. odpowiedź na
wcześniejsze pytanie o format Quake 3 MD3). MD3 to dobry pierwszy krok, bo
używa prostszej animacji przez morphing wierzchołków (kilka klatek siatki
interpolowanych na GPU/CPU), a nie pełnego szkieletu z macierzami kości i
skinningiem, od którego zaczyna książka.

## 7. Kolizje i fizyka (rozdz. "Collision and Rigid Body Dynamics")

- **Krok wstępny:** prosty broad-phase (nakładanie się AABB) do celów
  gameplayowych (triggery, podnoszenie przedmiotów) - zanim pojawi się pełna
  dynamika brył sztywnych.
- **Decyzja do podjęcia później:** pisać własną fizykę czy zintegrować
  gotową bibliotekę. Gregory świadomie omawia oba podejścia jako
  równorzędne - to, jak i kiedy podjąć tę decyzję, warto rozważyć dopiero
  przy tym podsystemie, nie teraz.

## 8. Audio (rozdz. "Audio")

`AudioSystem` oparty o OpenAL (LWJGL ma gotowe bindingi, więc pasuje do
reszty stosu) - odtwarzanie dźwięku przypiętego do pozycji `Renderable`
(dźwięk 3D), gdy scena będzie miała więcej niż jeden obiekt.

## 9. Fundamenty gameplayu (rozdz. "Runtime Gameplay Foundation Systems")

- Dziś "obiekt gry" to tak naprawdę `Renderable` - czysto wizualny, bez
  pojęcia logiki, zdrowia, stanu itd. Prawdziwy obiekt gry łączy transformację
  + wygląd + (docelowo) ciało fizyczne + logikę.
- **Decyzja do podjęcia na tym etapie:** ECS (Entity-Component-System) czy
  prostsza hierarchia obiektowa - książka omawia oba podejścia z ich
  kompromisami. Nie warto przesądzać tego teraz, zanim nie będzie wiadomo,
  jak duża i dynamiczna ma być scena.
- **System zdarzeń** do odsprzęgania podsystemów (np. "obiekt zniszczony"
  konsumowane przez audio i system cząsteczek, gdy oba powstaną).

## 10. Skrypty i AI (rozdz. "Scripting", "AI")

Sensowne dopiero, gdy jest coś grywalnego do skryptowania/sterowania - dobre
do odłożenia na koniec, chyba że celem jest konkretna mechanika wymagająca
AI wcześniej.

## 11. Narzędzia i pipeline (rozdziały o narzędziach rozsiane po książce)

- Serializacja sceny (zapis/odczyt listy `Renderable` z ich transformacjami
  jako JSON/YAML) - przydaje się już przy pracy nad oświetleniem i wieloma
  obiektami.
- Prosty debug UI w locie (np. Dear ImGui przez bindingi LWJGL) do
  podglądu/edycji parametrów `Material` (kolor, połysk, reflectivity) na
  żywo - bardzo skraca iterację przy pracy nad rozdziałem o renderowaniu.

## Rekomendowana kolejność najbliższych kroków

Książka omawia podsystemy mniej więcej w tej kolejności, ale nie trzeba
wdrażać ich 1:1 w tej samej kolejności - część (audio, AI, skrypty) nie ma
jeszcze czego obsługiwać. Biorąc pod uwagę, gdzie kod już jest (renderer z
materiałami, ale bez światła i bez wejścia), naturalne następne kroki to:

1. ~~**Wejście + sterowanie kamerą**~~ (rozdz. 3/Human Interface Devices) -
   zrobione (`InputManager`, `FlyCameraController`), patrz punkt 3.
2. ~~**Stały krok czasowy**~~ (rozdz. 3/Game Loop) - zrobione
   (`FixedTimestep` + interpolacja w `Renderable`), patrz punkt 3.
3. **Oświetlenie** (rozdz. 5/Rendering Engine) - naturalna kontynuacja tego,
   co już jest w `Material`, i najbardziej "widowiskowy" krok na tym etapie.
4. Dopiero potem reszta listy, w miarę potrzeb. (`ResourceManager` z
   `TextureManager` jest już zrobiony - patrz punkt 2.)

Daj znać, od którego punktu zaczynamy - każdy nadaje się na osobną sesję
nauki z książką obok klawiatury.

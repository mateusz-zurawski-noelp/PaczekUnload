package engine.scene;

import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.ArrayList;
import java.util.List;

/**
 * Rysuje tekst złożony z cyfr jako klasyczny wyświetlacz 7-segmentowy,
 * zbudowany z samych prostokątów (2 trójkąty każdy) - bez żadnego fontu czy
 * tekstury, więc nie trzeba wgrywać żadnych dodatkowych zasobów na GPU.
 *
 * Liczba generowanych wierzchołków jest zawsze taka sama ({@link #VERTEX_COUNT}),
 * niezależnie od tego, które segmenty są zapalone - segment "zgaszony" to po
 * prostu prostokąt zdegenerowany do jednego punktu (zerowa powierzchnia, więc
 * niewidoczny). Dzięki temu {@link engine.vulkan.OverlayMesh} może mieć stały
 * rozmiar, a polecenie rysujące nakładkę nigdy nie musi być przebudowywane.
 */
public final class SevenSegmentDigits {

    private static final int DIGIT_COUNT = 3;
    public static final int VERTEX_COUNT = 6 + DIGIT_COUNT * 7 * 6; // tło + (cyfry * 7 segmentów * 2 trójkąty)

    private static final float DIGIT_W = 22f;
    private static final float DIGIT_H = 34f;
    private static final float THICKNESS = 6f;
    private static final float DIGIT_GAP = 8f;
    private static final float PADDING = 10f;
    private static final float MARGIN = 16f;

    private static final Vector3fc BACKGROUND_COLOR = new Vector3f(0.05f, 0.05f, 0.05f);
    private static final Vector3fc SEGMENT_COLOR = new Vector3f(0.95f, 0.85f, 0.15f);

    // Segmenty a..g (zgodnie z klasycznym oznaczeniem wyświetlacza 7-segmentowego),
    // zakodowane bitowo: a=1 b=2 c=4 d=8 e=16 f=32 g=64.
    private static final int[] DIGIT_SEGMENTS = {
            0b0111111, // 0: a b c d e f
            0b0000110, // 1: b c
            0b1011011, // 2: a b d e g
            0b1001111, // 3: a b c d g
            0b1100110, // 4: b c f g
            0b1101101, // 5: a c d f g
            0b1111101, // 6: a c d e f g
            0b0000111, // 7: a b c
            0b1111111, // 8: a b c d e f g
            0b1101111, // 9: a b c d f g
    };

    private SevenSegmentDigits() {
    }

    /** Buduje wierzchołki licznika FPS (3 cyfry, wyrównane do prawej, przycięte do 0..999) w prawym górnym rogu ekranu. */
    public static List<OverlayVertex> buildFps(int fps, int windowWidth, int windowHeight) {

        int clamped = Math.max(0, Math.min(999, fps));
        String digits = String.format("%03d", clamped);

        List<OverlayVertex> vertices = new ArrayList<>(VERTEX_COUNT);

        float contentWidth = DIGIT_COUNT * DIGIT_W + (DIGIT_COUNT - 1) * DIGIT_GAP;
        float boxWidth = contentWidth + 2 * PADDING;
        float boxHeight = DIGIT_H + 2 * PADDING;

        float boxRight = windowWidth - MARGIN;
        float boxLeft = boxRight - boxWidth;
        float boxTop = MARGIN;
        float boxBottom = boxTop + boxHeight;

        addQuad(vertices, boxLeft, boxTop, boxRight, boxBottom, BACKGROUND_COLOR, windowWidth, windowHeight);

        float contentLeft = boxLeft + PADDING;
        float contentTop = boxTop + PADDING;

        for (int i = 0; i < DIGIT_COUNT; i++) {
            float originX = contentLeft + i * (DIGIT_W + DIGIT_GAP);
            int segments = DIGIT_SEGMENTS[digits.charAt(i) - '0'];
            addDigit(vertices, originX, contentTop, segments, windowWidth, windowHeight);
        }

        return vertices;
    }

    private static void addDigit(List<OverlayVertex> out, float x, float y, int segments, int windowWidth, int windowHeight) {

        float w = DIGIT_W;
        float h = DIGIT_H;
        float t = THICKNESS;
        float halfH = h / 2f;

        addSegment(out, (segments & 0b0000001) != 0, x + t, y, x + w - t, y + t, x, y, windowWidth, windowHeight);                     // a: góra
        addSegment(out, (segments & 0b0000010) != 0, x + w - t, y + t, x + w, y + halfH, x, y, windowWidth, windowHeight);             // b: góra-prawo
        addSegment(out, (segments & 0b0000100) != 0, x + w - t, y + halfH, x + w, y + h - t, x, y, windowWidth, windowHeight);         // c: dół-prawo
        addSegment(out, (segments & 0b0001000) != 0, x + t, y + h - t, x + w - t, y + h, x, y, windowWidth, windowHeight);             // d: dół
        addSegment(out, (segments & 0b0010000) != 0, x, y + halfH, x + t, y + h - t, x, y, windowWidth, windowHeight);                 // e: dół-lewo
        addSegment(out, (segments & 0b0100000) != 0, x, y + t, x + t, y + halfH, x, y, windowWidth, windowHeight);                     // f: góra-lewo
        addSegment(out, (segments & 0b1000000) != 0, x + t, y + halfH - t / 2f, x + w - t, y + halfH + t / 2f, x, y, windowWidth, windowHeight); // g: środek
    }

    private static void addSegment(List<OverlayVertex> out, boolean lit, float x0, float y0, float x1, float y1,
                                     float collapseX, float collapseY, int windowWidth, int windowHeight) {
        if (lit) {
            addQuad(out, x0, y0, x1, y1, SEGMENT_COLOR, windowWidth, windowHeight);
        } else {
            // Zgaszony segment: prostokąt zdegenerowany do jednego punktu -> zerowa
            // powierzchnia -> nic się nie rysuje, ale liczba wierzchołków się nie zmienia.
            addQuad(out, collapseX, collapseY, collapseX, collapseY, SEGMENT_COLOR, windowWidth, windowHeight);
        }
    }

    private static void addQuad(List<OverlayVertex> out, float x0, float y0, float x1, float y1,
                                  Vector3fc color, int windowWidth, int windowHeight) {

        Vector2f p00 = toNdc(x0, y0, windowWidth, windowHeight);
        Vector2f p10 = toNdc(x1, y0, windowWidth, windowHeight);
        Vector2f p11 = toNdc(x1, y1, windowWidth, windowHeight);
        Vector2f p01 = toNdc(x0, y1, windowWidth, windowHeight);

        out.add(new OverlayVertex(p00, color));
        out.add(new OverlayVertex(p10, color));
        out.add(new OverlayVertex(p11, color));

        out.add(new OverlayVertex(p00, color));
        out.add(new OverlayVertex(p11, color));
        out.add(new OverlayVertex(p01, color));
    }

    /** Piksele (0,0 = lewy górny róg okna) -> NDC Vulkana (-1,-1 = lewy górny róg, bez odwracania osi Y). */
    private static Vector2f toNdc(float px, float py, int windowWidth, int windowHeight) {
        return new Vector2f((px / windowWidth) * 2f - 1f, (py / windowHeight) * 2f - 1f);
    }
}

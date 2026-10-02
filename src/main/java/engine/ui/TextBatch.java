package engine.ui;

import org.joml.Vector4fc;

/**
 * Zbiera geometrię nakładki 2D na jedną klatkę: napisy i jednolite
 * prostokąty (tła paneli). Wszystko trafia do jednej tablicy wierzchołków
 * rysowanej jednym poleceniem - prostokąty bez tekstu biorą UV z białego
 * paska atlasu, więc nie potrzebują osobnego pipeline'u.
 *
 * Współrzędne podaje się w pikselach okna (0,0 = lewy górny róg, y w dół);
 * zamiana na NDC dzieje się tutaj. Kolejność wywołań = kolejność rysowania
 * (późniejsze na wierzchu), więc najpierw tło, potem tekst.
 *
 * Użycie co klatkę:
 *
 *   batch.begin(width, height);
 *   batch.rect(...); batch.text(...);
 *   overlayMesh.update(imageIndex, batch);
 */
public final class TextBatch {

    private static final int FLOATS_PER_QUAD = 6 * OverlayVertex.FLOATS;

    private final BakedFont font;
    private final float[] data;
    private int floatCount;

    private float ndcScaleX;
    private float ndcScaleY;

    /** @param capacityVertices najwięcej wierzchołków na klatkę (6 na znak lub prostokąt) */
    public TextBatch(BakedFont font, int capacityVertices) {
        this.font = font;
        this.data = new float[capacityVertices * OverlayVertex.FLOATS];
    }

    /** Czyści batch na początku klatki; rozmiar okna potrzebny do przeliczenia pikseli na NDC. */
    public void begin(int windowWidth, int windowHeight) {
        floatCount = 0;
        ndcScaleX = 2.0f / windowWidth;
        ndcScaleY = 2.0f / windowHeight;
    }

    public BakedFont font() {
        return font;
    }

    /** Wysokość wiersza tekstu w pikselach. */
    public float lineHeight() {
        return font.lineHeight();
    }

    /** Szerokość napisu w pikselach (suma przesunięć pióra). */
    public float measure(String text) {
        float width = 0;
        for (int i = 0; i < text.length(); ) {
            int codepoint = text.codePointAt(i);
            width += font.glyph(codepoint).advance();
            i += Character.charCount(codepoint);
        }
        return width;
    }

    /** Jednolity prostokąt od (x0, y0) do (x1, y1). */
    public void rect(float x0, float y0, float x1, float y1, Vector4fc color) {
        float u = font.whiteU();
        float v = font.whiteV();
        quad(x0, y0, x1, y1, u, v, u, v, color);
    }

    /**
     * Napis, którego górna krawędź wiersza leży na y. Pióro zaczyna od
     * całkowitego piksela - inaczej litery byłyby rozmyte przez filtrowanie
     * tekstury między pikselami. Znak nowej linii przenosi do następnego wiersza.
     *
     * @return x tuż za ostatnim znakiem (do doklejania kolejnych napisów)
     */
    public float text(String text, float x, float y, Vector4fc color) {
        float penX = Math.round(x);
        float baseline = Math.round(y + font.ascent());
        for (int i = 0; i < text.length(); ) {
            int codepoint = text.codePointAt(i);
            i += Character.charCount(codepoint);

            if (codepoint == '\n') {
                penX = Math.round(x);
                baseline += Math.round(font.lineHeight());
                continue;
            }
            BakedFont.Glyph g = font.glyph(codepoint);
            if (g.x1() > g.x0()) { // spacja nie ma kształtu - tylko przesuwa pióro
                quad(penX + g.x0(), baseline + g.y0(), penX + g.x1(), baseline + g.y1(),
                        g.u0(), g.v0(), g.u1(), g.v1(), color);
            }
            penX += g.advance();
        }
        return penX;
    }

    private void quad(float x0, float y0, float x1, float y1,
                      float u0, float v0, float u1, float v1, Vector4fc color) {
        if (floatCount + FLOATS_PER_QUAD > data.length) {
            throw new IllegalStateException("Nakładka przekroczyła pojemność " + data.length / OverlayVertex.FLOATS
                    + " wierzchołków - zwiększ OVERLAY_CAPACITY w Engine");
        }
        // Dwa trójkąty: (lewy-górny, prawy-górny, prawy-dolny) i (lewy-górny, prawy-dolny, lewy-dolny).
        vertex(x0, y0, u0, v0, color);
        vertex(x1, y0, u1, v0, color);
        vertex(x1, y1, u1, v1, color);
        vertex(x0, y0, u0, v0, color);
        vertex(x1, y1, u1, v1, color);
        vertex(x0, y1, u0, v1, color);
    }

    private void vertex(float px, float py, float u, float v, Vector4fc color) {
        // Piksele -> NDC Vulkana: (-1,-1) = lewy górny róg, y rośnie w dół (bez odwracania).
        data[floatCount++] = px * ndcScaleX - 1.0f;
        data[floatCount++] = py * ndcScaleY - 1.0f;
        data[floatCount++] = u;
        data[floatCount++] = v;
        data[floatCount++] = color.x();
        data[floatCount++] = color.y();
        data[floatCount++] = color.z();
        data[floatCount++] = color.w();
    }

    /** Surowe dane wierzchołków (ważne pierwsze {@link #floatCount()} wartości) - dla OverlayMesh. */
    public float[] data() {
        return data;
    }

    public int floatCount() {
        return floatCount;
    }

    public int vertexCount() {
        return floatCount / OverlayVertex.FLOATS;
    }
}

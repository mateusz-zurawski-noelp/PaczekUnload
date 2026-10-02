package engine.ui;

import org.lwjgl.stb.STBTTAlignedQuad;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTTPackContext;
import org.lwjgl.stb.STBTTPackRange;
import org.lwjgl.stb.STBTTPackedchar;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.stb.STBTruetype.*;
import static org.lwjgl.system.MemoryStack.stackPush;

/**
 * Czcionka "wypalona" do bitmapy - część CPU, bez Vulkana (wgranie na GPU
 * robi {@link FontAtlas}). Wszystkie potrzebne znaki są rasteryzowane raz,
 * przy starcie, do jednej bitmapy (atlasu) + opis, gdzie w atlasie leży
 * każdy znak i jak go ustawić względem linii bazowej.
 *
 * Jak to działa:
 *  1. stb_truetype czyta plik .ttf (krzywe opisujące kształty liter) i
 *     rasteryzuje znaki w skali szarości - wartość piksela to "jaka część
 *     piksela jest pokryta literą" (0..255), czyli gotowy antyaliasing krawędzi.
 *  2. Packer układa prostokąty znaków ciasno w bitmapie atlasu.
 *     Oversampling 2x2 rasteryzuje znaki w podwójnej rozdzielczości i
 *     filtruje - litery są gładsze.
 *  3. Pod znakami dokładamy pasek w pełni pokrytych pikseli - tam celują UV
 *     prostokątów bez tekstu (tła paneli), dzięki czemu tła i tekst rysuje
 *     jeden pipeline jednym poleceniem.
 *
 * Wspierane znaki: ASCII, Latin-1 i Latin Extended-A (m.in. ąćęłńóśźż).
 * Pozostałe są rysowane jako '?'.
 */
public final class BakedFont {

    /** Położenie znaku: prostokąt względem pióra (linia bazowa, y w dół), UV w atlasie i przesunięcie pióra. */
    public record Glyph(float x0, float y0, float x1, float y1,
                        float u0, float v0, float u1, float v1, float advance) {
    }

    public static final int ATLAS_WIDTH = 1024;
    private static final int ATLAS_GLYPH_HEIGHT = 1024;
    private static final int WHITE_STRIP_HEIGHT = 4;
    public static final int ATLAS_HEIGHT = ATLAS_GLYPH_HEIGHT + WHITE_STRIP_HEIGHT;

    /** Zakresy znaków Unicode {pierwszy, liczba}. */
    private static final int[][] RANGES = {
            {0x20, 0x7F - 0x20},   // ASCII drukowalne
            {0xA0, 0x100 - 0xA0},  // Latin-1 Supplement (ó, ä, °, ©...)
            {0x100, 0x180 - 0x100} // Latin Extended-A (ą, ć, ę, ł, ń, ś, ź, ż...)
    };

    private final byte[] coverage;
    private final Map<Integer, Glyph> glyphs;
    private final Glyph fallback;
    private final float ascent;
    private final float lineHeight;

    private BakedFont(byte[] coverage, Map<Integer, Glyph> glyphs, float ascent, float lineHeight) {
        this.coverage = coverage;
        this.glyphs = glyphs;
        this.fallback = glyphs.get((int) '?');
        this.ascent = ascent;
        this.lineHeight = lineHeight;
    }

    /**
     * @param fontData    zawartość pliku .ttf (np. z ResourceManager.readBytes);
     *                    potrzebna tylko w tej metodzie, potem można ją zwolnić
     * @param name        nazwa do komunikatów o błędach
     * @param pixelHeight wysokość czcionki w pikselach
     */
    public static BakedFont bake(ByteBuffer fontData, String name, float pixelHeight) {
        ByteBuffer bitmap = MemoryUtil.memCalloc(ATLAS_WIDTH * ATLAS_HEIGHT);
        try (MemoryStack stack = stackPush()) {

            // ----- metryki: odległość linii bazowej od góry wiersza i wysokość wiersza -----
            STBTTFontinfo info = STBTTFontinfo.malloc(stack);
            if (!stbtt_InitFont(info, fontData)) {
                throw new RuntimeException("Nie udało się odczytać czcionki " + name);
            }
            float scale = stbtt_ScaleForPixelHeight(info, pixelHeight);
            IntBuffer pAscent = stack.mallocInt(1);
            IntBuffer pDescent = stack.mallocInt(1);
            IntBuffer pLineGap = stack.mallocInt(1);
            stbtt_GetFontVMetrics(info, pAscent, pDescent, pLineGap);
            float ascent = pAscent.get(0) * scale;
            float lineHeight = (pAscent.get(0) - pDescent.get(0) + pLineGap.get(0)) * scale;

            // ----- pakowanie znaków do atlasu -----
            STBTTPackContext packContext = STBTTPackContext.malloc(stack);
            // Packer dostaje tylko górne ATLAS_GLYPH_HEIGHT wierszy - dolny pasek zostaje na "biel".
            if (!stbtt_PackBegin(packContext, bitmap, ATLAS_WIDTH, ATLAS_GLYPH_HEIGHT, ATLAS_WIDTH, 1)) {
                throw new RuntimeException("Nie udało się rozpocząć pakowania atlasu czcionki " + name);
            }
            stbtt_PackSetOversampling(packContext, 2, 2);

            STBTTPackRange.Buffer ranges = STBTTPackRange.malloc(RANGES.length, stack);
            STBTTPackedchar.Buffer[] charData = new STBTTPackedchar.Buffer[RANGES.length];
            for (int r = 0; r < RANGES.length; r++) {
                charData[r] = STBTTPackedchar.malloc(RANGES[r][1], stack);
                ranges.get(r).set(pixelHeight, RANGES[r][0], null, RANGES[r][1], charData[r], (byte) 0, (byte) 0);
            }
            boolean packed = stbtt_PackFontRanges(packContext, fontData, 0, ranges);
            stbtt_PackEnd(packContext);
            if (!packed) {
                throw new RuntimeException("Znaki czcionki " + name + " nie zmieściły się w atlasie "
                        + ATLAS_WIDTH + "x" + ATLAS_GLYPH_HEIGHT + " - zmniejsz rozmiar czcionki");
            }

            // ----- położenie każdego znaku (raz, do zwykłych obiektów Javy) -----
            Map<Integer, Glyph> glyphs = new HashMap<>();
            STBTTAlignedQuad quad = STBTTAlignedQuad.malloc(stack);
            FloatBuffer penX = stack.mallocFloat(1);
            FloatBuffer penY = stack.mallocFloat(1);
            for (int r = 0; r < RANGES.length; r++) {
                for (int i = 0; i < RANGES[r][1]; i++) {
                    penX.put(0, 0.0f);
                    penY.put(0, 0.0f);
                    // UV z GetPackedQuad są liczone względem podanej tu wysokości - podajemy
                    // pełną wysokość atlasu (z paskiem pod znakami), bo taką ma tekstura.
                    stbtt_GetPackedQuad(charData[r], ATLAS_WIDTH, ATLAS_HEIGHT, i, penX, penY, quad, false);
                    glyphs.put(RANGES[r][0] + i, new Glyph(
                            quad.x0(), quad.y0(), quad.x1(), quad.y1(),
                            quad.s0(), quad.t0(), quad.s1(), quad.t1(),
                            penX.get(0)));
                }
            }

            // ----- pasek pełnego pokrycia pod znakami -----
            for (int i = ATLAS_WIDTH * ATLAS_GLYPH_HEIGHT; i < ATLAS_WIDTH * ATLAS_HEIGHT; i++) {
                bitmap.put(i, (byte) 0xFF);
            }

            byte[] coverage = new byte[ATLAS_WIDTH * ATLAS_HEIGHT];
            bitmap.get(0, coverage);
            return new BakedFont(coverage, glyphs, ascent, lineHeight);
        } finally {
            MemoryUtil.memFree(bitmap);
        }
    }

    public Glyph glyph(int codepoint) {
        return glyphs.getOrDefault(codepoint, fallback);
    }

    /** Odległość od górnej krawędzi wiersza do linii bazowej (w pikselach). */
    public float ascent() {
        return ascent;
    }

    /** Odstęp między kolejnymi wierszami tekstu (w pikselach). */
    public float lineHeight() {
        return lineHeight;
    }

    /** UV środka paska pełnego pokrycia - dla jednolitych prostokątów. */
    public float whiteU() {
        return 0.5f;
    }

    public float whiteV() {
        return (ATLAS_GLYPH_HEIGHT + WHITE_STRIP_HEIGHT / 2.0f) / ATLAS_HEIGHT;
    }

    /** Pokrycie pikseli atlasu (0..255 jako bajt bez znaku), wiersz po wierszu, ATLAS_WIDTH x ATLAS_HEIGHT. */
    public byte[] coverage() {
        return coverage;
    }
}

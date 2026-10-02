package engine.ui;

import engine.input.InputManager;
import engine.log.Log;
import engine.log.LogEntry;
import engine.log.LogLevel;
import org.joml.Vector4f;
import org.joml.Vector4fc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Konsola silnika w stylu Quake: panel wysuwany z górnej krawędzi ekranu,
 * pokazujący na żywo wpisy z {@link Log}. Na razie tylko do czytania -
 * bez wpisywania poleceń.
 *
 *   ~ (klawisz pod Esc)    - otwórz / zamknij
 *   Esc                    - zamknij (gdy otwarta - nie zamyka wtedy silnika)
 *   PageUp / PageDown      - przewijanie o stronę
 *   kółko myszy            - przewijanie o 3 linie
 *   Home / End             - na początek historii / z powrotem do najnowszych
 *
 * Gdy konsola jest zamknięta, a w logu pojawiły się nowe ostrzeżenia lub
 * błędy, w lewym górnym rogu ekranu pojawia się plakietka z ich liczbą -
 * komunikat warstw walidacyjnych nie zginie niezauważony w terminalu.
 *
 * Wydajność: wpisy są formatowane i zawijane do szerokości ekranu raz, przy
 * pojawieniu się (pobierane przyrostowo przez Log.since), a co klatkę
 * rysowane są tylko widoczne linie. Pełne przeformatowanie następuje tylko
 * po zmianie szerokości okna.
 */
public final class DebugConsole {

    /** Jedna linia na ekranie - wpis z logu po zawinięciu może dać ich kilka. */
    private record Line(String text, Vector4fc color) {
    }

    private static final float HEIGHT_FRACTION = 0.45f;
    private static final float SLIDE_SECONDS = 0.15f;
    private static final float PADDING = 10.0f;
    private static final int MAX_LINES = 10_000;
    private static final int WHEEL_LINES = 3;

    private static final Vector4fc BACKGROUND = new Vector4f(0.02f, 0.02f, 0.04f, 0.88f);
    private static final Vector4fc BORDER = new Vector4f(0.95f, 0.85f, 0.15f, 1.0f);
    private static final Vector4fc FOOTER = new Vector4f(0.45f, 0.45f, 0.50f, 1.0f);
    private static final Vector4fc DEBUG_COLOR = new Vector4f(0.55f, 0.55f, 0.60f, 1.0f);
    private static final Vector4fc INFO_COLOR = new Vector4f(0.88f, 0.88f, 0.88f, 1.0f);
    private static final Vector4fc WARN_COLOR = new Vector4f(1.00f, 0.80f, 0.30f, 1.0f);
    private static final Vector4fc ERROR_COLOR = new Vector4f(1.00f, 0.40f, 0.40f, 1.0f);
    private static final Vector4fc BADGE_BACKGROUND = new Vector4f(0.35f, 0.05f, 0.05f, 0.85f);

    private final InputManager input;

    private final List<LogEntry> entries = new ArrayList<>();
    private final Deque<Line> lines = new ArrayDeque<>();
    private long lastSequence;
    private int wrapColumns = -1;

    private boolean open;
    private float openness;          // 0 = schowana, 1 = w pełni wysunięta (animacja)
    private int scrollOffset;        // ile linii od dołu przewinięto w górę (0 = najnowsze)
    private int visibleLines = 1;
    private int unseenProblems;      // nowe WARN/ERROR od ostatniego zamknięcia

    public DebugConsole(InputManager input) {
        this.input = input;
    }

    /** Czy konsola przejmuje klawiaturę i mysz (silnik ma wtedy wstrzymać sterowanie kamerą). */
    public boolean isCapturingInput() {
        return open;
    }

    /** Wywoływać raz na klatkę, przed sterowaniem kamerą. */
    public void update(float frameTime) {
        if (input.wasKeyPressed(GLFW_KEY_GRAVE_ACCENT)) {
            setOpen(!open);
        } else if (open && input.wasKeyPressed(GLFW_KEY_ESCAPE)) {
            setOpen(false);
        }

        float step = frameTime / SLIDE_SECONDS;
        openness = open ? Math.min(1.0f, openness + step) : Math.max(0.0f, openness - step);

        if (!open) {
            return;
        }
        int page = Math.max(1, visibleLines - 1);
        if (input.wasKeyPressed(GLFW_KEY_PAGE_UP)) {
            scrollOffset += page;
        }
        if (input.wasKeyPressed(GLFW_KEY_PAGE_DOWN)) {
            scrollOffset -= page;
        }
        if (input.wasKeyPressed(GLFW_KEY_HOME)) {
            scrollOffset = lines.size(); // clampScroll() przytnie do najstarszej linii
        }
        if (input.wasKeyPressed(GLFW_KEY_END)) {
            scrollOffset = 0;
        }
        scrollOffset += (int) Math.round(input.scrollDelta().y()) * WHEEL_LINES;
        clampScroll();
    }

    private void setOpen(boolean value) {
        open = value;
        if (open) {
            unseenProblems = 0;
        }
    }

    private void clampScroll() {
        scrollOffset = Math.clamp(scrollOffset, 0, Math.max(0, lines.size() - visibleLines));
    }

    /** Dopisuje konsolę (albo plakietkę o nowych błędach) do nakładki tej klatki. */
    public void draw(TextBatch batch, int windowWidth, int windowHeight) {
        int columns = Math.max(20, (int) ((windowWidth - 2 * PADDING) / batch.measure("M")));
        pullNewEntries(columns);

        if (openness <= 0.0f) {
            drawBadge(batch);
            return;
        }

        float lineHeight = batch.lineHeight();
        float panelHeight = Math.round(windowHeight * HEIGHT_FRACTION);
        float top = Math.round((openness - 1.0f) * panelHeight);   // wysuwanie: od -panelHeight do 0
        float bottom = top + panelHeight;

        batch.rect(0, top, windowWidth, bottom, BACKGROUND);
        batch.rect(0, bottom - 2, windowWidth, bottom, BORDER);

        // Stopka: pozycja w historii i skróty klawiszowe.
        float footerY = bottom - PADDING - lineHeight;
        visibleLines = Math.max(1, (int) ((footerY - top - PADDING) / lineHeight));
        clampScroll();
        int last = lines.size() - scrollOffset;              // wyłącznie
        int first = Math.max(0, last - visibleLines);
        String position = lines.isEmpty() ? "pusto"
                : String.format(Locale.ROOT, "linie %d-%d z %d", first + 1, last, lines.size());
        String footer = position + (scrollOffset > 0 ? "  (przewinięte - End wraca do najnowszych)" : "")
                + "   |   PgUp/PgDn, kółko: przewijanie   ~/Esc: zamknij";
        batch.text(footer, PADDING, footerY, FOOTER);

        // Linie od najstarszej widocznej w dół, tak żeby najnowsza była tuż nad stopką.
        float y = footerY - (last - first) * lineHeight;
        int index = 0;
        for (Line line : lines) {
            if (index >= first && index < last) {
                batch.text(line.text(), PADDING, y, line.color());
                y += lineHeight;
            }
            index++;
        }
    }

    private void drawBadge(TextBatch batch) {
        if (unseenProblems == 0) {
            return;
        }
        String text = "Nowe ostrzeżenia/błędy: " + unseenProblems + "  (~ otwiera konsolę)";
        float margin = 16.0f;
        float padding = 8.0f;
        batch.rect(margin, margin, margin + batch.measure(text) + 2 * padding,
                margin + batch.lineHeight() + 2 * padding, BADGE_BACKGROUND);
        batch.text(text, margin + padding, margin + padding, ERROR_COLOR);
    }

    /** Pobiera nowe wpisy z logu i zawija je do bieżącej szerokości (całość - tylko gdy szerokość się zmieniła). */
    private void pullNewEntries(int columns) {
        List<LogEntry> fresh = Log.since(lastSequence);
        for (LogEntry entry : fresh) {
            lastSequence = entry.sequence();
            entries.add(entry);
            if (!open && entry.level().ordinal() >= LogLevel.WARN.ordinal()) {
                unseenProblems++;
            }
        }
        // Konsola trzyma tyle wpisów co historia logu - starsze i tak wypadły z bufora.
        while (entries.size() > MAX_LINES) {
            entries.removeFirst();
        }

        if (columns != wrapColumns) {
            wrapColumns = columns;
            lines.clear();
            for (LogEntry entry : entries) {
                appendWrapped(entry);
            }
        } else {
            int before = lines.size();
            for (LogEntry entry : fresh) {
                appendWrapped(entry);
            }
            // Przewinięty widok ma zostać w miejscu, mimo że na dole przybyły linie.
            if (scrollOffset > 0) {
                scrollOffset += lines.size() - before;
            }
        }
        while (lines.size() > MAX_LINES) {
            lines.removeFirst();
        }
    }

    /** Formatuje wpis i dzieli go na linie mieszczące się w wrapColumns znakach. */
    private void appendWrapped(LogEntry entry) {
        Vector4fc color = switch (entry.level()) {
            case DEBUG -> DEBUG_COLOR;
            case INFO -> INFO_COLOR;
            case WARN -> WARN_COLOR;
            case ERROR -> ERROR_COLOR;
        };
        String prefix = String.format(Locale.ROOT, "[%8.3f] %-5s %-10s | ", entry.timeSeconds(), entry.level(), entry.channel());
        String indent = " ".repeat(prefix.length());
        int width = Math.max(10, wrapColumns - prefix.length());

        boolean firstLine = true;
        for (String paragraph : entry.message().split("\n", -1)) {
            int start = 0;
            do {
                int end = Math.min(paragraph.length(), start + width);
                // Łamiemy na ostatniej spacji, jeśli jest w drugiej połowie linii - czytelniej niż w pół słowa.
                if (end < paragraph.length()) {
                    int space = paragraph.lastIndexOf(' ', end);
                    if (space > start + width / 2) {
                        end = space + 1;
                    }
                }
                lines.addLast(new Line((firstLine ? prefix : indent) + paragraph.substring(start, end).stripTrailing(), color));
                firstLine = false;
                start = end;
            } while (start < paragraph.length());
        }
    }
}

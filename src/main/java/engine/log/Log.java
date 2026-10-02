package engine.log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Centralne logowanie silnika (Gregory, "Engine Support Systems" -
 * "Debug Printing and Logging"). Zamiast rozsianych System.out/err każdy
 * podsystem pisze tutaj, z poziomem ważności i nazwą kanału:
 *
 *   Log.info("Resources", "Wczytano teksturę " + path);
 *   Log.warn("Config", "Nieznany klucz \"window.widht\"");
 *   Log.error("Engine", "Nieobsłużony wyjątek", exception);
 *   Log.debug("Vulkan", () -> drogieDoZbudowania());   // tekst budowany tylko, gdy DEBUG włączony
 *
 * Każdy wpis:
 *  - trafia na konsolę systemową (INFO/DEBUG na stdout, WARN/ERROR na stderr),
 *  - zostaje w buforze cyklicznym ostatnich N wpisów w pamięci - z niego
 *    będzie czytać konsola na ekranie ({@link #recent}, {@link #lastSequence}).
 *
 * Dlaczego statyczna klasa, a nie obiekt przekazywany do konstruktorów?
 * Log jest potrzebny wszędzie, także zanim powstanie Engine (wczytywanie
 * konfiguracji) i w miejscach bez dostępu do niego (callback warstw
 * walidacyjnych Vulkana wołany przez sterownik). To jeden z niewielu
 * przypadków, w których globalny stan jest uzasadniony - większość silników
 * ma log właśnie jako globalny podsystem.
 *
 * Bezpieczny wątkowo: wszystkie operacje na buforze są pod jednym zamkiem
 * (wpisów jest mało - dziesiątki na sekundę, nie miliony - więc prosty
 * synchronized w zupełności wystarcza).
 */
public final class Log {

    public static final int DEFAULT_HISTORY_SIZE = 1000;

    private static final long START_NANOS = System.nanoTime();
    private static final Object LOCK = new Object();

    private static volatile LogLevel minLevel = LogLevel.INFO;

    // Bufor cykliczny: entries[head] to najstarszy wpis, nowe nadpisują najstarsze.
    private static LogEntry[] entries = new LogEntry[DEFAULT_HISTORY_SIZE];
    private static int head;
    private static int size;
    private static long sequence;

    private Log() {
    }

    /**
     * Ustawia minimalny poziom i rozmiar historii (z konfiguracji). Zmiana
     * rozmiaru zachowuje najnowsze wpisy, które się mieszczą.
     */
    public static void configure(LogLevel level, int historySize) {
        if (historySize < 1) {
            throw new IllegalArgumentException("Rozmiar historii logu musi być >= 1");
        }
        synchronized (LOCK) {
            List<LogEntry> kept = recentLocked(historySize);
            entries = new LogEntry[historySize];
            head = 0;
            size = 0;
            for (LogEntry entry : kept) {
                appendLocked(entry);
            }
        }
        minLevel = level;
    }

    public static boolean isEnabled(LogLevel level) {
        return level.ordinal() >= minLevel.ordinal();
    }

    public static void debug(String channel, String message) {
        log(LogLevel.DEBUG, channel, message);
    }

    /** Wariant z leniwie budowanym tekstem - Supplier nie jest wołany, gdy DEBUG jest wyłączony. */
    public static void debug(String channel, Supplier<String> message) {
        if (isEnabled(LogLevel.DEBUG)) {
            log(LogLevel.DEBUG, channel, message.get());
        }
    }

    public static void info(String channel, String message) {
        log(LogLevel.INFO, channel, message);
    }

    public static void warn(String channel, String message) {
        log(LogLevel.WARN, channel, message);
    }

    public static void error(String channel, String message) {
        log(LogLevel.ERROR, channel, message);
    }

    /**
     * Błąd z wyjątkiem: do historii trafia skrót (komunikat + typ i treść
     * wyjątku), a na stderr także pełny stack trace.
     */
    public static void error(String channel, String message, Throwable cause) {
        String summary = message + ": " + cause;
        LogEntry entry = logEntry(LogLevel.ERROR, channel, summary);
        if (entry != null) {
            StringWriter trace = new StringWriter();
            cause.printStackTrace(new PrintWriter(trace));
            System.err.print(trace);
        }
    }

    public static void log(LogLevel level, String channel, String message) {
        logEntry(level, channel, message);
    }

    private static LogEntry logEntry(LogLevel level, String channel, String message) {
        if (!isEnabled(level)) {
            return null;
        }
        double time = (System.nanoTime() - START_NANOS) / 1_000_000_000.0;
        LogEntry entry;
        synchronized (LOCK) {
            entry = new LogEntry(++sequence, time, level, channel, message, Thread.currentThread().getName());
            appendLocked(entry);
        }
        // Wypisywanie poza zamkiem - wolne I/O nie blokuje innych wątków chcących logować.
        if (level.ordinal() >= LogLevel.WARN.ordinal()) {
            System.err.println(entry.format());
        } else {
            System.out.println(entry.format());
        }
        return entry;
    }

    private static void appendLocked(LogEntry entry) {
        int index = (head + size) % entries.length;
        entries[index] = entry;
        if (size < entries.length) {
            size++;
        } else {
            head = (head + 1) % entries.length; // bufor pełny - najstarszy wpis wypada
        }
    }

    /** Najwyżej max najnowszych wpisów, od najstarszego do najnowszego (kopia - bezpieczna do iterowania). */
    public static List<LogEntry> recent(int max) {
        synchronized (LOCK) {
            return recentLocked(max);
        }
    }

    private static List<LogEntry> recentLocked(int max) {
        int count = Math.min(max, size);
        List<LogEntry> result = new ArrayList<>(count);
        for (int i = size - count; i < size; i++) {
            result.add(entries[(head + i) % entries.length]);
        }
        return result;
    }

    /**
     * Wpisy nowsze niż afterSequence (od najstarszego), o ile są jeszcze w
     * historii - do przyrostowego odczytu: zapamiętaj sequence ostatniego
     * odczytanego wpisu i przy następnym razie pytaj tylko o nowsze.
     */
    public static List<LogEntry> since(long afterSequence) {
        synchronized (LOCK) {
            long newer = sequence - afterSequence;
            return recentLocked((int) Math.min(Math.max(newer, 0), size));
        }
    }

    /** Numer ostatniego wpisu (0 = nic jeszcze nie zalogowano) - do wykrywania nowych wpisów. */
    public static long lastSequence() {
        synchronized (LOCK) {
            return sequence;
        }
    }
}

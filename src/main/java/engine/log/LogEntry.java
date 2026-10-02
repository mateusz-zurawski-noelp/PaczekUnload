package engine.log;

/**
 * Jeden wpis w logu. Niemutowalny, więc można go bezpiecznie przekazać do
 * innego wątku (np. konsola na ekranie czyta wpisy dodane przez wątek audio).
 *
 * @param sequence    numer kolejny wpisu od startu (rośnie o 1) - pozwala
 *                    sprawdzić, czy od ostatniego odczytu pojawiło się coś nowego
 * @param timeSeconds czas od startu silnika w sekundach
 * @param level       poziom ważności
 * @param channel     kanał / podsystem, z którego pochodzi wpis (np. "Vulkan", "Config")
 * @param message     treść
 * @param thread      nazwa wątku, który zalogował wpis
 */
public record LogEntry(long sequence, double timeSeconds, LogLevel level,
                       String channel, String message, String thread) {

    /** Postać tekstowa jak na konsoli systemowej: "[   1.234] WARN  Config     | treść". */
    public String format() {
        return String.format(java.util.Locale.ROOT, "[%8.3f] %-5s %-10s | %s", timeSeconds, level, channel, message);
    }
}

package engine.log;

/**
 * Poziom ważności wpisu w logu - od najmniej do najbardziej istotnego.
 * Minimalny poziom ustawia się w konfiguracji (log.level): wpisy poniżej
 * niego są odrzucane od razu, jeszcze przed zbudowaniem tekstu (patrz
 * Log.debug z Supplierem).
 */
public enum LogLevel {
    /** Szczegóły przydatne przy szukaniu błędu, domyślnie wyłączone (np. gadatliwe komunikaty Vulkana). */
    DEBUG,
    /** Normalny przebieg: co zostało wczytane, jaka karta graficzna, ile trwało. */
    INFO,
    /** Coś jest nie tak, ale silnik działa dalej (brak warstw walidacyjnych, literówka w konfiguracji). */
    WARN,
    /** Błąd - coś się nie udało; zwykle tuż przed zatrzymaniem silnika. */
    ERROR
}

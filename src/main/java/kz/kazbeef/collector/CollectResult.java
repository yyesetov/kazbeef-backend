package kz.kazbeef.collector;

import java.util.List;

/**
 * Результат работы сборщика.
 *
 * @param events   записи для конвейера
 * @param fetched  сколько записей прочитано из источника (до схлопывания и фильтрации)
 * @param warnings некритичные проблемы; при их наличии запуск получает статус PARTIAL
 */
public record CollectResult(List<RawEvent> events, int fetched, List<String> warnings) {

    public CollectResult {
        events = List.copyOf(events);
        warnings = List.copyOf(warnings);
    }
}

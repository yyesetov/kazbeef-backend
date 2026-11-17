package kz.kazbeef.collector;

import java.time.Instant;

/**
 * Сборщик данных одного источника. Новый источник = новая реализация (Spring-бин) +
 * запись в таблице {@code source} + расписание в {@code kazbeef.collectors.<code>}.
 * Ядро конвейера при этом не меняется.
 */
public interface Collector {

    /** Код источника, совпадает с {@code source.code}: "WAHIS", "PROMED" ... */
    String sourceCode();

    /**
     * Получает записи источника, появившиеся или изменившиеся после {@code since}.
     *
     * @param since время последнего успешного запуска; {@code null}, если успешных запусков не было
     */
    CollectResult collect(Instant since);
}

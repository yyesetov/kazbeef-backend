package kz.kazbeef.kb;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param dir               каталог Markdown-версий документов
 * @param chatModel         модель для ответов в чате
 * @param topChunks         сколько фрагментов документов передавать модели
 * @param maxQuestionLength предельная длина вопроса
 * @param ratePerMinute     вопросов в минуту с одного IP
 * @param maxAnswerTokens   предельная длина ответа
 */
@ConfigurationProperties(prefix = "kazbeef.kb")
public record KbProperties(
        @DefaultValue("/data/kb") String dir,
        @DefaultValue("claude-opus-5-5") String chatModel,
        @DefaultValue("8") int topChunks,
        @DefaultValue("1000") int maxQuestionLength,
        @DefaultValue("10") int ratePerMinute,
        @DefaultValue("1500") long maxAnswerTokens
) {
}

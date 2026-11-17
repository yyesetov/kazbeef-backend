package kz.kazbeef.notify;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param publicUrl     адрес сервиса для ссылок в сообщениях
 * @param riskThreshold мгновенное оповещение при риске не ниже порога
 * @param maxAgeDays    не оповещать о событиях старше стольких дней
 * @param ruminantOnly  только события, значимые для КРС/МРС
 */
@ConfigurationProperties(prefix = "kazbeef.notify")
public record NotifyProperties(
        @DefaultValue("http://localhost:8080") String publicUrl,
        @DefaultValue("60") int riskThreshold,
        @DefaultValue("14") int maxAgeDays,
        @DefaultValue("true") boolean ruminantOnly,
        @DefaultValue("0 0 9 * * *") String digestCron,
        @DefaultValue Telegram telegram,
        @DefaultValue Email email
) {
    public record Telegram(String botToken, String chatId) {
        public boolean enabled() {
            return botToken != null && !botToken.isBlank() && chatId != null && !chatId.isBlank();
        }
    }

    public record Email(String to, String from) {
        public boolean enabled() {
            return to != null && !to.isBlank();
        }
    }
}

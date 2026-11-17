package kz.kazbeef.config;

import kz.kazbeef.collector.Collector;
import kz.kazbeef.collector.CollectorRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;

import java.time.ZoneId;
import java.util.List;

/**
 * Регистрирует расписание для каждого бина {@link Collector} по настройкам
 * {@code kazbeef.collectors.<CODE>}. Новый сборщик подхватывается без изменения этого класса.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(SchedulingConfig.class);

    private final List<Collector> collectors;
    private final CollectorRunner runner;
    private final KazbeefProperties props;

    public SchedulingConfig(List<Collector> collectors, CollectorRunner runner, KazbeefProperties props) {
        this.collectors = collectors;
        this.runner = runner;
        this.props = props;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(Math.max(2, collectors.size()));
        scheduler.setThreadNamePrefix("collector-");
        scheduler.initialize();
        registrar.setTaskScheduler(scheduler);

        for (Collector collector : collectors) {
            String code = collector.sourceCode();
            // ключи карты могут прийти в другом регистре (например, из переменных окружения)
            KazbeefProperties.Schedule schedule = props.collectors().entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(code))
                    .map(java.util.Map.Entry::getValue)
                    .findFirst().orElse(null);
            if (schedule == null || schedule.cron() == null || schedule.cron().isBlank()) {
                log.warn("Для сборщика {} не задано расписание kazbeef.collectors.{}.cron, только ручной запуск", code, code);
                continue;
            }
            if (!schedule.enabled()) {
                log.info("Сборщик {} отключён в настройках", code);
                continue;
            }
            CronTrigger trigger = new CronTrigger(schedule.cron(), ZoneId.of(schedule.zone()));
            registrar.addCronTask(new CronTask(() -> runner.run(code), trigger));
            log.info("Сборщик {}: расписание '{}' ({})", code, schedule.cron(), schedule.zone());
        }
    }
}

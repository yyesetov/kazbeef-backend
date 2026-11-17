-- Этап 3–5: межисточниковая дедупликация, русские названия стран, журнал оповещений.

-- Запись источника, прикреплённая к чужому событию как подтверждение, не перезаписывает его поля
ALTER TABLE event_source ADD COLUMN is_primary BOOLEAN NOT NULL DEFAULT TRUE;

-- Написания страны в русскоязычных источниках (ИАЦ)
ALTER TABLE country ADD COLUMN synonyms JSONB NOT NULL DEFAULT '[]'::jsonb;
UPDATE country SET synonyms = '["Республика Казахстан", "РК", "Kazakhstan"]' WHERE iso3 = 'KAZ';
UPDATE country SET synonyms = '["Российская Федерация", "РФ", "Russian Federation"]' WHERE iso3 = 'RUS';
UPDATE country SET synonyms = '["КНР", "Китайская Народная Республика", "China"]' WHERE iso3 = 'CHN';
UPDATE country SET synonyms = '["Кыргызстан", "Киргизстан", "Кыргызская Республика", "Kyrgyz Republic"]' WHERE iso3 = 'KGZ';
UPDATE country SET synonyms = '["Республика Узбекистан"]' WHERE iso3 = 'UZB';
UPDATE country SET synonyms = '["Туркмения"]' WHERE iso3 = 'TKM';
UPDATE country SET synonyms = '["МНР"]' WHERE iso3 = 'MNG';

-- Что и куда уже отправлено: повторный запуск сборщика не шлёт оповещение второй раз
CREATE TABLE notification_log (
    id         BIGSERIAL PRIMARY KEY,
    event_id   BIGINT REFERENCES event (id) ON DELETE CASCADE,
    kind       VARCHAR(16)  NOT NULL CHECK (kind IN ('alert', 'digest')),
    channel    VARCHAR(16)  NOT NULL,
    -- для дайджеста: дата, за которую он собран
    digest_day DATE,
    sent_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    ok         BOOLEAN      NOT NULL,
    error_text TEXT
);
CREATE UNIQUE INDEX ux_notification_alert ON notification_log (event_id, channel) WHERE kind = 'alert' AND ok;
CREATE UNIQUE INDEX ux_notification_digest ON notification_log (digest_day, channel) WHERE kind = 'digest' AND ok;

-- Очередь ИИ: сколько раз пытались обработать событие (чтобы не зацикливаться на сбойной записи)
ALTER TABLE event ADD COLUMN ai_attempts SMALLINT NOT NULL DEFAULT 0;

-- EMPRES-i+: данные доступны только после входа в учётную запись FAO, открытого API нет
UPDATE source SET active = FALSE,
    note = 'Не подключён: с мая 2026 данные EMPRES-i+ отдаются только авторизованным пользователям FAO, открытого API нет. Нужна учётная запись или разрешение FAO.'
WHERE code = 'EMPRES';
UPDATE source SET note = 'Не подключён: адрес и формат открытой ленты событий новой платформы GLEWS+ не найдены.' WHERE code = 'GLEWS';

-- Страны, откуда Казахстан ввозит говядину и баранину, и группы стран наблюдения.
-- Импорт говядины в 2025 г. (USDA FAS): Беларусь 73%, Россия 14,5%, Украина 8,2%, далее Бразилия и Парагвай.
-- Беларусь и Россия уже в справочнике; добавляются Украина, Бразилия, Парагвай.

-- Почему страна в наблюдении. От группы зависит оценка риска для событий без координат.
--   neighbor — Казахстан и сопредельные страны
--   cis      — прочие страны СНГ
--   importer — поставщики мяса КРС/МРС в Казахстан
ALTER TABLE country ADD COLUMN watch_group VARCHAR(16)
    CHECK (watch_group IN ('neighbor', 'cis', 'importer'));

UPDATE country SET watch_group = 'neighbor' WHERE iso3 IN ('KAZ', 'RUS', 'CHN', 'KGZ', 'UZB', 'TKM', 'MNG');
UPDATE country SET watch_group = 'cis' WHERE iso3 IN ('ARM', 'AZE', 'TJK', 'MDA');
-- Беларусь — член СНГ, но главный поставщик говядины: группа «импорт» важнее
UPDATE country SET watch_group = 'importer' WHERE iso3 = 'BLR';

INSERT INTO country (iso2, iso3, name_ru, name_en, is_priority, watch_group, synonyms) VALUES
    ('UA', 'UKR', 'Украина',  'Ukraine',  TRUE, 'importer', '["Republic of Ukraine"]'),
    ('BR', 'BRA', 'Бразилия', 'Brazil',   TRUE, 'importer', '["Федеративная Республика Бразилия", "Brasil"]'),
    ('PY', 'PRY', 'Парагвай', 'Paraguay', TRUE, 'importer', '["Республика Парагвай"]')
ON CONFLICT (iso3) DO UPDATE SET
    iso2        = coalesce(country.iso2, excluded.iso2),
    name_ru     = coalesce(country.name_ru, excluded.name_ru),
    is_priority = TRUE,
    watch_group = excluded.watch_group,
    synonyms    = excluded.synonyms;

-- Страны СНГ, которых не было в справочнике: Беларусь, Армения, Азербайджан, Таджикистан, Молдова.
-- Приоритетные, как Казахстан и соседи: по ним собирает ИАЦ (priority-only), выше доля в оценке риска.
-- Сборщик мог уже завести страну сам (как неприоритетную, без русского названия) — тогда запись дополняется.
-- name_en совпадает с написанием WAHIS; остальные варианты — в синонимах.
INSERT INTO country (iso2, iso3, name_ru, name_en, is_priority, synonyms) VALUES
    ('BY', 'BLR', 'Беларусь',    'Belarus',    TRUE, '["Белоруссия", "Республика Беларусь", "Republic of Belarus"]'),
    ('AM', 'ARM', 'Армения',     'Armenia',    TRUE, '["Республика Армения", "Republic of Armenia"]'),
    ('AZ', 'AZE', 'Азербайджан', 'Azerbaijan', TRUE, '["Азербайджанская Республика", "Republic of Azerbaijan"]'),
    ('TJ', 'TJK', 'Таджикистан', 'Tajikistan', TRUE, '["Республика Таджикистан", "Republic of Tajikistan"]'),
    ('MD', 'MDA', 'Молдова',     'Moldova',    TRUE, '["Молдавия", "Республика Молдова", "Moldova (Rep. of)", "Republic of Moldova"]')
ON CONFLICT (iso3) DO UPDATE SET
    iso2        = coalesce(country.iso2, excluded.iso2),
    name_ru     = coalesce(country.name_ru, excluded.name_ru),
    is_priority = TRUE,
    synonyms    = excluded.synonyms;

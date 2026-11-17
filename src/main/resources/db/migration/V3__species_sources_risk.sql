-- Этап 2: виды животных (КРС/МРС), новые источники, поля риска, расширение справочников.

-- ---------- виды ----------
ALTER TABLE animal_species
    ADD COLUMN group_code VARCHAR(16) NOT NULL DEFAULT 'OTHER'
        CHECK (group_code IN ('CATTLE', 'SMALL_RUMINANT', 'OTHER'));

UPDATE animal_species SET group_code = 'CATTLE',
    synonyms = '["Bovine", "Bovines", "Cattle (domestic)", "Bovidae", "Cow", "Cows", "КРС", "крупный рогатый скот", "коровы", "корова", "телята"]'
WHERE lower(name_en) = 'cattle';
UPDATE animal_species SET group_code = 'SMALL_RUMINANT',
    synonyms = '["Ovine", "Sheep (domestic)", "овца", "овцы", "бараны", "МРС", "мелкий рогатый скот"]'
WHERE lower(name_en) = 'sheep';
UPDATE animal_species SET group_code = 'SMALL_RUMINANT',
    synonyms = '["Goat", "Caprine", "Goats (domestic)", "коза", "козы"]'
WHERE lower(name_en) = 'goats';
UPDATE animal_species SET
    synonyms = '["Horse", "Equine", "Equidae", "Equids", "лошадь", "лошади", "кони"]'
WHERE lower(name_en) = 'horses';

INSERT INTO animal_species (name_ru, name_en, group_code, synonyms) VALUES
    ('Буйволы',         'Buffaloes', 'CATTLE', '["Buffalo", "Water buffalo", "буйвол", "буйволы"]'),
    ('Яки',             'Yaks',      'CATTLE', '["Yak", "як", "яки"]'),
    ('Свиньи',          'Swine',     'OTHER',  '["Pig", "Pigs", "Porcine", "Suidae", "Wild boar", "свинья", "свиньи", "кабан", "кабаны", "дикий кабан"]'),
    ('Птица',           'Birds',     'OTHER',  '["Poultry", "Bird", "Domestic birds", "Chickens", "Chicken", "птица", "птицы", "куры", "домашняя птица"]'),
    ('Верблюды',        'Camels',    'OTHER',  '["Camelidae", "Camel", "Dromedary", "Dromedaries", "верблюд", "верблюды"]'),
    ('Олени',           'Deer',      'OTHER',  '["Cervidae", "Reindeer", "олень", "олени", "северные олени", "северный олень"]'),
    ('Собаки',          'Dogs',      'OTHER',  '["Dog", "Canine", "собака", "собаки"]'),
    ('Кошки',           'Cats',      'OTHER',  '["Cat", "Feline", "кошка", "кошки"]'),
    ('Дикие животные',  'Wildlife',  'OTHER',  '["Wildlife (species unspecified)", "Wild animals", "Wild", "дикие животные", "дикие птицы"]');

-- Событие может затрагивать несколько видов
CREATE TABLE event_species (
    event_id   BIGINT NOT NULL REFERENCES event (id) ON DELETE CASCADE,
    species_id BIGINT NOT NULL REFERENCES animal_species (id),
    PRIMARY KEY (event_id, species_id)
);
CREATE INDEX ix_event_species_species ON event_species (species_id);

-- ---------- событие ----------
ALTER TABLE event
    -- поражены КРС/МРС или болезнь опасна для них
    ADD COLUMN ruminant_relevant BOOLEAN NOT NULL DEFAULT FALSE,
    -- расстояние до границы Казахстана, км (0 внутри страны)
    ADD COLUMN distance_to_kz_km DOUBLE PRECISION,
    -- балл риска для Казахстана, 0..100
    ADD COLUMN risk_score        INTEGER;
CREATE INDEX ix_event_ruminant ON event (ruminant_relevant, report_date);
CREATE INDEX ix_event_risk ON event (risk_score);

-- ---------- болезни ----------
-- Вес болезни для КРС/МРС: 1 (не опасна) .. 5 (особо опасная трансграничная)
ALTER TABLE disease
    ADD COLUMN severity SMALLINT NOT NULL DEFAULT 2 CHECK (severity BETWEEN 1 AND 5);

-- Записи, созданные сборщиком из-за неполных синонимов, сливаем со справочными
UPDATE event SET disease_id = (SELECT id FROM disease WHERE code = 'HPAI')
WHERE disease_id IN (SELECT id FROM disease
                     WHERE code IS NULL AND lower(name_en) LIKE 'influenza a viruses of high pathogenicity%');
DELETE FROM disease
WHERE code IS NULL AND lower(name_en) LIKE 'influenza a viruses of high pathogenicity%';

UPDATE disease SET severity = 5, synonyms = synonyms || '["ящур"]'::jsonb WHERE code = 'FMD';
UPDATE disease SET severity = 4, synonyms = synonyms || '["заразный узелковый дерматит", "заразный узелковый дерматит КРС", "нодулярный дерматит КРС", "ЗУД"]'::jsonb WHERE code = 'LSD';
UPDATE disease SET severity = 1, synonyms = synonyms || '["АЧС"]'::jsonb WHERE code = 'ASF';
UPDATE disease SET severity = 1, synonyms = synonyms ||
    '["Influenza A viruses of high pathogenicity (Inf. with) (non-poultry including wild birds) (2017-)", "High pathogenicity avian influenza viruses (poultry) (Inf. with) (2017-)", "Highly pathogenic avian influenza", "грипп птиц", "высокопатогенный грипп птиц", "ВПГП"]'::jsonb
WHERE code = 'HPAI';
UPDATE disease SET severity = 4, synonyms = synonyms || '["сибирская язва"]'::jsonb WHERE code = 'ANTHRAX';
UPDATE disease SET severity = 3, synonyms = synonyms || '["Brucella ovis (Inf. with)", "Ovine epididymitis (Brucella ovis)", "бруцеллез"]'::jsonb WHERE code = 'BRUC';
UPDATE disease SET severity = 4, synonyms = synonyms || '["Sheep pox and goat pox virus (Inf. with)", "Sheeppox", "Goatpox", "оспа овец", "оспа коз", "оспа овец и коз"]'::jsonb WHERE code = 'SGP';
UPDATE disease SET severity = 5, synonyms = synonyms || '["ЧМЖ", "чума мелких жвачных животных"]'::jsonb WHERE code = 'PPR';
UPDATE disease SET severity = 3, synonyms = synonyms || '["блютанг", "катаральная лихорадка овец"]'::jsonb WHERE code = 'BT';
UPDATE disease SET severity = 3, synonyms = synonyms || '["бешенство"]'::jsonb WHERE code = 'RAB';

INSERT INTO disease (code, name_ru, name_en, severity, synonyms) VALUES
    ('CCPP', 'Контагиозная плевропневмония коз', 'Contagious caprine pleuropneumonia', 4,
        '["Mycoplasma capricolum subsp. capripneumoniae (Inf. with)", "CCPP", "КПП коз"]'),
    ('CBPP', 'Контагиозная плевропневмония КРС', 'Contagious bovine pleuropneumonia', 5,
        '["Mycoplasma mycoides subsp. mycoides SC (Inf. with) (Contagious bovine pleuropneumonia)", "Mycoplasma mycoides subsp. mycoides (Inf. with)", "CBPP", "КПП КРС"]'),
    ('RVF', 'Лихорадка долины Рифт', 'Rift Valley fever', 5,
        '["Rift Valley fever virus (Inf. with)", "RVF"]'),
    ('RP', 'Чума КРС', 'Rinderpest', 5,
        '["Rinderpest virus (Inf. with)"]'),
    ('BSE', 'Губкообразная энцефалопатия КРС', 'Bovine spongiform encephalopathy', 3,
        '["BSE", "ГЭ КРС"]'),
    ('TB', 'Туберкулёз', 'Bovine tuberculosis', 3,
        '["Mycobacterium tuberculosis complex (Inf. with)", "Mycobacterium bovis (Inf. with)", "Tuberculosis", "туберкулез", "туберкулёз КРС", "туберкулез КРС"]'),
    ('EBL', 'Лейкоз КРС', 'Enzootic bovine leukosis', 2,
        '["Bovine leukosis", "лейкоз", "лейкоз КРС", "энзоотический лейкоз КРС"]'),
    ('EHD', 'Эпизоотическая геморрагическая болезнь оленей', 'Epizootic haemorrhagic disease', 3,
        '["Epizootic haemorrhagic disease virus (Inf. with)", "Epizootic hemorrhagic disease", "EHD", "ЭГБО"]'),
    ('QF', 'Лихорадка Ку', 'Q fever', 2,
        '["Coxiella burnetii (Inf. with)", "Ку-лихорадка", "коксиеллёз", "коксиеллез"]'),
    ('SCRAPIE', 'Скрепи', 'Scrapie', 2, '["скрепи овец"]'),
    ('SBV', 'Болезнь Шмалленберга', 'Schmallenberg virus infection', 2,
        '["Schmallenberg virus", "Schmallenberg disease", "Шмалленберг"]'),
    ('CCHF', 'Конго-Крымская геморрагическая лихорадка', 'Crimean Congo haemorrhagic fever', 3,
        '["Crimean Congo hemorrhagic fever", "Crimean-Congo haemorrhagic fever", "CCHF", "ККГЛ"]'),
    ('HS', 'Геморрагическая септицемия', 'Haemorrhagic septicaemia', 3,
        '["Pasteurella multocida serotypes 6:b and 6:e (Inf. with)", "Hemorrhagic septicemia", "пастереллез", "пастереллёз"]'),
    ('BLACKLEG', 'Эмфизематозный карбункул (эмкар)', 'Blackleg', 3,
        '["Emphysematous carbuncle", "Clostridium chauvoei (Inf. with)", "эмкар", "эмфизематозный карбункул"]'),
    ('BVD', 'Вирусная диарея КРС', 'Bovine viral diarrhoea', 2,
        '["Bovine viral diarrhea", "BVD", "вирусная диарея"]'),
    ('IBR', 'Инфекционный ринотрахеит КРС', 'Infectious bovine rhinotracheitis', 2,
        '["Infectious bovine rhinotracheitis/infectious pustular vulvovaginitis", "IBR/IPV", "IBR", "ИРТ", "ИРТ КРС"]'),
    ('PARATB', 'Паратуберкулёз', 'Paratuberculosis', 2,
        '["Mycobacterium avium subsp. paratuberculosis (Inf. with)", "Johne''s disease", "паратуберкулез"]'),
    ('LEPTO', 'Лептоспироз', 'Leptospirosis', 2, '["Leptospira (Inf. with)"]'),
    ('THEIL', 'Тейлериоз', 'Theileriosis', 2,
        '["Theileria annulata, Theileria orientalis and Theileria parva (Inf. with)", "Theileria (Inf. with)"]'),
    ('ECHIN', 'Эхинококкоз', 'Echinococcosis', 2,
        '["Echinococcus granulosus (Inf. with)", "Echinococcus multilocularis (Inf. with)"]'),
    ('MV', 'Висна-маеди', 'Maedi-visna', 2, '["Maedi visna", "висна маеди"]'),
    ('CAE', 'Артрит-энцефалит коз', 'Caprine arthritis/encephalitis', 2, '["Caprine arthritis encephalitis", "CAE"]'),
    ('CA', 'Инфекционная агалактия', 'Contagious agalactia', 2, '["инфекционная агалактия овец и коз"]'),
    ('EAE', 'Энзоотический аборт овец', 'Enzootic abortion of ewes', 2,
        '["Enzootic abortion of ewes (ovine chlamydiosis)", "Chlamydia abortus (Inf. with)", "хламидиоз овец"]'),
    ('ND', 'Болезнь Ньюкасла', 'Newcastle disease', 1,
        '["Newcastle disease virus (Inf. with)", "ньюкаслская болезнь"]'),
    ('CSF', 'Классическая чума свиней', 'Classical swine fever', 1,
        '["Classical swine fever virus (Inf. with)", "КЧС"]'),
    ('GLANDERS', 'Сап', 'Glanders', 1, '["Burkholderia mallei (Inf. with) (Glanders)", "Burkholderia mallei (Inf. with)"]')
ON CONFLICT (lower(name_en)) DO UPDATE
    SET code = EXCLUDED.code, name_ru = EXCLUDED.name_ru, severity = EXCLUDED.severity,
        synonyms = EXCLUDED.synonyms, needs_review = FALSE;

-- Восприимчивость КРС/МРС к болезням (остальные связи на этапе 1)
INSERT INTO disease_species (disease_id, species_id)
SELECT d.id, s.id FROM disease d, animal_species s
WHERE (d.code, s.name_en) IN (
    ('FMD', 'Buffaloes'), ('FMD', 'Yaks'), ('FMD', 'Swine'), ('FMD', 'Camels'), ('FMD', 'Deer'),
    ('LSD', 'Buffaloes'), ('LSD', 'Yaks'),
    ('ASF', 'Swine'), ('CSF', 'Swine'),
    ('HPAI', 'Birds'), ('ND', 'Birds'),
    ('GLANDERS', 'Horses'),
    ('ANTHRAX', 'Buffaloes'), ('ANTHRAX', 'Yaks'), ('ANTHRAX', 'Camels'), ('ANTHRAX', 'Swine'), ('ANTHRAX', 'Deer'),
    ('BRUC', 'Buffaloes'), ('BRUC', 'Yaks'), ('BRUC', 'Camels'), ('BRUC', 'Swine'), ('BRUC', 'Deer'),
    ('PPR', 'Camels'),
    ('BT', 'Deer'),
    ('RAB', 'Dogs'), ('RAB', 'Cats'), ('RAB', 'Wildlife'), ('RAB', 'Camels'),
    ('CCPP', 'Goats'), ('CCPP', 'Sheep'),
    ('CBPP', 'Cattle'), ('CBPP', 'Buffaloes'), ('CBPP', 'Yaks'),
    ('RVF', 'Cattle'), ('RVF', 'Sheep'), ('RVF', 'Goats'), ('RVF', 'Camels'),
    ('RP', 'Cattle'), ('RP', 'Buffaloes'), ('RP', 'Yaks'),
    ('BSE', 'Cattle'),
    ('TB', 'Cattle'), ('TB', 'Buffaloes'), ('TB', 'Goats'), ('TB', 'Deer'),
    ('EBL', 'Cattle'),
    ('EHD', 'Cattle'), ('EHD', 'Deer'),
    ('QF', 'Cattle'), ('QF', 'Sheep'), ('QF', 'Goats'),
    ('SCRAPIE', 'Sheep'), ('SCRAPIE', 'Goats'),
    ('SBV', 'Cattle'), ('SBV', 'Sheep'), ('SBV', 'Goats'),
    ('CCHF', 'Cattle'), ('CCHF', 'Sheep'), ('CCHF', 'Goats'),
    ('HS', 'Cattle'), ('HS', 'Buffaloes'),
    ('BLACKLEG', 'Cattle'), ('BLACKLEG', 'Sheep'),
    ('BVD', 'Cattle'),
    ('IBR', 'Cattle'),
    ('PARATB', 'Cattle'), ('PARATB', 'Sheep'), ('PARATB', 'Goats'),
    ('LEPTO', 'Cattle'), ('LEPTO', 'Sheep'), ('LEPTO', 'Goats'), ('LEPTO', 'Swine'), ('LEPTO', 'Dogs'),
    ('THEIL', 'Cattle'), ('THEIL', 'Buffaloes'),
    ('ECHIN', 'Cattle'), ('ECHIN', 'Sheep'), ('ECHIN', 'Goats'), ('ECHIN', 'Dogs'),
    ('MV', 'Sheep'), ('MV', 'Goats'),
    ('CAE', 'Goats'),
    ('CA', 'Sheep'), ('CA', 'Goats'),
    ('EAE', 'Sheep'), ('EAE', 'Goats')
)
ON CONFLICT DO NOTHING;

-- ---------- источники ----------
INSERT INTO source (code, name, url, trust_level, active) VALUES
    ('EMPRES', 'EMPRES-i+ (FAO Global Animal Disease Information System)', 'https://empres-i.apps.fao.org', 'confirmed', TRUE),
    ('IAC',    'ИАЦ Россельхознадзора (ФГБУ «ВНИИЗЖ»)', 'https://fsvps.gov.ru/jepizooticheskaja-situacija/', 'confirmed', TRUE),
    ('EFSA',   'EFSA (European Food Safety Authority)', 'https://www.efsa.europa.eu', 'analytics', TRUE),
    ('GLEWS',  'GLEWS+ (FAO / WHO / WOAH)', 'https://www.glews.net', 'signal', FALSE),
    ('PROMED', 'ProMED-mail (ISID)', 'https://promedmail.org', 'signal', FALSE);

-- Пояснение, почему источник не собирается (показывается в админке)
ALTER TABLE source ADD COLUMN note TEXT;
UPDATE source SET note = 'Отложен: RSS закрыт, доступ к сообщениям только по платной подписке.' WHERE code = 'PROMED';
UPDATE source SET note = 'Открытая лента событий не найдена; подключается после появления публичного доступа.' WHERE code = 'GLEWS';

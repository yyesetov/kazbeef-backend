-- Справочники для старта.

INSERT INTO source (code, name, url, trust_level) VALUES
    ('WAHIS', 'WAHIS (WOAH World Animal Health Information System)', 'https://wahis.woah.org', 'confirmed');

-- name_en совпадает с написанием WAHIS (/api/v1/pi/country/list)
INSERT INTO country (iso2, iso3, name_ru, name_en, is_priority) VALUES
    ('KZ', 'KAZ', 'Казахстан',     'Kazakhstan',              TRUE),
    ('RU', 'RUS', 'Россия',        'Russia',                  TRUE),
    ('CN', 'CHN', 'Китай',         'China (People''s Rep. of)', TRUE),
    ('KG', 'KGZ', 'Киргизия',      'Kyrgyzstan',              TRUE),
    ('UZ', 'UZB', 'Узбекистан',    'Uzbekistan',              TRUE),
    ('TM', 'TKM', 'Туркменистан',  'Turkmenistan',            TRUE),
    ('MN', 'MNG', 'Монголия',      'Mongolia',                TRUE);

-- Синонимы включают написание WAHIS. При сравнении регистр, пробелы и суффикс "(Inf. with)" игнорируются.
INSERT INTO disease (code, name_ru, name_en, synonyms) VALUES
    ('FMD',  'Ящур',                          'Foot and mouth disease',
        '["Foot and mouth disease virus (Inf. with)", "FMD", "Foot-and-mouth disease"]'),
    ('LSD',  'Нодулярный дерматит',           'Lumpy skin disease',
        '["Lumpy skin disease virus (Inf. with)", "LSD"]'),
    ('ASF',  'Африканская чума свиней',       'African swine fever',
        '["African swine fever virus (Inf. with)", "ASF"]'),
    ('HPAI', 'Высокопатогенный грипп птиц',   'High pathogenicity avian influenza',
        '["High pathogenicity avian influenza viruses (poultry) (Inf. with)", "Influenza A viruses of high pathogenicity (Inf. with non-poultry including wild birds) (2017-)", "HPAI", "Avian influenza"]'),
    ('ANTHRAX', 'Сибирская язва',             'Anthrax',
        '["Bacillus anthracis (Inf. with)"]'),
    ('BRUC', 'Бруцеллёз',                     'Brucellosis',
        '["Brucella abortus (Inf. with)", "Brucella melitensis (Inf. with)", "Brucella suis (Inf. with)"]'),
    ('SGP',  'Оспа овец и коз',               'Sheep pox and goat pox',
        '["Sheep pox", "Goat pox"]'),
    ('PPR',  'Чума мелких жвачных',           'Peste des petits ruminants',
        '["Peste des petits ruminants virus (Inf. with)", "PPR"]'),
    ('BT',   'Блутанг',                       'Bluetongue',
        '["Bluetongue virus (Inf. with)", "BTV"]'),
    ('RAB',  'Бешенство',                     'Rabies',
        '["Rabies virus (Inf. with)"]');

INSERT INTO animal_species (name_ru, name_en, synonyms) VALUES
    ('КРС',    'Cattle', '["Bovine", "Cattle (domestic)", "Bovidae"]'),
    ('Овцы',   'Sheep',  '["Ovine", "Sheep (domestic)"]'),
    ('Козы',   'Goats',  '["Goat", "Caprine", "Goats (domestic)"]'),
    ('Лошади', 'Horses', '["Horse", "Equine", "Equidae"]');

INSERT INTO disease_species (disease_id, species_id)
SELECT d.id, s.id FROM disease d, animal_species s
WHERE (d.code, s.name_en) IN (
    ('FMD', 'Cattle'), ('FMD', 'Sheep'), ('FMD', 'Goats'),
    ('LSD', 'Cattle'),
    ('ANTHRAX', 'Cattle'), ('ANTHRAX', 'Sheep'), ('ANTHRAX', 'Goats'), ('ANTHRAX', 'Horses'),
    ('BRUC', 'Cattle'), ('BRUC', 'Sheep'), ('BRUC', 'Goats'),
    ('SGP', 'Sheep'), ('SGP', 'Goats'),
    ('PPR', 'Sheep'), ('PPR', 'Goats'),
    ('BT', 'Cattle'), ('BT', 'Sheep'), ('BT', 'Goats'),
    ('RAB', 'Cattle'), ('RAB', 'Sheep'), ('RAB', 'Goats'), ('RAB', 'Horses')
);

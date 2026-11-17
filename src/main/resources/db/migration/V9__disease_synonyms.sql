-- Написания WAHIS, которые не сопоставились со справочником при загрузке стран СНГ.
-- Автоматически заведённые записи (needs_review) сливаются со справочными: события переносятся, записи удаляются.

UPDATE disease SET synonyms = synonyms ||
    '["Theileria lestoquardi, Theileria luwenshuni and Theileria uilenbergi (Inf. with) (2024-)", "Theileria lestoquardi, Theileria luwenshuni and Theileria uilenbergi (Inf. with)", "тейлериоз МРС"]'::jsonb
WHERE code = 'THEIL';

UPDATE disease SET synonyms = synonyms ||
    '["Chlamydia abortus (Inf. with) (Enzootic abortion of ewes, ovine chlamydiosis)"]'::jsonb
WHERE code = 'EAE';

UPDATE event SET disease_id = (SELECT id FROM disease WHERE code = 'THEIL')
WHERE disease_id IN (SELECT id FROM disease WHERE code IS NULL AND name_en ILIKE 'Theileria lestoquardi%');
DELETE FROM disease WHERE code IS NULL AND name_en ILIKE 'Theileria lestoquardi%';

UPDATE event SET disease_id = (SELECT id FROM disease WHERE code = 'EAE')
WHERE disease_id IN (SELECT id FROM disease WHERE code IS NULL AND name_en ILIKE 'Chlamydia abortus%');
DELETE FROM disease WHERE code IS NULL AND name_en ILIKE 'Chlamydia abortus%';

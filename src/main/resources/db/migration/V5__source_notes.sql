-- Уточнение пояснения: основной API EMPRES-i+ отвечает «Token not provided» без входа.
UPDATE source SET
    note = 'Не подключён: API EMPRES-i+ отдаёт данные только после входа в учётную запись FAO, открытого доступа нет. Нужна учётная запись или разрешение FAO.'
WHERE code = 'EMPRES';

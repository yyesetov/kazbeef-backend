# Развёртывание на сервере (Ubuntu / Debian)

Схема:

```
Браузер ──► https://kazbeef-frontend.yyessetov.workers.dev   (интерфейс, Cloudflare Workers)
   │
   └──► https://<IP-сервера>.sslip.io   ──► Caddy (HTTPS) ──► app:8080 ──► db
                                         ваш сервер, Docker
```

- Интерфейс обращается к API напрямую. Бэкенд разрешает запросы только с адреса интерфейса (CORS).
- HTTPS-сертификат выпускает Caddy автоматически (Let's Encrypt).
- Своего домена не нужно: `sslip.io` превращает IP в имя (`203-0-113-10.sslip.io` → `203.0.113.10`). Когда появится домен, достаточно поменять `API_HOST`.
- Наружу открыты только порты 80 и 443. Приложение и база из интернета не видны.

Ниже `203.0.113.10` — пример IP. Везде подставляйте IP своего сервера.

---

## 1. Подготовка сервера

Подключитесь по SSH и установите Docker:

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER
```

Выйдите из SSH и зайдите снова, чтобы группа `docker` применилась. Проверка:

```bash
docker compose version
```

Откройте порты (если включён файрвол `ufw`):

```bash
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw allow 443/udp
sudo ufw enable
```

Если сервер у облачного провайдера, откройте порты 80 и 443 и в его панели (security group / firewall).

Узнайте внешний IP сервера:

```bash
curl -4 ifconfig.me
```

## 2. Код

```bash
git clone https://github.com/yyesetov/kazbeef-backend.git ~/kazbeef
cd ~/kazbeef
```

## 3. Настройки `.env`

```bash
cp .env.example .env
nano .env
```

Заполните:

```env
DB_PASSWORD=<длинный случайный пароль>
ADMIN_USERNAME=admin
ADMIN_PASSWORD=<пароль для входа в интерфейс>
KAZBEEF_CONTACT_EMAIL=<ваш e-mail>

# адрес API: IP сервера через дефисы + .sslip.io
API_HOST=203-0-113-10.sslip.io
# адрес интерфейса — с него разрешены запросы
KAZBEEF_CORS_ORIGINS=https://kazbeef-frontend.yyessetov.workers.dev
# ссылки в оповещениях ведут на интерфейс
KAZBEEF_PUBLIC_URL=https://kazbeef-frontend.yyessetov.workers.dev

# ИИ (перевод, резюме, чат)
ANTHROPIC_API_KEY=sk-ant-...
KAZBEEF_AI_MODEL=claude-haiku-5-5
KAZBEEF_CHAT_MODEL=claude-haiku-5-5
```

Случайный пароль можно получить командой `openssl rand -base64 24`.

Закройте файл для чужих глаз:

```bash
chmod 600 .env
```

## 4. Запуск

```bash
docker compose -f docker-compose.yml -f deploy/docker-compose.server.yml up -d --build
```

Первая сборка — 3–5 минут. Поднимутся три контейнера: `db`, `app`, `caddy`.

Чтобы не набирать два `-f` каждый раз, добавьте в `.env` строку:

```env
COMPOSE_FILE=docker-compose.yml:deploy/docker-compose.server.yml
```

После этого достаточно `docker compose up -d --build`, `docker compose logs -f` и т.д. Все команды ниже записаны в этом коротком виде.

## 5. Проверка

```bash
docker compose ps
curl https://203-0-113-10.sslip.io/actuator/health
```

Ответ `{"status":"UP"}` — API работает по HTTPS. Первый запрос может занять до минуты: Caddy получает сертификат. Если ответа нет — `docker compose logs caddy`: чаще всего закрыт порт 80 или 443.

Проверка CORS (должен вернуться заголовок `access-control-allow-origin` с адресом интерфейса):

```bash
curl -s -D - -o /dev/null -H "Origin: https://kazbeef-frontend.yyessetov.workers.dev" \
     https://203-0-113-10.sslip.io/public/chat/status | grep -i access-control
```

## 6. Подключить интерфейс к API

В проекте интерфейса адрес API задаётся при сборке переменной `VITE_API_URL`.

**Сборка в Cloudflare** (Workers Builds, подключён репозиторий): Cloudflare → Workers & Pages → `kazbeef-frontend` → Settings → Build → **Variables and secrets** → добавить `VITE_API_URL` = `https://203-0-113-10.sslip.io` → пересобрать (Deployments → Retry deployment, или новый коммит).

**Сборка у себя** и выкладка через `wrangler`:

```bash
VITE_API_URL=https://203-0-113-10.sslip.io npm run build
npx wrangler deploy
```

Проверка: откройте https://kazbeef-frontend.yyessetov.workers.dev — должна появиться форма входа, а после входа — данные. В инструментах разработчика (F12 → Network) запросы идут на `…sslip.io` и отвечают 200.

## 7. Первая загрузка данных

Войдите в интерфейс → **Управление → Источники** → «Запустить» у WAHIS, ИАЦ и EFSA. Или дождитесь ночного запуска по расписанию.

---

## Обслуживание

| Задача | Команда (в `~/kazbeef`) |
|---|---|
| Обновить код и перезапустить | `git pull && docker compose up -d --build` |
| Применить изменения `.env` | `docker compose up -d` |
| Логи | `docker compose logs -f app` |
| Состояние | `docker compose ps` |
| Резервная копия базы | `docker compose exec db pg_dump -U kazbeef kazbeef \| gzip > backup-$(date +%F).sql.gz` |
| Копия документов базы знаний | `docker run --rm -v kazbeef_kbdata:/kb -v $PWD:/out alpine tar czf /out/kb-$(date +%F).tgz -C /kb .` |
| Восстановление базы | `gunzip -c backup.sql.gz \| docker compose exec -T db psql -U kazbeef kazbeef` |

Ежедневная резервная копия — строка в `crontab -e`:

```cron
0 2 * * * cd ~/kazbeef && docker compose exec -T db pg_dump -U kazbeef kazbeef | gzip > ~/backups/kazbeef-$(date +\%F).sql.gz && find ~/backups -name 'kazbeef-*.sql.gz' -mtime +14 -delete
```

(перед этим `mkdir -p ~/backups`; хранятся копии за 14 дней).

Контейнеры перезапускаются сами после сбоя и после перезагрузки сервера (`restart: unless-stopped`), если служба Docker включена: `sudo systemctl enable docker`.

---

## Если не работает

| Признак | Причина и что делать |
|---|---|
| `curl https://…sslip.io` не отвечает | закрыты порты 80/443 у провайдера или в `ufw`; `docker compose logs caddy` |
| Caddy пишет об ошибке сертификата | порт 80 должен быть открыт: Let's Encrypt проверяет сервер через него |
| В интерфейсе ошибка CORS (F12 → Console) | `KAZBEEF_CORS_ORIGINS` в `.env` должен точно совпадать с адресом интерфейса: `https://`, без `/` в конце; затем `docker compose up -d` |
| Вход не срабатывает, форма снова появляется | браузер блокирует cookie стороннего сайта (Safari, Firefox со строгой защитой). Проверьте в Chrome. Надёжное решение — свой домен для API и интерфейса (например `app.kazbeef.kz` и `api.kazbeef.kz`) или прокси через Worker |
| Чат пишет «Чат не настроен» | не задан `ANTHROPIC_API_KEY`; `curl https://…sslip.io/public/chat/status` должен показать `"enabled":true` |
| Ответ чата приходит одним куском, а не по мере генерации | между Caddy и браузером есть ещё прокси с буферизацией |

## Когда появится свой домен

1. DNS: запись `A` для `api.ваш-домен` → IP сервера.
2. В `.env`: `API_HOST=api.ваш-домен`, затем `docker compose up -d`.
3. В Cloudflare: `VITE_API_URL=https://api.ваш-домен`, пересобрать интерфейс.

Если интерфейс тоже переедет на этот домен (`app.ваш-домен`), cookie станут «своими» для браузера и проблема с Safari исчезнет. Не забудьте добавить новый адрес интерфейса в `KAZBEEF_CORS_ORIGINS`.

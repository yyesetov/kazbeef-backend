-- База знаний для публичного чата: документы (их Markdown-версия лежит файлом на диске),
-- поисковый индекс по фрагментам и журнал вопросов.

CREATE TABLE kb_document (
    id            BIGSERIAL PRIMARY KEY,
    original_name VARCHAR(512) NOT NULL,
    content_type  VARCHAR(64)  NOT NULL,
    original_size BIGINT       NOT NULL,
    title         VARCHAR(512),
    summary       TEXT,
    tags          JSONB        NOT NULL DEFAULT '[]'::jsonb,
    status        VARCHAR(16)  NOT NULL DEFAULT 'processing' CHECK (status IN ('processing', 'ready', 'failed')),
    error_text    TEXT,
    -- путь к Markdown-файлу относительно каталога базы знаний
    md_path       VARCHAR(255),
    md_size       BIGINT,
    chars         INTEGER,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Фрагмент документа для поиска. Пересобирается из Markdown-файла.
CREATE TABLE kb_chunk (
    id          BIGSERIAL PRIMARY KEY,
    document_id BIGINT  NOT NULL REFERENCES kb_document (id) ON DELETE CASCADE,
    position    INTEGER NOT NULL,
    -- путь заголовков: «Раздел › Подраздел»
    heading     TEXT,
    content     TEXT    NOT NULL,
    tsv         TSVECTOR GENERATED ALWAYS AS (
                    setweight(to_tsvector('russian', coalesce(heading, '')), 'A') ||
                    setweight(to_tsvector('russian', content), 'B')) STORED
);
CREATE INDEX ix_kb_chunk_tsv ON kb_chunk USING GIN (tsv);
CREATE INDEX ix_kb_chunk_document ON kb_chunk (document_id, position);

-- Вопросы посетителей без персональных данных: чего не хватает в базе знаний
CREATE TABLE kb_chat_log (
    id           BIGSERIAL PRIMARY KEY,
    asked_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    question     TEXT        NOT NULL,
    found        BOOLEAN     NOT NULL,
    document_ids JSONB       NOT NULL DEFAULT '[]'::jsonb
);
CREATE INDEX ix_kb_chat_log_asked ON kb_chat_log (asked_at DESC);

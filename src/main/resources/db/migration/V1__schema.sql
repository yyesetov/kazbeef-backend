-- Этап 1: базовая схема.
-- Перечисления хранятся как varchar + CHECK.

CREATE TABLE source (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(32)  NOT NULL UNIQUE,
    name            VARCHAR(255) NOT NULL,
    url             VARCHAR(512),
    trust_level     VARCHAR(16)  NOT NULL CHECK (trust_level IN ('confirmed', 'signal', 'analytics')),
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    last_success_at TIMESTAMPTZ
);

CREATE TABLE disease (
    id           BIGSERIAL PRIMARY KEY,
    name_ru      VARCHAR(255),
    name_en      VARCHAR(255) NOT NULL,
    code         VARCHAR(64) UNIQUE,
    synonyms     JSONB        NOT NULL DEFAULT '[]'::jsonb,
    needs_review BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_disease_name_en ON disease (lower(name_en));

CREATE TABLE animal_species (
    id       BIGSERIAL PRIMARY KEY,
    name_ru  VARCHAR(255) NOT NULL,
    name_en  VARCHAR(255) NOT NULL,
    synonyms JSONB        NOT NULL DEFAULT '[]'::jsonb
);
CREATE UNIQUE INDEX ux_animal_species_name_en ON animal_species (lower(name_en));

CREATE TABLE disease_species (
    disease_id BIGINT NOT NULL REFERENCES disease (id) ON DELETE CASCADE,
    species_id BIGINT NOT NULL REFERENCES animal_species (id) ON DELETE CASCADE,
    PRIMARY KEY (disease_id, species_id)
);

CREATE TABLE country (
    id          BIGSERIAL PRIMARY KEY,
    iso2        CHAR(2) UNIQUE,
    iso3        CHAR(3)      NOT NULL UNIQUE,
    name_ru     VARCHAR(255),
    name_en     VARCHAR(255) NOT NULL,
    is_priority BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE TABLE event (
    id               BIGSERIAL PRIMARY KEY,
    disease_id       BIGINT REFERENCES disease (id),
    country_id       BIGINT REFERENCES country (id),
    species_id       BIGINT REFERENCES animal_species (id),
    raw_disease_name VARCHAR(512),
    raw_country_name VARCHAR(255),
    raw_species      TEXT,
    region           TEXT,
    latitude         DOUBLE PRECISION,
    longitude        DOUBLE PRECISION,
    start_date       DATE,
    report_date      DATE,
    outbreaks        INTEGER,
    susceptible      INTEGER,
    cases            INTEGER,
    deaths           INTEGER,
    status           VARCHAR(16)  NOT NULL DEFAULT 'new' CHECK (status IN ('new', 'ongoing', 'closed')),
    trust_level      VARCHAR(16)  NOT NULL CHECK (trust_level IN ('confirmed', 'signal', 'analytics')),
    title            VARCHAR(1024),
    description      TEXT,
    translation      TEXT,
    ai_summary       TEXT,
    ai_generated     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_event_disease_country_start ON event (disease_id, country_id, start_date);
CREATE INDEX ix_event_report_date ON event (report_date);

CREATE TABLE event_source (
    id          BIGSERIAL PRIMARY KEY,
    event_id    BIGINT       NOT NULL REFERENCES event (id) ON DELETE CASCADE,
    source_id   BIGINT       NOT NULL REFERENCES source (id),
    external_id VARCHAR(255) NOT NULL,
    url         VARCHAR(1024),
    fetched_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    raw_payload JSONB,
    CONSTRAINT ux_event_source_external UNIQUE (source_id, external_id)
);
CREATE INDEX ix_event_source_event ON event_source (event_id);

CREATE TABLE collect_run (
    id            BIGSERIAL PRIMARY KEY,
    source_id     BIGINT      NOT NULL REFERENCES source (id),
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ,
    status        VARCHAR(16) NOT NULL CHECK (status IN ('RUNNING', 'SUCCESS', 'PARTIAL', 'FAILED')),
    fetched_count INTEGER     NOT NULL DEFAULT 0,
    new_count     INTEGER     NOT NULL DEFAULT 0,
    updated_count INTEGER     NOT NULL DEFAULT 0,
    failed_count  INTEGER     NOT NULL DEFAULT 0,
    error_text    TEXT
);
CREATE INDEX ix_collect_run_source_started ON collect_run (source_id, started_at DESC);

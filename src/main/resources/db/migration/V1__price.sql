-- Spotpriser per prisområde, slik de publiseres av hvakosterstrommen.no (uten mva).
-- Ett intervall er vanligvis en time, men kan være kortere (for eksempel et kvarter).
create table price (
    area          varchar(3)     not null check (area in ('NO1', 'NO2', 'NO3', 'NO4', 'NO5')),
    starts_at     timestamptz    not null,
    ends_at       timestamptz    not null,
    nok_per_kwh   numeric(10, 5) not null,
    eur_per_kwh   numeric(10, 5) not null,
    exchange_rate numeric(10, 4) not null,
    fetched_at    timestamptz    not null default now(),
    primary key (area, starts_at),
    check (ends_at > starts_at)
);

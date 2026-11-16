package no.ibrahimnur.stromsmart.price;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Lagring av spotpriser med vanlig SQL og JdbcClient. */
@Repository
public class PriceRepository {

    private final JdbcClient jdbc;

    public PriceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Lagrer prisene. Finnes intervallet fra før, oppdateres det, så samme dag kan hentes flere ganger. */
    @Transactional
    public void saveAll(List<Price> prices) {
        for (Price price : prices) {
            jdbc.sql("""
                    insert into price (area, starts_at, ends_at, nok_per_kwh, eur_per_kwh, exchange_rate)
                    values (:area, :start, :end, :nok, :eur, :rate)
                    on conflict (area, starts_at) do update
                    set ends_at = excluded.ends_at,
                        nok_per_kwh = excluded.nok_per_kwh,
                        eur_per_kwh = excluded.eur_per_kwh,
                        exchange_rate = excluded.exchange_rate,
                        fetched_at = now()
                    """)
                    .param("area", price.area().name())
                    .param("start", utc(price.start()))
                    .param("end", utc(price.end()))
                    .param("nok", price.nokPerKwh())
                    .param("eur", price.eurPerKwh())
                    .param("rate", price.exchangeRate())
                    .update();
        }
    }

    /** Alle intervaller som starter i [from, to), i tidsrekkefølge. */
    public List<Price> find(Area area, Instant from, Instant to) {
        return jdbc.sql("""
                        select area, starts_at, ends_at, nok_per_kwh, eur_per_kwh, exchange_rate
                        from price
                        where area = :area and starts_at >= :from and starts_at < :to
                        order by starts_at
                        """)
                .param("area", area.name())
                .param("from", utc(from))
                .param("to", utc(to))
                .query(PriceRepository::map)
                .list();
    }

    /** Finnes det minst en pris i [from, to)? Brukes for å se om en dag allerede er hentet. */
    public boolean exists(Area area, Instant from, Instant to) {
        return jdbc.sql("select exists (select 1 from price where area = :area and starts_at >= :from and starts_at < :to)")
                .param("area", area.name())
                .param("from", utc(from))
                .param("to", utc(to))
                .query(Boolean.class)
                .single();
    }

    /** Siste tidspunkt det finnes priser for, eller null hvis tabellen er tom for området. */
    public Instant latestEnd(Area area) {
        OffsetDateTime latest = jdbc.sql("select max(ends_at) from price where area = :area")
                .param("area", area.name())
                .query(OffsetDateTime.class)
                .optional()
                .orElse(null);
        return latest == null ? null : latest.toInstant();
    }

    private static Price map(ResultSet rs, int row) throws SQLException {
        return new Price(
                Area.valueOf(rs.getString("area")),
                rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
                rs.getObject("ends_at", OffsetDateTime.class).toInstant(),
                rs.getBigDecimal("nok_per_kwh"),
                rs.getBigDecimal("eur_per_kwh"),
                rs.getBigDecimal("exchange_rate"));
    }

    /** PostgreSQL-driveren tar imot OffsetDateTime for timestamptz. */
    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}

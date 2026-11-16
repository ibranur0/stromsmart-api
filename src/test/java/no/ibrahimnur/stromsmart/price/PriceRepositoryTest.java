package no.ibrahimnur.stromsmart.price;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import no.ibrahimnur.stromsmart.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;

/** Lagringen mot en ekte PostgreSQL i Docker, med tabellen fra Flyway-migreringen. */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, PriceRepository.class})
class PriceRepositoryTest {

    @Autowired
    private PriceRepository repository;

    /** Timespriser gjennom en hel dag i norsk tid, også når dagen har 23 eller 25 timer. */
    private static List<Price> day(Area area, LocalDate date, double nok) {
        List<Price> out = new ArrayList<>();
        for (Instant start = Area.startOf(date); start.isBefore(Area.startOf(date.plusDays(1))); start = start.plus(Duration.ofHours(1))) {
            out.add(new Price(area, start, start.plus(Duration.ofHours(1)), BigDecimal.valueOf(nok), new BigDecimal("0.1"), new BigDecimal("10.5")));
        }
        return out;
    }

    @Test
    void savesAndFindsADayInOrder() {
        LocalDate date = LocalDate.of(2026, 11, 15);
        repository.saveAll(day(Area.NO1, date, 1.5).reversed());

        var found = repository.find(Area.NO1, Area.startOf(date), Area.startOf(date.plusDays(1)));

        assertThat(found).hasSize(24);
        assertThat(found).extracting(Price::start).isSorted();
        assertThat(found.getFirst().nokPerKwh()).isEqualByComparingTo("1.5");
    }

    @Test
    void savingTheSameDayAgainUpdatesInsteadOfDuplicating() {
        LocalDate date = LocalDate.of(2026, 11, 15);
        repository.saveAll(day(Area.NO2, date, 1.0));
        repository.saveAll(day(Area.NO2, date, 2.0));

        var found = repository.find(Area.NO2, Area.startOf(date), Area.startOf(date.plusDays(1)));

        assertThat(found).hasSize(24).allSatisfy(price -> assertThat(price.nokPerKwh()).isEqualByComparingTo("2"));
    }

    @Test
    void daysWithDaylightSavingChangesHaveTheRightNumberOfHours() {
        LocalDate autumn = LocalDate.of(2026, 10, 25); // klokken stilles tilbake: 25 timer
        LocalDate spring = LocalDate.of(2026, 3, 29);  // klokken stilles frem: 23 timer
        repository.saveAll(day(Area.NO3, autumn, 1.0));
        repository.saveAll(day(Area.NO3, spring, 1.0));

        assertThat(repository.find(Area.NO3, Area.startOf(autumn), Area.startOf(autumn.plusDays(1)))).hasSize(25);
        assertThat(repository.find(Area.NO3, Area.startOf(spring), Area.startOf(spring.plusDays(1)))).hasSize(23);
    }

    @Test
    void areasAreKeptApart() {
        LocalDate date = LocalDate.of(2026, 11, 15);
        repository.saveAll(day(Area.NO4, date, 1.0));

        assertThat(repository.exists(Area.NO4, Area.startOf(date), Area.startOf(date.plusDays(1)))).isTrue();
        assertThat(repository.exists(Area.NO5, Area.startOf(date), Area.startOf(date.plusDays(1)))).isFalse();
        assertThat(repository.latestEnd(Area.NO4)).isEqualTo(Area.startOf(date.plusDays(1)));
        assertThat(repository.latestEnd(Area.NO5)).isNull();
    }
}

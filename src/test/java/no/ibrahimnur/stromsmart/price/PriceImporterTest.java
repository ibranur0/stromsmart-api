package no.ibrahimnur.stromsmart.price;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class PriceImporterTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 15);

    private final PriceSource source = mock(PriceSource.class);
    private final PriceRepository repository = mock(PriceRepository.class);
    private final PriceProperties properties = new PriceProperties(URI.create("https://priser.test"), "test", 2, Duration.ofMinutes(30), Duration.ZERO);

    private PriceImporter importerAt(int hour) {
        Clock clock = Clock.fixed(ZonedDateTime.of(TODAY.atTime(hour, 0), Area.ZONE).toInstant(), Area.ZONE);
        return new PriceImporter(source, repository, properties, clock);
    }

    private static List<Price> pricesFor(Area area, LocalDate day) {
        var start = Area.startOf(day);
        return List.of(new Price(area, start, start.plus(Duration.ofHours(1)), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.TEN));
    }

    @Test
    void fetchesHistoryAndTodayButNotTomorrowBeforeOnePm() {
        when(source.fetch(any(), any())).thenAnswer(call -> pricesFor(call.getArgument(0), call.getArgument(1)));

        int saved = importerAt(12).importMissing();

        // Tre dager (i forgårs, i går, i dag) for fem prisområder.
        assertThat(saved).isEqualTo(15);
        verify(source, never()).fetch(any(), eq(TODAY.plusDays(1)));
    }

    @Test
    void asksForTomorrowAfterOnePm() {
        when(source.fetch(any(), any())).thenReturn(List.of());

        importerAt(14).importMissing();

        verify(source, times(Area.values().length)).fetch(any(), eq(TODAY.plusDays(1)));
    }

    @Test
    void daysAlreadyStoredAreNotFetchedAgain() {
        when(repository.exists(any(), any(), any())).thenReturn(true);

        assertThat(importerAt(14).importMissing()).isZero();
        verify(source, never()).fetch(any(), any());
    }

    @Test
    void oneFailingAreaDoesNotStopTheOthers() {
        when(source.fetch(any(), any())).thenAnswer(call -> {
            if (call.getArgument(0) == Area.NO3) {
                throw new PriceSource.PriceSourceException("Pris-API-et svarte 500");
            }
            return pricesFor(call.getArgument(0), call.getArgument(1));
        });

        assertThat(importerAt(12).importMissing()).isEqualTo(12);
    }
}

package no.ibrahimnur.stromsmart.price;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import no.ibrahimnur.stromsmart.plan.Planner;
import org.springframework.stereotype.Service;

/** Priser slik API-et viser dem: med eller uten mva, i norsk tid og med nøkkeltall. */
@Service
public class PriceService {

    /** Kroner per kWh med fem desimaler, som i kilden. */
    private static final int SCALE = 5;

    private final PriceRepository repository;
    private final Clock clock;

    public PriceService(PriceRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** En pris i API-svaret. */
    public record PricePoint(OffsetDateTime start, OffsetDateTime end, BigDecimal price) {
    }

    /** Lavest, høyest og snitt. Snittet er vektet på intervallenes lengde. */
    public record Summary(BigDecimal min, BigDecimal max, BigDecimal mean) {
    }

    public record Prices(Area area, String areaName, boolean includesVat, String currency, String unit,
                         List<PricePoint> intervals, Summary summary) {
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(Area.ZONE));
    }

    /** Prisene for en dag i norsk tid. */
    public Prices day(Area area, LocalDate date, boolean includeVat) {
        List<Price> prices = repository.find(area, Area.startOf(date), Area.startOf(date.plusDays(1)));
        if (prices.isEmpty()) {
            throw new NoPricesException(date.isAfter(today())
                    ? "Prisene for %s er ikke publisert ennå. Morgendagens priser kommer rundt kl. 13.".formatted(date)
                    : "Det finnes ingen priser for %s i %s.".formatted(date, area));
        }
        return toResponse(area, prices, includeVat);
    }

    /** I dag, og i morgen hvis prisene er publisert. */
    public Prices current(Area area, boolean includeVat) {
        LocalDate today = today();
        List<Price> prices = repository.find(area, Area.startOf(today), Area.startOf(today.plusDays(2)));
        if (prices.isEmpty()) {
            throw new NoPricesException("Det finnes ingen priser for i dag i %s ennå.".formatted(area));
        }
        return toResponse(area, prices, includeVat);
    }

    /** Prisene fra intervallet som pågår nå og så langt vi har priser. Brukes til planleggingen. */
    public List<Planner.Interval> forPlanning(Area area, boolean includeVat) {
        Instant now = clock.instant();
        BigDecimal factor = area.vatFactor(includeVat);
        return repository.find(area, now.minus(Duration.ofHours(1)), Area.startOf(today().plusDays(2))).stream()
                .filter(price -> price.end().isAfter(now))
                .map(price -> new Planner.Interval(price.start(), price.end(), price.nokPerKwh().multiply(factor).doubleValue()))
                .toList();
    }

    public Instant now() {
        return clock.instant();
    }

    private Prices toResponse(Area area, List<Price> prices, boolean includeVat) {
        BigDecimal factor = area.vatFactor(includeVat);
        List<PricePoint> points = prices.stream()
                .map(price -> new PricePoint(
                        price.start().atZone(Area.ZONE).toOffsetDateTime(),
                        price.end().atZone(Area.ZONE).toOffsetDateTime(),
                        price.nokPerKwh().multiply(factor).setScale(SCALE, RoundingMode.HALF_UP)))
                .toList();
        return new Prices(area, area.displayName(), includeVat && !area.vatExempt(), "NOK", "kWh", points, summarize(points));
    }

    static Summary summarize(List<PricePoint> points) {
        BigDecimal min = points.stream().map(PricePoint::price).min(BigDecimal::compareTo).orElseThrow();
        BigDecimal max = points.stream().map(PricePoint::price).max(BigDecimal::compareTo).orElseThrow();
        BigDecimal weighted = BigDecimal.ZERO;
        long totalSeconds = 0;
        for (PricePoint point : points) {
            long seconds = Duration.between(point.start(), point.end()).toSeconds();
            weighted = weighted.add(point.price().multiply(BigDecimal.valueOf(seconds)));
            totalSeconds += seconds;
        }
        return new Summary(min, max, weighted.divide(BigDecimal.valueOf(totalSeconds), SCALE, RoundingMode.HALF_UP));
    }
}

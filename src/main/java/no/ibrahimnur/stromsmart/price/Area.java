package no.ibrahimnur.stromsmart.price;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** De fem prisområdene i Norge. */
public enum Area {
    NO1("Østlandet"),
    NO2("Sørlandet"),
    NO3("Midt-Norge"),
    NO4("Nord-Norge"),
    NO5("Vestlandet");

    /** Prisene følger norsk tid. Et døgn er et døgn i Oslo, også når klokken stilles. */
    public static final ZoneId ZONE = ZoneId.of("Europe/Oslo");

    /** Midnatt i Oslo ved starten av dagen. */
    public static Instant startOf(LocalDate day) {
        return day.atStartOfDay(ZONE).toInstant();
    }

    private static final BigDecimal VAT = new BigDecimal("1.25");

    private final String displayName;

    Area(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** Husholdninger i Nord-Norge betaler ikke merverdiavgift på strøm. */
    public boolean vatExempt() {
        return this == NO4;
    }

    /** Tallet prisen uten mva ganges med for å få det kunden betaler. */
    public BigDecimal vatFactor(boolean includeVat) {
        return includeVat && !vatExempt() ? VAT : BigDecimal.ONE;
    }
}

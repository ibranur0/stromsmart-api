package no.ibrahimnur.stromsmart.plan;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;
import java.util.TreeSet;

/**
 * Finner de billigste tidspunktene å bruke strøm. Ingen Spring og ingen database her.
 * Oversatt fra stromsmart.py i porteføljen, og testene sjekker at de to gir samme svar.
 *
 * Det er to ulike tilfeller:
 * Et apparat som må gå i ett strekk (oppvaskmaskin). Det billigste tidsrommet starter eller
 * slutter alltid på en intervallgrense, så det holder å prøve dem.
 * Forbruk som kan deles opp (elbil). Da fyller vi de billigste intervallene først.
 */
public final class Planner {

    /** Toleranse for avrunding når timer og kroner regnes med flyttall. */
    private static final double EPSILON = 1e-9;

    private Planner() {
    }

    /** Ett prisintervall. Prisen er i kroner per kWh. */
    public record Interval(Instant start, Instant end, double price) {
        double hours() {
            return hoursBetween(start, end);
        }
    }

    /** En sammenhengende periode med strømbruk. */
    public record Block(Instant start, Instant end) {
    }

    /** Hvordan forbruket kan fordeles. */
    public enum Mode {
        /** Kan deles opp i bolker, som lading av elbil. */
        FLEXIBLE,
        /** Må gå i ett strekk, som en oppvaskmaskin. */
        WINDOW
    }

    /**
     * Det som skal planlegges. FLEXIBLE trenger kw, og WINDOW trenger hours.
     * deadline kan være null.
     */
    public record Task(Mode mode, double kwh, Double kw, Double hours, Instant deadline) {
    }

    /** Planen, og hva det ville kostet å starte med en gang. */
    public record Plan(Mode mode, List<Block> blocks, double cost, double hours, double averagePrice,
                       Double baselineCost, Double saving) {
    }

    /** Resultatet av ett av de to søkene, før det sammenlignes med å starte med en gang. */
    record Schedule(List<Block> blocks, double cost, double hours) {
    }

    /** Lager en plan for en oppgave og sammenligner med å starte med en gang. */
    public static Plan plan(List<Interval> intervals, Instant now, Task task) {
        List<Interval> available = clip(intervals, now, task.deadline());
        if (available.isEmpty()) {
            throw new PlanningException("Det finnes ingen priser for det valgte tidsrommet.");
        }
        Schedule schedule;
        double kw;
        if (task.mode() == Mode.FLEXIBLE) {
            kw = task.kw();
            schedule = cheapestSlots(available, task.kwh(), kw);
        } else {
            schedule = cheapestWindow(available, task.hours(), task.kwh());
            kw = task.kwh() / schedule.hours();
        }
        OptionalDouble baseline = costOfRun(available, available.getFirst().start(), schedule.hours(), kw);
        Double baselineCost = baseline.isPresent() ? baseline.getAsDouble() : null;
        return new Plan(task.mode(), schedule.blocks(), schedule.cost(), schedule.hours(),
                schedule.cost() / task.kwh(), baselineCost,
                baselineCost == null ? null : baselineCost - schedule.cost());
    }

    /** Beholder bare den delen av hvert intervall som ligger mellom earliest og deadline. */
    static List<Interval> clip(List<Interval> intervals, Instant earliest, Instant deadline) {
        List<Interval> out = new ArrayList<>();
        for (Interval interval : sorted(intervals)) {
            Instant start = later(interval.start(), earliest);
            Instant end = deadline == null ? interval.end() : earlier(interval.end(), deadline);
            if (end.isAfter(start)) {
                out.add(new Interval(start, end, interval.price()));
            }
        }
        return out;
    }

    /**
     * Kostnaden ved å trekke kw sammenhengende i hours timer fra start.
     * Tom hvis prisene ikke dekker hele perioden.
     */
    static OptionalDouble costOfRun(List<Interval> intervals, Instant start, double hours, double kw) {
        Instant end = start.plus(duration(hours));
        double covered = 0;
        double cost = 0;
        for (Interval interval : intervals) {
            double overlap = hoursBetween(later(interval.start(), start), earlier(interval.end(), end));
            if (overlap > 0) {
                covered += overlap;
                cost += overlap * kw * interval.price();
            }
        }
        return covered >= hours - EPSILON ? OptionalDouble.of(cost) : OptionalDouble.empty();
    }

    /** Billigste sammenhengende tidsrom, for apparater som ikke kan stoppes underveis. */
    static Schedule cheapestWindow(List<Interval> intervals, double hours, double kwh) {
        if (hours <= 0 || kwh <= 0) {
            throw new PlanningException("Varighet og energi må være større enn null.");
        }
        double kw = kwh / hours;
        TreeSet<Instant> candidates = new TreeSet<>();
        for (Interval interval : intervals) {
            candidates.add(interval.start());
            candidates.add(interval.end().minus(duration(hours)));
        }
        Instant first = intervals.isEmpty() ? null : intervals.getFirst().start();
        Instant bestStart = null;
        double bestCost = Double.POSITIVE_INFINITY;
        for (Instant start : candidates) {
            if (first == null || start.isBefore(first)) {
                continue;
            }
            OptionalDouble cost = costOfRun(intervals, start, hours, kw);
            // Samme pris: det tidligste vinduet vinner, slik som i Python-versjonen.
            if (cost.isPresent() && cost.getAsDouble() < bestCost - 1e-12) {
                bestStart = start;
                bestCost = cost.getAsDouble();
            }
        }
        if (bestStart == null) {
            throw new PlanningException("Det er ikke nok tid igjen til å bli ferdig innen fristen.");
        }
        return new Schedule(List.of(new Block(bestStart, bestStart.plus(duration(hours)))), bestCost, hours);
    }

    /** Billigste oppdelte lading, for forbruk som kan settes på pause (elbil, varmtvann). */
    static Schedule cheapestSlots(List<Interval> intervals, double kwh, double kw) {
        if (kwh <= 0 || kw <= 0) {
            throw new PlanningException("Energi og effekt må være større enn null.");
        }
        double needed = kwh / kw;
        double available = intervals.stream().mapToDouble(Interval::hours).sum();
        if (available < needed - EPSILON) {
            throw new PlanningException("Det er ikke nok tid igjen til å bli ferdig innen fristen.");
        }
        List<Interval> chosen = new ArrayList<>();
        double cost = 0;
        double remaining = needed;
        List<Interval> cheapestFirst = intervals.stream()
                .sorted(Comparator.comparingDouble(Interval::price).thenComparing(Interval::start))
                .toList();
        for (Interval interval : cheapestFirst) {
            if (remaining <= EPSILON) {
                break;
            }
            double used = Math.min(interval.hours(), remaining);
            boolean partial = used < interval.hours();
            if (partial && chosen.stream().anyMatch(c -> c.start().equals(interval.end()))) {
                // Et halvt brukt intervall legges inntil neste bolk, så ladingen ikke starter og stopper unødig.
                chosen.add(new Interval(interval.end().minus(duration(used)), interval.end(), interval.price()));
            } else {
                chosen.add(new Interval(interval.start(), interval.start().plus(duration(used)), interval.price()));
            }
            cost += used * kw * interval.price();
            remaining -= used;
        }
        return new Schedule(mergeBlocks(chosen), cost, needed);
    }

    /** Slår sammen tilstøtende intervaller til sammenhengende bolker. */
    static List<Block> mergeBlocks(List<Interval> chosen) {
        List<Block> blocks = new ArrayList<>();
        for (Interval interval : sorted(chosen)) {
            Block last = blocks.isEmpty() ? null : blocks.getLast();
            if (last != null && !interval.start().isAfter(last.end())) {
                blocks.set(blocks.size() - 1, new Block(last.start(), later(last.end(), interval.end())));
            } else {
                blocks.add(new Block(interval.start(), interval.end()));
            }
        }
        return blocks;
    }

    private static List<Interval> sorted(List<Interval> intervals) {
        return intervals.stream().sorted(Comparator.comparing(Interval::start)).toList();
    }

    static Duration duration(double hours) {
        return Duration.ofNanos(Math.round(hours * 3_600e9));
    }

    static double hoursBetween(Instant from, Instant to) {
        return Duration.between(from, to).toNanos() / 3_600e9;
    }

    private static Instant later(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    private static Instant earlier(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}

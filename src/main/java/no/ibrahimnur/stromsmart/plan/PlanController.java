package no.ibrahimnur.stromsmart.plan;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.OffsetDateTime;
import java.util.List;
import no.ibrahimnur.stromsmart.plan.Planner.Mode;
import no.ibrahimnur.stromsmart.price.Area;
import no.ibrahimnur.stromsmart.price.PriceService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Planlegging", description = "Når er det billigst å bruke strøm?")
public class PlanController {

    private final PriceService prices;

    public PlanController(PriceService prices) {
        this.prices = prices;
    }

    @Schema(description = "Hva som skal planlegges. FLEXIBLE krever kw, WINDOW krever hours.")
    public record PlanRequest(
            @NotNull(message = "Prisområde mangler") Area area,
            @Schema(defaultValue = "true") Boolean includeVat,
            @NotNull(message = "Velg FLEXIBLE (kan deles opp) eller WINDOW (ett strekk)") Mode mode,
            @Positive(message = "Forbruket må være større enn null") @DecimalMax(value = "1000", message = "Forbruket kan være høyst 1000 kWh") double kwh,
            @Positive(message = "Effekten må være større enn null") Double kw,
            @Positive(message = "Varigheten må være større enn null") @DecimalMax(value = "48", message = "Varigheten kan være høyst 48 timer") Double hours,
            @Schema(example = "2026-11-16T07:00:00+01:00") OffsetDateTime deadline) {

        @AssertTrue(message = "FLEXIBLE krever effekt (kw), og WINDOW krever varighet (hours)")
        @Schema(hidden = true)
        public boolean isComplete() {
            return mode == null || (mode == Mode.FLEXIBLE ? kw != null : hours != null);
        }
    }

    public record BlockResponse(OffsetDateTime start, OffsetDateTime end) {
    }

    public record PlanResponse(Area area, Mode mode, List<BlockResponse> blocks, double hours,
                               @Schema(description = "Kroner") double cost,
                               @Schema(description = "Kroner per kWh") double averagePrice,
                               @Schema(description = "Hva det ville kostet å starte nå, i kroner") Double baselineCost,
                               @Schema(description = "Kroner spart på å vente") Double saving) {
    }

    @PostMapping("/plans")
    @Operation(summary = "Billigste tidspunkt for et forbruk, innenfor en eventuell frist")
    public PlanResponse plan(@Valid @RequestBody PlanRequest request) {
        if (request.deadline() != null && !request.deadline().toInstant().isAfter(prices.now())) {
            throw new PlanningException("Fristen må være frem i tid.");
        }
        boolean includeVat = request.includeVat() == null || request.includeVat();
        var task = new Planner.Task(request.mode(), request.kwh(), request.kw(), request.hours(),
                request.deadline() == null ? null : request.deadline().toInstant());
        var plan = Planner.plan(prices.forPlanning(request.area(), includeVat), prices.now(), task);
        var blocks = plan.blocks().stream()
                .map(block -> new BlockResponse(block.start().atZone(Area.ZONE).toOffsetDateTime(), block.end().atZone(Area.ZONE).toOffsetDateTime()))
                .toList();
        return new PlanResponse(request.area(), plan.mode(), blocks, round(plan.hours()), round(plan.cost()),
                round(plan.averagePrice()), round(plan.baselineCost()), round(plan.saving()));
    }

    /** Fire desimaler holder for kroner og timer, og gjør svaret lett å lese. */
    private static Double round(Double value) {
        return value == null ? null : Math.round(value * 10_000) / 10_000.0;
    }
}

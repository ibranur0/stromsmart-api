package no.ibrahimnur.stromsmart.price;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Priser", description = "Spotpriser per prisområde, i kroner per kWh")
public class PriceController {

    private final PriceService service;

    public PriceController(PriceService service) {
        this.service = service;
    }

    public record AreaInfo(Area code, String name, boolean vatExempt) {
    }

    @GetMapping("/areas")
    @Operation(summary = "Prisområdene")
    public ResponseEntity<List<AreaInfo>> areas() {
        List<AreaInfo> areas = Arrays.stream(Area.values())
                .map(area -> new AreaInfo(area, area.displayName(), area.vatExempt()))
                .toList();
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofDays(1))).body(areas);
    }

    @GetMapping("/prices/{area}")
    @Operation(summary = "Prisene i dag, og i morgen når de er publisert (rundt kl. 13)")
    public ResponseEntity<PriceService.Prices> current(
            @PathVariable Area area,
            @Parameter(description = "Ta med 25 % mva (gjelder ikke NO4)") @RequestParam(defaultValue = "true") boolean vat) {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(5))).body(service.current(area, vat));
    }

    @GetMapping("/prices/{area}/{date}")
    @Operation(summary = "Prisene for en dag")
    public ResponseEntity<PriceService.Prices> day(
            @PathVariable Area area,
            @Parameter(example = "2026-11-15") @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Parameter(description = "Ta med 25 % mva (gjelder ikke NO4)") @RequestParam(defaultValue = "true") boolean vat) {
        // Tidligere dager endrer seg ikke, og kan ligge lenge i hurtigbufferen.
        Duration maxAge = date.isBefore(service.today()) ? Duration.ofDays(1) : Duration.ofMinutes(5);
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(maxAge)).body(service.day(area, date, vat));
    }
}

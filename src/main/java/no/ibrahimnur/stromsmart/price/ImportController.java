package no.ibrahimnur.stromsmart.price;

import io.swagger.v3.oas.annotations.Hidden;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Starter innhentingen utenfra. I skyen sover appen når ingen bruker den, så en tidsplan
 * inne i appen kjører ikke. Der kaller Cloud Scheduler dette endepunktet hver halvtime.
 * Uten stromsmart.import-token finnes ikke endepunktet (404).
 */
@Hidden
@RestController
class ImportController {

    record Result(int savedDays) {
    }

    private final PriceImporter importer;
    private final byte[] token;

    ImportController(PriceImporter importer, @Value("${stromsmart.import-token:}") String token) {
        this.importer = importer;
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/internal/import")
    ResponseEntity<Result> run(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        if (token.length == 0) {
            return ResponseEntity.notFound().build();
        }
        if (!authorized(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(new Result(importer.importMissing()));
    }

    private boolean authorized(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return false;
        }
        byte[] given = authorization.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8);
        // Sammenligner i konstant tid, så svartiden ikke avslører hvor mye av nøkkelen som stemmer.
        return MessageDigest.isEqual(given, token);
    }
}

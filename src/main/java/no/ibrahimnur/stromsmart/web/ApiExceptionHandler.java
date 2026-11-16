package no.ibrahimnur.stromsmart.web;

import java.util.LinkedHashMap;
import java.util.Map;
import no.ibrahimnur.stromsmart.plan.PlanningException;
import no.ibrahimnur.stromsmart.price.NoPricesException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Alle feil gis som Problem Details (RFC 9457), med en norsk forklaring i detail
 * som kan vises rett til brukeren.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(NoPricesException.class)
    ProblemDetail noPrices(NoPricesException e) {
        return problem(HttpStatus.NOT_FOUND, "Ingen priser", e.getMessage());
    }

    @ExceptionHandler(PlanningException.class)
    ProblemDetail planning(PlanningException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Kan ikke planlegge", e.getMessage());
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException e, HttpHeaders headers,
                                                        HttpStatusCode status, WebRequest request) {
        String name = e instanceof MethodArgumentTypeMismatchException mismatch ? mismatch.getName() : e.getPropertyName();
        String detail = "area".equals(name)
                ? "Ukjent prisområde '%s'. Gyldige er NO1, NO2, NO3, NO4 og NO5.".formatted(e.getValue())
                : "Ugyldig verdi for %s: '%s'.".formatted(name, e.getValue());
        return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, "Ugyldig forespørsel", detail));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError error : e.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        e.getBindingResult().getGlobalErrors().forEach(error -> errors.putIfAbsent(error.getObjectName(), error.getDefaultMessage()));
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "Ugyldig forespørsel", String.join(" ", errors.values()));
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException e, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, "Ugyldig forespørsel",
                "Forespørselen er ikke gyldig JSON, eller et felt har feil type."));
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}

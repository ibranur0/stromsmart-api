package no.ibrahimnur.stromsmart.plan;

/** Kan ikke planlegges, for eksempel fordi fristen er for kort. Meldingen vises til brukeren. */
public class PlanningException extends RuntimeException {

    public PlanningException(String message) {
        super(message);
    }
}

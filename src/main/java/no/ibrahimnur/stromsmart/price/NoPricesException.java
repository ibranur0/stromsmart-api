package no.ibrahimnur.stromsmart.price;

/** Det finnes ingen priser for det som ble spurt om. Meldingen vises til brukeren. */
public class NoPricesException extends RuntimeException {

    public NoPricesException(String message) {
        super(message);
    }
}

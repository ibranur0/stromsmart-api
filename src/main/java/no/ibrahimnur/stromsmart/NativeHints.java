package no.ibrahimnur.stromsmart;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

/**
 * Ting native image må vite på forhånd. Flyway slår opp feilklassene sine med refleksjon
 * når en SQL-feil skjer. Uten dette ble en vanlig innloggingsfeil til en NoSuchMethodException
 * som skjulte den egentlige feilen.
 */
class NativeHints implements RuntimeHintsRegistrar {

    private static final String FLYWAY_SQL_EXCEPTIONS = "org.flywaydb.core.internal.exception.sqlExceptions.";

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        for (String name : new String[] {
                "FlywaySqlUnableToConnectToDbException",
                "FlywaySqlNoIntegratedAuthException",
                "FlywaySqlServerUntrustedCertificateSqlException",
                "FlywaySqlNoDriversForInteractiveAuthException"}) {
            hints.reflection().registerType(TypeReference.of(FLYWAY_SQL_EXCEPTIONS + name),
                    MemberCategory.INVOKE_DECLARED_METHODS, MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
        }
    }
}

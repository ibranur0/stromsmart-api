# Bygg: GraalVM lager en kjørbar fil av appen. Avhengighetene ligger i et eget lag,
# så de bare lastes ned på nytt når pom.xml endres.
FROM ghcr.io/graalvm/native-image-community:25 AS build
WORKDIR /app
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -B -Pnative dependency:go-offline
COPY src/ src/
RUN ./mvnw -q -B -Pnative -DskipTests native:compile

# Kjør: bare filen og glibc, som en vanlig bruker. Ingen JVM, skall eller pakkebehandler.
FROM gcr.io/distroless/base-debian12:nonroot
COPY --from=build /app/target/stromsmart-api /stromsmart-api
EXPOSE 8080
ENTRYPOINT ["/stromsmart-api"]

FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn package -q -DskipTests

FROM eclipse-temurin:21-jre-alpine
# CVE-2026-91403: usuniecie rootow emSign (eMudhra), pod ktorymi wystawiono skompromitowane CA posrednie.
# Dopasowanie po CN, bo nazwy aliasow roznia sie miedzy dystrybucjami JDK. Temurin 21.0.13+ ma juz te rooty usuniete - wtedy krok nic nie robi; mozna go skasowac, gdy tag 21-jre-alpine bedzie >= 21.0.13.
RUN keytool -list -v -cacerts -storepass changeit \
      | awk '/^Alias name:/{a=substr($0,13)} /^Owner:.*CN=emSign (ECC Root CA - G3|ECC Root CA - C3|Root CA - C1),/{print a}' \
      | while IFS= read -r a; do keytool -delete -noprompt -cacerts -storepass changeit -alias "$a"; done \
    && ! keytool -list -v -cacerts -storepass changeit | grep -qE '^Owner:.*CN=emSign (ECC Root CA - G3|ECC Root CA - C3|Root CA - C1),'
WORKDIR /app
COPY --from=build /build/target/MediaEkspertGenerator-1.0.jar app.jar
COPY ean.txt .
COPY cny.txt .
RUN mkdir -p /app/data
EXPOSE 8089
ENV PORT=8089 \
    EAN_FILE=/app/ean.txt \
    CNY_FILE=/app/cny.txt \
    OUTPUT_FILE=/app/data/output.xml
CMD ["java", "-Xmx2g", "-jar", "app.jar"]
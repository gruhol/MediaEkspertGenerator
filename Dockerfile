FROM maven:3.9-eclipse-temurin-17-alpine AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn package -q -DskipTests

FROM eclipse-temurin:17-jre-alpine
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
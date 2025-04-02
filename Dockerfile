FROM maven:3.9.9-amazoncorretto-21 AS builder

WORKDIR /app
COPY . .
RUN mvn clean package

FROM quay.io/keycloak/keycloak:26.1.2
COPY --from=builder /app/target/*.jar /opt/keycloak/providers/

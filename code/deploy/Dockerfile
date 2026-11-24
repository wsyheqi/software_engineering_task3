FROM node:22-alpine AS web
WORKDIR /web
COPY frontend/package*.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY backend/pom.xml backend/pom.xml
RUN mvn -f backend/pom.xml dependency:go-offline -q
COPY backend/src/ backend/src/
COPY --from=web /web/dist/ frontend/dist/
RUN mvn -f backend/pom.xml package -DskipTests -q

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN groupadd -g 10001 campus && useradd -u 10001 -g campus -d /app campus && mkdir /app/data && chown -R campus:campus /app
COPY --from=build --chown=campus:campus /src/backend/target/hospitality-1.1.0.jar /app/app.jar
USER campus
EXPOSE 8080
ENTRYPOINT ["java","-Dfile.encoding=UTF-8","-jar","/app/app.jar","--debug=false"]

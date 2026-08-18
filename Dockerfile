# Step 1: Build stage
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app
COPY . .

# Check if pom.xml is in subfolder or root, then build
RUN if [ -f "./pom.xml" ]; then mvn clean package -DskipTests; else cd speech && mvn clean package -DskipTests && cp -r target /app/; fi

# Step 2: Runtime stage
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
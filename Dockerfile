# The manager with all its prerequisites: builds every module from source with Maven + JDK 21, so
# the host needs only Docker. Two targets:
#   docker build -t scanning-manager .                                   -> manager image (default)
#   docker build --target releases --output type=local,dest=releases .   -> node-1.0.0.jar, node-1.1.0.jar
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# Poms first so dependency downloads are cached until a pom changes.
COPY pom.xml .
COPY common/pom.xml common/
COPY manager/pom.xml manager/
COPY node/pom.xml node/
RUN mvn -q -B dependency:go-offline || true
COPY . .
# Both node releases from the same source: 1.0.0 is installed, 1.1.0 is the upgrade target.
RUN mvn -q -B -DskipTests package \
    && mvn -q -B -DskipTests -pl node -am package -Dnode.version=1.1.0 \
    && mkdir /releases && cp node/target/node-1.0.0.jar node/target/node-1.1.0.jar /releases/

FROM scratch AS releases
COPY --from=build /releases/ /

FROM eclipse-temurin:21-jre AS manager
WORKDIR /app
COPY --from=build /src/manager/target/manager-1.0.0.jar manager.jar
# Served at /releases/ and offered as app upgrades on the dashboard.
COPY --from=build /releases/ releases/
ENV SCANNING_DATA_DIR=/app/data
EXPOSE 8443
CMD ["java", "-jar", "manager.jar"]

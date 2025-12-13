# Build stage for Scala application
FROM eclipse-temurin:17-jdk-jammy AS builder

# Install sbt
RUN apt-get update && \
    apt-get install -y curl gnupg && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | tee /etc/apt/sources.list.d/sbt.list && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian /" | tee /etc/apt/sources.list.d/sbt_old.list && \
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | apt-key add && \
    apt-get update && \
    apt-get install -y sbt && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /build

# Copy build files
COPY build.sbt .
COPY project project

# Download dependencies (cached layer)
RUN sbt update

# Copy source code
COPY src src

# Build the application
RUN sbt assembly

# Runtime stage - build on top of gizmodata/gizmosql from Docker Hub
FROM gizmodata/gizmosql:latest

# Switch to root to install additional packages
USER root

# Update package lists and install lsof and Java
RUN apt-get update && \
    apt-get install -y lsof openjdk-21-jre-headless && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# Verify Java installation
RUN java -version

# Create directory for the process manager
ARG GIZMO_MANAGER_DIR=/opt/gizmo/manager
RUN mkdir -p ${GIZMO_MANAGER_DIR} && \
    chown app_user:app_user ${GIZMO_MANAGER_DIR}

# Switch back to app_user
USER app_user

WORKDIR ${GIZMO_MANAGER_DIR}

# Copy the built JAR from builder stage
COPY --from=builder --chown=app_user:app_user /build/target/scala-3.7.4/gizmo-on-demand-assembly-0.1.0-SNAPSHOT.jar ./gizmo-manager.jar

# Environment variables with defaults
ENV SL_GIZMO_HOST=0.0.0.0
ENV SL_GIZMO_PORT=10900
ENV SL_GIZMO_MIN_PORT=11900
ENV SL_GIZMO_MAX_PORT=12000
ENV SL_GIZMO_MAX_PROCESSES=10
ENV SL_GIZMO_DEFAULT_SCRIPT=/opt/gizmo/scripts/start_gizmosql.sh

# Expose the process manager port
EXPOSE 10900

# Expose the range of ports for managed processes
EXPOSE 11900-12000

# Start the process manager
ENTRYPOINT ["java", "-jar", "gizmo-manager.jar"]


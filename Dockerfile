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
ARG GIZMO_VERSION=v1.13.4
FROM gizmodata/gizmosql:${GIZMO_VERSION}

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

ARG GIZMO_SCRIPTS_DIR=/opt/gizmo/scripts
RUN mkdir -p ${GIZMO_SCRIPTS_DIR} && \
    chown app_user:app_user ${GIZMO_SCRIPTS_DIR}

# Switch back to app_user
USER app_user

WORKDIR ${GIZMO_MANAGER_DIR}

# Copy the built JAR from builder stage
COPY --from=builder --chown=app_user:app_user /build/distrib/gizmo-on-demand-assembly-0.1.0-SNAPSHOT.jar ./gizmo-on-demand.jar

# Copy proxy startup script
COPY --chown=app_user:app_user start-proxy.sh ${GIZMO_SCRIPTS_DIR}/start-proxy.sh
RUN chmod +x ${GIZMO_SCRIPTS_DIR}/start-proxy.sh

# Environment variables with defaults
ENV SL_GIZMO_ON_DEMAND_HOST=0.0.0.0
ENV SL_GIZMO_ON_DEMAND_PORT=10900
ENV SL_GIZMO_MIN_PORT=11900
ENV SL_GIZMO_MAX_PORT=12000
ENV SL_GIZMO_MAX_PROCESSES=10
ENV SL_GIZMO_DEFAULT_SCRIPT=/opt/gizmo/scripts/start_gizmosql.sh
# SL_GIZMO_API_KEY should be set at runtime for security

# Expose the process manager port
EXPOSE 10900

# Expose the range of ports for managed processes
EXPOSE 11900-12000

# Start the process manager
ENTRYPOINT ["java", "-jar", "gizmo-on-demand.jar"]


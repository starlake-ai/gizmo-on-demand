.PHONY: help build run test clean docker-build docker-run docker-push-snapshot docker-push-release

# Default target
help:
	@echo "Gizmo Process Manager - Available targets:"
	@echo ""
	@echo "  Development:"
	@echo "    build                - Compile the Scala application"
	@echo "    run                  - Run the application locally"
	@echo "    test                 - Run tests"
	@echo "    clean                - Clean build artifacts"
	@echo ""
	@echo "  Docker:"
	@echo "    docker-build         - Build Docker image locally"
	@echo "    docker-run           - Run Docker container locally"
	@echo "    docker-stop          - Stop running Docker container"
	@echo "    docker-logs          - View Docker container logs"
	@echo ""
	@echo "  Publishing:"
	@echo "    docker-push-snapshot - Publish snapshot to Docker Hub"
	@echo "    docker-push-release  - Publish release to Docker Hub"
	@echo ""
	@echo "  Environment Variables:"
	@echo "    SL_GIZMO_API_KEY     - API key for authentication"
	@echo "    SL_GIZMO_PORT        - Server port (default: 10900)"
	@echo ""

# Development targets
build:
	sbt compile

run:
	sbt run

test:
	./test-api.sh

clean:
	sbt clean
	rm -rf target/
	rm -rf project/target/

# Docker targets
docker-build:
	docker build -t gizmo-on-demand:local .

docker-run:
	docker run -d \
		--name gizmo-on-demand \
		-p 10900:10900 \
		-p 11900-12000:11900-12000 \
		-e SL_GIZMO_API_KEY=${SL_GIZMO_API_KEY} \
		gizmo-on-demand:local

docker-stop:
	docker stop gizmo-on-demand || true
	docker rm gizmo-on-demand || true

docker-logs:
	docker logs -f gizmo-on-demand

# Publishing targets
docker-push-snapshot:
	./publish-docker.sh snapshot

docker-push-release:
	./publish-docker.sh release

# Combined targets
docker-rebuild: docker-stop docker-build docker-run
	@echo "Docker container rebuilt and running"

docker-restart: docker-stop docker-run
	@echo "Docker container restarted"


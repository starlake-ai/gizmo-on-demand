#!/bin/bash

# Script to publish Docker image to Docker Hub
# Usage:
#   ./publish-docker.sh snapshot    # Publish snapshot version
#   ./publish-docker.sh release     # Publish release version

set -e

# Configuration
DOCKER_REPO="starlakeai/gizmo-on-demand"
VERSION_FILE="version.txt"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Function to print colored messages
print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check if Docker is installed
if ! command -v docker &> /dev/null; then
    print_error "Docker is not installed. Please install Docker first."
    exit 1
fi

# Check if logged in to Docker Hub
if ! docker info | grep -q "Username"; then
    print_warn "Not logged in to Docker Hub. Please run: docker login"
    exit 1
fi

# Check if buildx is available
if ! docker buildx version &> /dev/null; then
    print_error "Docker buildx is not available. Please update Docker to a version that supports buildx."
    exit 1
fi

# Create or use existing buildx builder for multi-platform builds
BUILDER_NAME="gizmo-multiplatform-builder"
if ! docker buildx inspect "$BUILDER_NAME" &> /dev/null; then
    print_info "Creating multi-platform builder: $BUILDER_NAME"
    docker buildx create --name "$BUILDER_NAME" --use
else
    print_info "Using existing builder: $BUILDER_NAME"
    docker buildx use "$BUILDER_NAME"
fi

# Bootstrap the builder
docker buildx inspect --bootstrap

# GizmoSQL Base Image Version
GIZMO_VERSION="${GIZMO_VERSION:-v1.13.4}"
print_info "Using GizmoSQL base version: ${GIZMO_VERSION}"


# Get version from file or use default
if [ -f "$VERSION_FILE" ]; then
    BASE_VERSION=$(cat "$VERSION_FILE" | tr -d '[:space:]')
else
    print_warn "Version file not found. Using default version 0.1.0"
    BASE_VERSION="0.1.0"
fi

# Determine publish type
PUBLISH_TYPE=${1:-snapshot}

if [ "$PUBLISH_TYPE" = "snapshot" ]; then
    # Snapshot version includes timestamp
    TIMESTAMP=$(date +%Y%m%d-%H%M%S)
    VERSION="${BASE_VERSION}-SNAPSHOT-${TIMESTAMP}"
    TAGS=("${VERSION}" "snapshot" "latest-snapshot")
    print_info "Publishing SNAPSHOT version: ${VERSION}"
elif [ "$PUBLISH_TYPE" = "release" ]; then
    # Release version
    VERSION="${BASE_VERSION}"
    TAGS=("${VERSION}" "latest")
    print_info "Publishing RELEASE version: ${VERSION}"
else
    print_error "Invalid publish type: ${PUBLISH_TYPE}"
    print_error "Usage: $0 [snapshot|release]"
    exit 1
fi

# Build tag list for buildx
TAG_ARGS=""
for TAG in "${TAGS[@]}"; do
    TAG_ARGS="${TAG_ARGS} -t ${DOCKER_REPO}:${TAG}"
done

# Build and push multi-platform Docker image
print_info "Building and pushing multi-platform Docker image..."
print_info "Platforms: linux/amd64, linux/arm64"
print_info "Tags: ${TAGS[*]}"

docker buildx build \
    --platform linux/amd64,linux/arm64 \
    --build-arg GIZMO_VERSION="${GIZMO_VERSION}" \
    ${TAG_ARGS} \
    --push \
    .

if [ $? -ne 0 ]; then
    print_error "Docker build and push failed"
    exit 1
fi

print_info "Multi-platform build and push successful"

print_info "Successfully published ${DOCKER_REPO}:${VERSION}"
print_info "Available tags:"
for TAG in "${TAGS[@]}"; do
    echo "  - ${DOCKER_REPO}:${TAG}"
done

# If this is a release, ask if we should update the version file
if [ "$PUBLISH_TYPE" = "release" ]; then
    echo ""
    print_info "Release published successfully!"
    print_warn "Don't forget to:"
    echo "  1. Tag the git commit: git tag v${VERSION}"
    echo "  2. Push the tag: git push origin v${VERSION}"
    echo "  3. Update version.txt for next development cycle"
fi


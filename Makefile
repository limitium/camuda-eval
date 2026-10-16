# Makefile for Camunda Evaluator with Jib Containerization

.PHONY: help build-backend build-frontend build-all clean run stop logs status check-prereqs dev stop-dev

# Environment Variables:
# BASE_URL - Base path after domain (e.g., myapp) so app is at http://domain/myapp/

# Default target
help:
	@echo "Available targets:"
	@echo "  build-frontend    - Build frontend static assets"
	@echo "  build-backend     - Build backend container with Jib"
	@echo "  build-all         - Build complete application (frontend + backend container)"
	@echo "  run               - Run the containerized application"
	@echo "  stop              - Stop the running container"
	@echo "  logs              - Show container logs"
	@echo "  status            - Show container status"
	@echo "  clean             - Clean up build artifacts and containers"
	@echo "  dev               - Start development environment"
	@echo "  stop-dev          - Stop development environment"
	@echo ""
	@echo "Environment Variables:"
	@echo "  BASE_URL          - Base path after domain (e.g., myapp)"
	@echo ""
	@echo "Examples:"
	@echo "  make run BASE_URL=myapp"
	@echo "  make build-all BASE_URL=myapp"

# Check prerequisites
check-prereqs:
	@echo "Checking prerequisites..."
	@command -v node >/dev/null 2>&1 || (echo "Node.js is not installed. Please install Node.js to build the frontend." && exit 1)
	@command -v npm >/dev/null 2>&1 || (echo "npm is not installed. Please install npm to build the frontend." && exit 1)
	@command -v java >/dev/null 2>&1 || (echo "Java is not installed. Please install Java to build the backend." && exit 1)
	@test -x ./gradlew || (echo "Gradle wrapper not found. Please ensure gradlew is executable." && exit 1)
	@echo "All prerequisites are available"

# Build targets
build-frontend: check-prereqs
	@echo "Building frontend static assets..."
	@mkdir -p backend/src/main/resources/static
	cd frontend && \
	if [ ! -d "node_modules" ]; then \
		echo "Installing frontend dependencies..."; \
		npm install; \
	fi && \
	echo "Building frontend assets..." && \
	npm run build && \
	echo "Frontend built successfully"

build-backend: check-prereqs
	@echo "Building backend container with Jib..."
	cd backend && \
	echo "Building backend application..." && \
	../gradlew build && \
	echo "Building container image with Jib..." && \
	../gradlew jib && \
	echo "Backend container image built successfully"

build-all: check-prereqs
	@echo "Building complete application..."
	$(MAKE) build-frontend
	$(MAKE) build-backend
	@echo "Complete application built successfully!"

# Container management targets
run:
	@echo "Running the containerized application..."
	docker run -d --name camunda-eval -p 7070:7070 -v $(PWD)/data:/data \
		-e BASE_URL="$(BASE_URL)" \
		limitium/camunda-eval:latest

stop:
	@echo "Stopping the containerized application..."
	docker stop camunda-eval || true
	docker rm camunda-eval || true

# Logging targets
logs:
	@echo "Showing container logs..."
	docker logs -f camunda-eval

# Status targets
status:
	@echo "Container status:"
	docker ps -a --filter name=camunda-eval

# Cleanup targets
clean:
	@echo "Cleaning up build artifacts and containers..."
	docker stop camunda-eval || true
	docker rm camunda-eval || true
	docker rmi limitium/camunda-eval:latest || true
	./gradlew clean
	cd frontend && rm -rf dist node_modules
	cd backend && rm -rf build

# Development targets
dev:
	@echo "Starting development environment..."
	@echo "Frontend: http://localhost:5173/"
	@echo "Backend: http://localhost:7070/"
	@echo ""
	@echo "Starting frontend and backend concurrently..."
	@echo "Press Ctrl+C to stop both services"
	@echo ""
	@(cd frontend && npm run dev) & \
	(cd backend && ../gradlew run) & \
	wait

stop-dev:
	@echo "Stopping development environment..."
	@echo "Stopping frontend (Vite) processes..."
	@pkill -f "vite" || true
	@echo "Stopping backend (Spring Boot) processes..."
	@pkill -f "CamundaEvalApplication" || true
	@echo "Stopping Gradle processes..."
	@pkill -f "gradlew run" || true
	@echo "Development environment stopped"

# Multi-stage build for eGov HRMS service

# Stage 1: Build
FROM golang:1.21-alpine AS builder

WORKDIR /app

# Install dependencies
RUN apk add --no-cache git make

# Copy go mod files
COPY go.mod go.sum ./
RUN go mod download

# Copy source code
COPY . .

# Build the application
RUN CGO_ENABLED=0 GOOS=linux go build -a -installsuffix cgo -o hrms ./cmd/server

# Stage 2: Runtime
FROM alpine:latest

RUN apk --no-cache add ca-certificates tzdata

# Install Flyway and dependencies for tenant migration
RUN apk add --no-cache \
    curl \
    bash \
    openjdk21-jre-headless \
    && curl -fsSL https://repo1.maven.org/maven2/org/flywaydb/flyway-commandline/10.7.1/flyway-commandline-10.7.1-linux-x64.tar.gz \
    | tar xz \
    && mv flyway-10.7.1 /opt/flyway \
    && ln -s /opt/flyway/flyway /usr/local/bin/flyway \
    && chmod +x /usr/local/bin/flyway

WORKDIR /opt/digit

# Copy binary from builder
COPY --from=builder /app/hrms .

# Copy migrations and migration script from build stage
COPY --from=builder /app/db/migrations .db/migrations
COPY --from=builder /app/db/migrate.sh .db/migrate.sh

# Set permissions
RUN chmod +x /opt/digit/hrms && chmod +x /opt/digit/.db/migrate.sh

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/health || exit 1

# Run the application
CMD ["./hrms"]

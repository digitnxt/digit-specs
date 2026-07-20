# Build stage
FROM golang:1.24-alpine AS builder

# Install build dependencies
RUN apk add --no-cache git make

# Set working directory
WORKDIR /app

# Copy go mod files
COPY go.mod go.sum ./

# Download dependencies
RUN go mod download

# Copy source code
COPY . .

# Build the application
RUN CGO_ENABLED=0 GOOS=linux go build -a -installsuffix cgo -o individual ./cmd/server/main.go

# Runtime stage
FROM alpine:3.19

# Install ca-certificates for HTTPS and Flyway for tenant migration
RUN apk --no-cache  \
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
COPY --from=builder /app/individual .

# Copy migrations and migration script from build stage
COPY --from=builder /app/db/migrations .db/migrations
COPY --from=builder /app/db/migrate.sh .db/migrate.sh

# Set permissions
RUN chmod +x /opt/digit/individual && chmod +x /opt/digit/.db/migrate.sh

# Expose port
EXPOSE 8080

# Run the application
CMD ["./individual"]

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Project Is

A playground for exploring gRPC service-to-service communication and load balancing on Kubernetes. Two Spring Boot 4.0.2 / Java 25 services communicate over gRPC using Spring gRPC 1.0.1:

- **hello-service** (HTTP REST gateway) — exposes `GET /hello/{name}`, calls to-upper-case-service via gRPC, returns `"Hello <UPPERCASED_NAME>"`
- **to-upper-case-service** (gRPC server) — implements `ToUpperCaseService.toUpperCase` RPC, uppercases the input and tags the response with its instance ID (to observe load balancing distribution)

## Build Commands

Each service is an independent Maven project (no parent pom). Build from within each service directory:

```bash
# hello-service
cd hello-service && ./mvnw clean package install -DskipTests

# to-upper-case-service
cd to-upper-case-service && ./mvnw clean package install -DskipTests
```

Docker images (for loading into Kind):
```bash
cd hello-service && docker build -t grpc-sample/hello-service:1 .
cd to-upper-case-service && docker build -t grpc-sample/to-upper-case-service:1 .
kind load docker-image grpc-sample/hello-service:1
kind load docker-image grpc-sample/to-upper-case-service:1
```

## Protobuf Code Generation

Both services share the same `to-upper-case-service.proto` (duplicated in each `src/main/proto/`). The `protobuf-maven-plugin` (io.github.ascopes v4.0.3) runs during `mvn compile` and generates Java sources into `target/`. Generated package: `it.valeriovaudi.touppercase.proto`.

If you modify the proto, update the copy in **both** services.

## Architecture

```
Client → HTTP :8085 → hello-service → gRPC :9090 → to-upper-case-service (N replicas)
```

- hello-service uses `GrpcChannelFactory` with a named channel `"local"` configured via `spring.grpc.client.channels.local.address`
- In K8s, the gRPC target is `dns:///to-upper-case-service:9090` — the `dns:///` scheme enables client-side name resolution against the headless ClusterIP service
- Locally, it defaults to `dns:///localhost:9090`

## Kubernetes Setup

All deployed to namespace `grpc-hello-service`. Local cluster uses Kind + MetalLB (IP pool `172.25.255.200-250`).

Key deployment details:
- `to-upper-case-service` uses a **headless Service** (`clusterIP: None`) — this is critical for gRPC client-side load balancing, as it returns individual pod IPs via DNS
- `hello-service` uses a LoadBalancer Service (exposed via MetalLB)
- KEDA ScaledObjects trigger autoscaling based on Prometheus metrics (`http_server_requests_seconds_count` for hello-service, `grpc_server_received_total` for to-upper-case)
- Commented-out env vars in `deploy.yml` contain gRPC keep-alive and round_robin LB policy configuration for experimentation

Cluster bootstrap order: Kind cluster → MetalLB → namespace → Prometheus (Helm) → KEDA (Helm) → app manifests.

## Ports

| Service | HTTP/Actuator | gRPC |
|---|---|---|
| hello-service | 8085 | — |
| to-upper-case-service | 9090 (management) | 9090 |

## Load Testing

`k8s/load.sh` hits `hello-service` in a tight loop (0.2s interval) and logs results to `k8s/result.txt`.

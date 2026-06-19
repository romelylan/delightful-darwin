# GMA Spring Gateway Deployment Architecture

**Audience:** Infrastructure/DevOps team, Platform architects
**Context:** Deploying the Spring Gateway to GMA AKS cluster behind KrakenD, with Mini App WebViews/backends and HCPH internal WebApp as callers
**Scope:** Network topology, routing, TLS, and access patterns for each caller type

---

## 1. Reference Topology

```
┌─────────────────────────────────────────────────────────────────┐
│                     EXTERNAL (Internet/WAF)                      │
└────────────────────────────────────────────────────────────────┘
                                 │
                    ┌────────────▼────────────┐
                    │   External WAF / LB     │ (Rate limit, DDoS, TLS termination)
                    └────────────┬────────────┘
                                 │
                    ┌────────────▼────────────┐
                    │      KrakenD (API GW)   │ (Port 8000 or 443 public)
                    │   (Auth, rate limit,    │   (Conneg, routing, auth offload)
                    │    routing, logging)    │
                    └────────────┬────────────┘
                                 │
        ┌────────────────────────┼────────────────────────┐
        │                        │                        │
        ▼                        ▼                        ▼
┌──────────────┐    ┌──────────────────┐    ┌──────────────┐
│  Spring GW   │    │   Keycloak (IAM) │    │ Other Svcs   │
│  (Port 9000) │    │   (Port 8080)    │    │              │
└──────────────┘    └──────────────────┘    └──────────────┘
        │
        ├──────────────────┬──────────────────┐
        │                  │                  │
        ▼                  ▼                  ▼
┌──────────────┐   ┌──────────────┐  ┌──────────────┐
│  core-be     │   │  core-be-2   │  │  other-core  │
│  (Port 8082) │   │  (Port 8082) │  │  (Port 80xx) │
└──────────────┘   └──────────────┘  └──────────────┘
```

**Network Zones:**
- **External Zone:** WAF + LB (internet-facing, terminates external TLS)
- **API Gateway Zone:** KrakenD (public/internal API gateway, orchestrates auth + routing)
- **Service Zone:** Spring Gateway + Keycloak + Core microservices (internal AKS services, inter-service mTLS optional)

---

## 2. Caller 1: HCPH Internal WebApp (on-prem) → Spring Gateway

**Scenario:** The WebApp is HCPH on-prem, must call GMA AKS Spring Gateway for protected APIs.

### 2.1 Best Practice: Route through KrakenD (public)

```
HCPH WebApp (on-prem)
    │
    │ HTTPS to public endpoint
    │
    ▼
 [Internet]
    │
    ▼
External WAF / LB (443 public)
    │
    ▼
KrakenD (8000 or 443 public)
    │ (validates JWT, routes internally)
    │
    ▼
Spring Gateway (9000 internal)
    │
    ▼
Core microservices
```

**Pros:**
- ✅ No network peering / VPN required
- ✅ Centralized auth (KrakenD validates tokens, offloads Spring GW)
- ✅ Single entry point for audit, rate limit, WAF rules
- ✅ HCPH can use the same public API as external Mini Apps (parity)
- ✅ Follows zero-trust: every caller validates identity at the edge (KrakenD)

**Implementation:**
1. KrakenD exposes public endpoint: `https://gma-api.yourdomain.com` (or similar)
2. HCPH WebApp calls `https://gma-api.yourdomain.com/api/rewards/claim` (etc.)
3. KrakenD:
   - Validates the JWT (Keycloak JWK set)
   - Routes to `Spring Gateway:9000/api/rewards/claim` internally
   - Spring GW reads the JWT and proxies to core services
4. Spring GW does **not** need to expose port 9000 externally — it's backend-only

**TLS:**
- Client → WAF/LB: standard HTTPS (certificates managed by infra)
- WAF/LB → KrakenD: either re-encrypt HTTPS or mTLS (if both are in AKS)
- KrakenD → Spring GW: internal mTLS (optional, recommended for AKS)

---

## 2.2 Alternative: Direct VPN/Peering (not recommended for HCPH-to-AKS)

If HCPH and GMA AKS are on a private network (VPN-peered or ExpressRoute), the WebApp could call Spring GW directly:

```
HCPH WebApp
    │
    │ mTLS via VPN
    │
    ▼
Spring Gateway (9000 internal)
```

**Cons:**
- ❌ Requires VPN/peering (more infrastructure, ongoing maintenance)
- ❌ Bypasses centralized auth, rate limit, audit at KrakenD
- ❌ Spring GW must expose 9000 to external networks (expanded attack surface)
- ❌ Difficult to rotate credentials, add rate limits, change routing later

**Verdict:** Only adopt if there's a specific reason (e.g., extreme latency sensitivity, already VPN-peered for other reasons). For a fresh deployment, use **§2.1 (route through KrakenD)**.

---

## 3. Caller 2: Mini App Backends → Spring Gateway

**Scenario:** Vendor-hosted or GMA-hosted Mini App backends (Java, Node, etc.) need to call Spring Gateway to reach core services (via token exchange).

### 3.1 Location determines routing:

| Mini App Backend location | Call path | TLS | Why |
|---|---|---|---|
| **Same AKS (GMA)** | Direct to Spring GW:9000 internal | mTLS (K8s cert) | No external routing; faster; internal service mesh |
| **Vendor cloud / external** | Through KrakenD public endpoint | HTTPS | No VPN; public internet; centralized auth/audit |
| **HCPH on-prem** | Through KrakenD public endpoint | HTTPS | Same as WebApp; consistent pattern |

### 3.2 Recommended: GMA-hosted Mini App backends call Spring GW directly (internal)

```
┌─────────────────────────────────────────────────────────┐
│           GMA AKS Cluster (internal)                    │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  ┌─────────────────────┐       ┌──────────────────┐   │
│  │ mini-app-loyalty-be │───→   │ Spring Gateway   │   │
│  │   (Pod / Service)   │ :9000 │ (internal svc)   │   │
│  │                     │       │                  │   │
│  └─────────────────────┘       └────────┬─────────┘   │
│                                         │             │
│                                         ▼             │
│                                  ┌──────────────┐    │
│                                  │  core-be     │    │
│                                  └──────────────┘    │
│                                                     │
└─────────────────────────────────────────────────────┘
```

**Pros:**
- ✅ No external routing; stays internal to AKS
- ✅ Kubernetes service discovery: DNS name `spring-gateway:9000` (or FQDN in namespace)
- ✅ mTLS via K8s certificate injection (Istio/Linkerd) — no secret management
- ✅ Lower latency; no WAF/LB traversal
- ✅ Audit via K8s logs and service mesh observability

**Implementation:**
1. Mini App backend (in AKS) is configured with:
   ```java
   KEYCLOAK_TOKEN_URL = "http://keycloak:8080/realms/production/protocol/openid-connect/token"
   CORE_GATEWAY_URL = "http://spring-gateway:9000"  // or FQDN in K8s namespace
   ```
2. Spring GW exposes a **Kubernetes Service** (not externally):
   ```yaml
   apiVersion: v1
   kind: Service
   metadata:
     name: spring-gateway
   spec:
     selector:
       app: spring-gateway
     ports:
       - port: 9000
         targetPort: 9000
   ```
3. TLS (optional, but recommended):
   - If using Istio/Linkerd, mTLS is automatic (transparent).
   - If not, consider HTTPS internally or rely on network policies to restrict access.

---

### 3.3 Vendor-hosted or external Mini App backends

```
┌──────────────────────────┐
│ Vendor Cloud / On-prem   │
│ mini-app-loyalty-be      │
│                          │
│  Calls:                  │
│  POST https://gma-api... │
│       /realms/prod.../.. │
│                          │
└────────────┬─────────────┘
             │ HTTPS
             │ (over internet)
             │
┌────────────▼──────────────────────────────────┐
│         GMA Infrastructure                    │
├──────────────────────────────────────────────┤
│                                              │
│  WAF/LB → KrakenD → Spring GW → core-be    │
│                                              │
└──────────────────────────────────────────────┘
```

**Pattern:** Same as HCPH WebApp (§2.1).

**Implementation:**
1. Vendor backend configured with:
   ```
   KEYCLOAK_TOKEN_URL = "https://gma-api.yourdomain.com/realms/production/protocol/openid-connect/token"
   CORE_GATEWAY_URL = "https://gma-api.yourdomain.com"
   ```
2. KrakenD routes both token and API calls:
   ```
   POST /realms/production/protocol/openid-connect/token  → Keycloak
   POST /api/wallet/deduct                                → Spring GW
   ```

**Why not Spring GW directly from external?**
- ❌ Spring GW (port 9000) should not be exposed publicly — it lacks WAF/rate limit/auth aggregation.
- ✅ KrakenD is the API gateway — centralized policy, routing, audit.

---

## 4. TLS and mTLS Strategy

| Path | TLS mode | Certificates | Who manages |
|---|---|---|---|
| Client → WAF/LB | HTTPS (standard) | Public cert (LetsEncrypt, Entrust, etc.) | Infra/LB team |
| WAF/LB → KrakenD | mTLS (recommended) | K8s CA or internal PKI | Platform team |
| KrakenD → Spring GW | mTLS (recommended) | K8s CA (Istio) or internal PKI | Platform team |
| KrakenD → Keycloak | mTLS (optional) | K8s CA or self-signed | Platform team |
| Mini app (internal) → Spring GW | mTLS (automatic via Istio) | K8s CA (transparent) | Istio sidecar |
| Mini app (external) → KrakenD | HTTPS (standard) | Part of public cert | Infra/LB team |

**Recommendation:**
- Use **Istio service mesh** for internal mTLS (automatic, transparent, no app code changes).
- Public entry point (WAF/LB) uses standard HTTPS with public certificates.
- Keycloak and KrakenD integration: validate JWK set over HTTPS; no need for mTLS there unless you require client auth.

---

## 5. Spring Gateway Configuration (environment-aware)

### Local / Docker Compose (current):
```yaml
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI: http://keycloak:8080/realms/production/protocol/openid-connect/certs
```

### AKS / Production (K8s service discovery):
```yaml
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWK_SET_URI: http://keycloak:8080/realms/production/protocol/openid-connect/certs
  # K8s DNS resolves "keycloak" to the Keycloak Service in the cluster
```

### For internal callers (mini-app-be in AKS):
**No changes needed to Spring GW config.** It already reads JWTs and routes; K8s networking handles discovery.

---

## 6. KrakenD Configuration (high-level example)

```json
{
  "endpoints": [
    {
      "endpoint": "/api/wallet/deduct",
      "method": "POST",
      "backend": [
        {
          "url_pattern": "/api/wallet/deduct",
          "host": ["spring-gateway:9000"],  // or internal K8s FQDN
          "scheme": "http"                  // mTLS handled by service mesh
        }
      ],
      "middleware": {
        "http/security": {
          "alg": "RS256",
          "jwk_url": "http://keycloak:8080/realms/production/protocol/openid-connect/certs",
          "audience": ["core-wallet-service"],
          "roles_key": "scope"
        }
      }
    }
  ],
  "tls": {
    "public": {
      "cert": "/etc/tls/public/tls.crt",
      "key": "/etc/tls/public/tls.key"
    },
    "backend": {
      // mTLS to internal services (if not using Istio)
    }
  }
}
```

---

## 7. Summary: Routing Table for all callers

| Caller | Call target | Protocol | Network | Notes |
|---|---|---|---|---|
| **HCPH WebApp (on-prem)** | `https://gma-api.yourdomain.com` | HTTPS | Internet | Public entry; KrakenD routes internally |
| **External Mini App WebView** | `https://gma-api.yourdomain.com` | HTTPS | Internet | Same public endpoint as HCPH (consistency) |
| **External Mini App backend** | `https://gma-api.yourdomain.com` | HTTPS | Internet | Token exchange + core API calls both via KrakenD |
| **GMA-hosted Mini App backend** | `http://spring-gateway:9000` | HTTP (mTLS by service mesh) | AKS internal | K8s service discovery; direct internal route |

---

## 8. Deployment Checklist for Infrastructure Team

- [ ] **KrakenD** deployed in AKS, exposed via LoadBalancer or Ingress (public HTTPS)
- [ ] **Spring Gateway** deployed in AKS (internal service, not exposed publicly)
- [ ] **Keycloak** deployed in AKS (can be internal; accessed by KrakenD + Spring GW)
- [ ] **K8s Service** created for Spring Gateway:
  ```bash
  kubectl expose deployment spring-gateway --port=9000 --name=spring-gateway
  ```
- [ ] **mTLS** enabled (Istio/Linkerd) or K8s network policies restrict access to Spring GW
- [ ] **DNS/Ingress:** Public FQDN (e.g., `gma-api.yourdomain.com`) routes to KrakenD
- [ ] **KrakenD configuration** updated to route to `spring-gateway:9000` (K8s DNS)
- [ ] **Mini App backend environment** configured with:
  - **GMA-hosted:** `CORE_GATEWAY_URL=http://spring-gateway:9000`
  - **External:** `CORE_GATEWAY_URL=https://gma-api.yourdomain.com`
- [ ] **HCPH WebApp** configured with:
  - `API_GATEWAY_URL=https://gma-api.yourdomain.com`
- [ ] **Secrets management:** Client secrets (for mini-app-loyalty-rewards, etc.) stored in K8s Secrets, not in code or env files
- [ ] **Observability:** KrakenD, Spring GW, and Keycloak logs aggregated; K8s service mesh metrics visible
- [ ] **Testing:** End-to-end flow test:
  1. HCPH WebApp calls public endpoint
  2. Mini App backend (external) calls public endpoint
  3. Mini App backend (internal) calls Spring GW directly
  4. All three successfully exchange tokens and reach core services

---

## 9. Answers to Infra Team Questions

### Q1: How would the Mini App/WebApp/WebView call the Spring Gateway? (HCPH on-prem)

**A:** Route through KrakenD (public API gateway):
- HCPH WebApp calls `https://gma-api.yourdomain.com` (public HTTPS endpoint)
- KrakenD validates the JWT and routes internally to Spring GW (9000 on AKS)
- This gives you centralized auth, audit, rate limiting, and consistent API surface for all callers (external Mini Apps and HCPH)
- Spring GW itself is **not** exposed publicly; only KrakenD is.

### Q2: How do Mini App backends call the Spring Gateway?

**A:** Depends on location:

- **GMA-hosted backend (in AKS):** Call Spring GW directly at `http://spring-gateway:9000` (K8s service discovery, mTLS optional via service mesh)
- **External/vendor backend:** Call through KrakenD public endpoint (`https://gma-api.yourdomain.com`), same as the WebApp

Both patterns reach the same Spring GW; the difference is network segment and whether KrakenD's auth/routing layer is used.

# Flutter Mini App Ecosystem Playbook
## Engineering & Architectural Blueprint for Internal & Vendor Teams

This playbook outlines the end-to-end technical patterns, security mechanisms, infrastructure standards, and developer workflows required to build and operate a high-performance **Mini App Ecosystem** hosted on a **Flutter Mobile App**.

---

## 1. Architectural Strategy

To onboard internal and external vendor teams to build "Mini Apps" that run dynamically inside a core Flutter mobile app, you must select an architecture that balances **security sandboxing**, **dynamic over-the-air (OTA) updates**, **development speed**, and **native performance**.

### The Hybrid Web-in-WebView Container
We strongly recommend and detail the **Hybrid Web-in-WebView** architecture (similar to WeChat, Alipay, Grab, and Kakao). 

* **Why Web-in-WebView?** 
  * **Absolute Isolation:** Operating system WebViews run guest code in separate processes/threads. If a vendor's Mini App crashes, it cannot crash the main Flutter Dart VM or native thread.
  * **Familiar Web Tech:** Vendors and internal teams can build Mini Apps using standard web stacks (React, Vue, Svelte, or Vanilla TS/JS) rather than needing Flutter expertise.
  * **Instant OTA Updates:** Mini Apps can be compiled as static SPA bundles, zipped, and downloaded dynamically by the Host App, bypassing App Store/Play Store review times.
  * **Granular Security:** A custom JavaScript Bridge acts as an explicit firewall. Mini Apps have zero direct access to the device or local storage except through host-controlled APIs.

---

## 2. Dynamic Hosting & Infrastructure Topology (Azure-Focused)

Your ecosystem comprises three operational team topologies. The deployment architecture relies heavily on **Azure Front Door**, **Azure CDN**, and **Azure Kubernetes Service (AKS)**.

```
                               ┌────────────────────────────────────────────────────────┐
                               │                 Flutter Host Mobile App                │
                               └──────┬────────────────────┬────────────────────┬───────┘
                                      │                    │                    │
                          (Downloads  │        (Downloads  │        (Downloads  │
                           Bundles /  │         Bundles /  │         Bundles /  │
                          APIs)       │        APIs)       │        APIs)       │
                                      ▼                    ▼                    ▼
                           ┌──────────────────┐ ┌──────────────────┐ ┌──────────────────┐
                           │   Model 1 (Core) │ │Model 2 (Int-Vend)│ │Model 3 (Ext-Vend)│
                           ├──────────────────┤ ├──────────────────┤ ├──────────────────┤
                           │ Azure Front Door │ │ Tenant Sub-CDN   │ │ Ext-Vendor CDN   │
                           │  & Azure CDN     │ │ (Corp Network)   │ │ (Public / DMZ)   │
                           └──────────┬───────┘ └──────────┬───────┘ └──────────┬───────┘
                                      │                    │                    │
                           (Routes to │        (Routes to  │        (Routes to  │
                           AKS Pods)  │        Dept-AKS)   │        Ext-API)    │
                                      ▼                    ▼                    ▼
                           ┌──────────────────┐ ┌──────────────────┐ ┌──────────────────┐
                           │ Azure AKS        │ │ Internal AKS /   │ │ Vendor Hosted    │
                           │ Core Clusters    │ │ App Services     │ │ (AWS/GCP/Azure)  │
                           └──────────────────┘ └──────────────────┘ └──────────────────┘
```

### 2.1 Model 1: Core Team Hosting (Centralized Azure Stack)
The Core Team builds, maintains, and hosts the core Flutter App, the Mini App SDK, and central orchestration components.

* **Web Technology Hosting (Frontend):** 
  * Static single-page application (SPA) bundles are stored in an **Azure Blob Storage** container configured for Static Website hosting.
  * We serve these assets globally through **Azure Front Door & Azure CDN (Standard/Premium)**.
* **Backend Services & Data Storage:**
  * Microservices are containerized and deployed into **Azure Kubernetes Service (AKS)**.
  * **Azure Ingress Controller (Application Gateway Ingress Controller - AGIC)** acts as the ingress manager, providing Layer 7 load balancing directly into the AKS pods.
  * Databases (e.g., Azure SQL, Cosmos DB) run within secure virtual networks (VNets) with private endpoints mapping into AKS.

### 2.2 Model 2: Internal Team as Vendor (Federated Enterprise Infrastructure)
Other business units across the enterprise build their own Mini Apps to plug into the core Flutter App. They host and operate their own infrastructure inside their respective business unit Azure subscriptions.

* **Asset Hosting (Decentralized CDNs):**
  * The Internal Vendor compiles their SPA and deploys the bundle to their own **Azure CDN / Blob Storage** instances.
  * The **Core Manifest Registry** (managed by the Core Team) points the Flutter Host app to the dynamic URL of the Internal Vendor's CDN when downloading that specific Mini App.
* **Backend Hosting:**
  * The Internal Vendor deploys their API services to their own AKS cluster, Azure App Services, or Container Apps.
  * **Cross-Subscription Connectivity:** Secure network tunnels are established via VNet Peering or Azure ExpressRoute.
  * **Authentication:** The Flutter app requests a scoped token from Keycloak. The Internal Vendor's backend validates this token against Keycloak, ensuring seamless SSO.

### 2.3 Model 3: External Team Vendor (Isolated DMZ/Third-Party Infrastructure) - *Future Roadmap*
An external vendor builds a Mini App, hosting the static code and backend APIs entirely on their own third-party cloud infrastructure (e.g., AWS, GCP, or an external Azure tenant).

* **Bundle Distribution Protocol:**
  * External vendors are **not** permitted to host production static bundles on their own public CDNs.
  * **The Rule:** The external vendor uploads their signed compiled bundle (`.mapk` ZIP) to the Core Team's secure upload registry. The Core Team hosts the verified bundle on the **Core Azure CDN**.
* **API Ingress Protocol (Zero-Trust API Gateway):**
  * The external vendor hosts their backend APIs on their own cloud infrastructure.
  * All communications from the Flutter app to the external backend route through the **Core Azure API Management (APIM)** gateway.
  * The APIM gateway validates Keycloak micro-tokens, strips internal header topologies, applies aggressive rate limits, and forwards requests securely to the external vendor's public HTTPS endpoints.

---

## 3. Sandbox Isolation & WebView Security

Because external/internal vendors execute code within your application shell, security must be implemented under a **Zero-Trust Model**.

### 3.1 WebView Hardening Guidelines (Flutter/Native)
The Flutter Host app must harden its WebView implementation (`flutter_inappwebview` is the recommended library due to its fine-grained control over network requests, web storage, and security configurations).

1. **Disable Unused Protocols & File Access:**
   * Force `https://` only. Disable `file://` and `content://` access schemes to prevent guest apps from reading local database files or SharedPreferences/NSUserDefaults.
   * Disable geolocation, camera, and microphone accesses at the WebView level unless explicitly granted via a Host Permission Manager.
2. **Dynamic Domain Whitelisting:**
   * The Host App must intercept all page navigations. If a Mini App attempts to redirect to an unwhitelisted domain, block the navigation.
   * Enforce Content Security Policies (CSP) injected at load-time to prevent Cross-Site Scripting (XSS) and inline script execution.
3. **Storage & Cookie Isolation:**
   * Run each Mini App with a unique `websiteDataStore` or `dataPartition` (using iOS `WKWebsiteDataStore` and Android's equivalent profiles) to ensure data, cookies, and local storage cannot be read by another Mini App or leak back to the host.

### 3.2 Content Security Policy (CSP)
Every Mini App must bundle or serve a strict CSP header:
```http
Content-Security-Policy: default-src 'self' https://api.yourdomain.com; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; connect-src 'self' https://api.yourdomain.com; frame-ancestors 'none';
```

---

## 4. The Secure JavaScript Bridge (JS-Bridge)

The JS-Bridge is the designated firewall between the Mini App Sandbox and the Flutter Host.

### 4.1 Asynchronous Communication Protocol
All communications between the Mini App and the Host must be asynchronous, one-way messages using structured JSON. Direct synchronous bindings (like exposing raw Dart objects to Javascript context) are strictly forbidden as they can leak memory addresses and internal system hooks.

```
Mini App JS                  WebView Boundary                 Flutter Core
-----------                  ----------------                 ------------
  |                                 |                               |
  |-- invoke('getGPSLocation') ---->|                               |
  |                                 |-- postMessage(Payload) ------>|
  |                                 |                               |-- Verify API Permissions
  |                                 |                               |-- Request Native Location
  |                                 |                               |-- [USER CONSENT PROMPT]
  |                                 |<-- callback(LocationData) ----|
  |<-- promise.resolve(Location) ---|                               |
```

### 4.2 The Message Payload Structure
Every request from the guest app must follow a standardized payload structure:

```json
{
  "miniAppId": "com.vendor.loyalty-rewards",
  "requestId": "req_168493021132",
  "action": "device.getGPSLocation",
  "params": {
    "highAccuracy": true
  },
  "signature": "3aef82b61920..."
}
```

---

## 5. Token & Identity Management

A key challenge when working with third-party vendors is **maintaining secure user sessions without exposing high-privilege access tokens (e.g., Bearer JWT tokens with master API access)**.

### 5.1 Token Separation (Host Token vs. Scoped Guest Token)
1. **Never expose the Host App's OAuth Access Token** or Refresh Token directly to the WebView/Mini App. If a vendor's app has an XSS vulnerability, the host user account will be fully compromised.
2. **Issue Scoped Mini-App Tokens (Micro-tokens):**
   * When a Mini App needs to communicate with your backend APIs, it requests a token through the JS-Bridge: `hostApp.auth.getToken()`.
   * The Host App contacts Keycloak, passing the `miniAppId` and the current user's host session.
   * Keycloak generates a short-lived token (JWT) specifically scoped for that Mini App (e.g., Aud: `mini-app-loyalty`, Scopes: `read:user_profile`, Exp: 15 minutes).
   * Even if this token is compromised, its reach is severely restricted and it expires quickly.

---

## 6. Coding & UX Standards for Mini Apps

To ensure the ecosystem feels premium and integrated, Mini Apps must follow strict quality, stylistic, and coding standards.

### 6.1 Technology Stack & Architecture
* **Framework Agnostic:** Mini Apps can be built in Vanilla Javascript, React, Vue, Svelte, or Next.js (exported as pure static files).
* **Build System:** Web apps must be compiled using Webpack, Vite, or Rollup. Source maps must be stripped from production bundles but uploaded to the Host's Sentry server for error tracing.
* **Routing:** Single Page Application (SPA) routing must use **Hash Routing** (`/#/profile`) rather than HTML5 History API Routing to avoid asset resolution errors when running offline from a local device server.

### 6.2 UI/UX Consistency & Design Tokens
Vendors must import the core host UI library or follow a shared design token stylesheet to prevent disjointed user experiences.

1. **Brand Variables (CSS Custom Properties):**
   ```css
   :root {
     --host-primary-color: #0F172A; /* Slate 900 */
     --host-secondary-color: #38BDF8; /* Sky 400 */
     --host-font-family: 'Inter', system-ui, -apple-system, sans-serif;
     --host-border-radius: 12px;
     --host-spacing-unit: 8px;
     --host-background-color: #F8FAFC;
   }
   ```
2. **Typography:** Use modern web fonts system stacks matching the Host App (e.g., `Inter` or standard system sans-serif font). Custom fonts inside Mini Apps should be heavily restricted or loaded from a shared CDN to reduce bundle size.
3. **Safe Area Management:**
   Since Mini Apps run full-viewport, they must handle physical device notches using CSS Safe Area variables:
   ```css
   .mini-app-header {
     padding-top: max(16px, env(safe-area-inset-top));
     padding-bottom: max(16px, env(safe-area-inset-bottom));
   }
   ```

### 6.3 Performance and Caching Strategies
* **Maximum Bundle Size:** Single bundle sizes must not exceed **2MB** (compressed).
* **Asset Optimization:** All images must be compressed (WebP/SVG) and ideally fetched from CDNs rather than included in the zip bundle.
* **Caching (Local Asset Interception):** 
  * The Host app downloads a `.zip` containing all HTML, CSS, and JS assets.
  * The Host serves files directly from the local device storage.
  * This guarantees sub-50ms render latency (offline-first execution).

---

## 7. Infrastructure & Deployment (CI/CD)

The Mini App packaging and release process should be fully automated to manage multiple internal and vendor teams independently.

### 7.1 Bundle Package Format (`.mapk`)
A Mini App bundle must be packaged in a custom archive format (e.g., `.mapk`, which is a standard `.zip` containing a specific directory structure):

```
my-rewards-app.mapk/
├── manifest.json
├── index.html
├── assets/
│   ├── app.js
│   ├── app.css
│   └── logo.png
└── locales/
    ├── en.json
    └── id.json
```

---

## 8. Incident Response & Remote Revocation Plan

* **Immediate Suspension (The "Kill Switch"):**
  The Host app must ping a dynamic configuration manifest on application launch (cached with a low Time-To-Live, e.g., 5 minutes). If a Mini App is flagged as compromised (`"status": "disabled"`), the Host will immediately unload the WebView, delete local offline assets, and display a user-friendly error message: *"This mini-app is currently undergoing maintenance."*
* **Automatic Exception Tracking:**
  Inject a global Javascript error listener into the WebView window object. Route all unhandled JavaScript exceptions via the JS-Bridge back to the Host app's central telemetry system (e.g., Sentry, Firebase Crashlytics) for real-time alerting.

---

## 9. Pure Flutter-to-Flutter Mini App Architectures

If your **Mini Apps themselves are built in Flutter**, dynamic loading requires specialized architecture due to operating system security guidelines (specifically iOS App Store rules forbidding raw native binary/AOT code pushing).

```
   ┌─────────────────────────────────────────────────────────────┐
   │                  Flutter Host App Shell                     │
   │  ┌──────────────────┐  ┌─────────────────────────────────┐  │
   │  │   Core Engines   │  │    Dynamic Loading Router       │  │
   │  └────────┬─────────┘  └────────────────┬────────────────┘  │
   └───────────┼─────────────────────────────┼───────────────────┘
               │ (Direct Dart calls)         │ (Decodes & renders)
   ┌───────────▼─────────┐  ┌────────────────▼───────────────────┐
   │ Compile-Time Modules│  │ Server-Driven UI (RFW Bytecode)    │
   │ (Melos Monorepo)    │  │  - Safe, Declarative layouts       │
   │  - Shared Assets    │  │  - Zero iOS policy risk            │
   │  - Max performance  │  │  - Immediate Over-The-Air updates  │
   │  - Vendor UI updates               │
   └─────────────────────┘  └────────────────────────────────────┘
```

Below are the three approved architectural models for dynamic Flutter-to-Flutter loading:

### Model A: Monolithic Multi-Package Monorepo ( Melos / Git Submodules )
**Best For: Internal Development Teams**
* **The Pattern:** Build each Mini App as an independent Flutter Dart Package (`package:mini_app_rewards`). Manage the codebase using a monorepo manager like **Melos**.
* **Integration:** The Host App lists the mini-apps as local dependency paths in its `pubspec.yaml`. Compile and release the Host App as a single unified binary.
* **Pros:** Absolute maximum performance, 100% access to native code channels, shared state/caching, no App Store policy violations.
* **Cons:** No immediate dynamic OTA updates for guest apps independent of the host app store release. A vendor update requires rebuilding and releasing the entire mobile app.

### Model B: Server-Driven UI via Remote Flutter Widgets (RFW)
**Best For: Simple Vendor Forms, Promotional Dashboards, Static Lists**
* **The Pattern:** Use the official Flutter team package `remote_flutter_widgets`.
* **Integration:** Mini Widgets write their UI layouts using a declarative text widget format (which compiles to a highly compressed binary file, `.rfw`). The Host App downloads this `.rfw` bundle over-the-air and renders it natively inside a safe local placeholder using pre-approved host widgets.
* **Pros:** Highly secure, complies 100% with iOS App Store dynamic guidelines, extremely small bundle size (often < 50KB).
* **Cons:** Dynamic layouts cannot run arbitrary custom Dart logic. Any complex custom logic must be pre-compiled into the Host App and triggered via event-string callbacks.

### Model C: Compiled Dart Bytecode Interpreters (`dart_eval`)
**Best For: Complex Dynamic Logic with OTA Requirements**
* **The Pattern:** Use the `dart_eval` package (or dynamic runtime interpreters).

---

## 10. Enterprise Backend Integration (Java & .NET)

If your engineering teams are skilled in **Java (Spring Boot / Quarkus)** and **.NET (ASP.NET Core)**, their primary responsibility will be building the robust **Ecosystem API Gateway** and the isolated **Mini App Backends**.

### 10.1 Centralized Gateway Token Offloading (Recommended Architecture)

> [!IMPORTANT]
> To eliminate code duplication, minimize development friction, and prevent massive overhead across your AKS microservices, **DO NOT** implement Keycloak JWT validation inside every individual .NET or Spring Boot service.
> Instead, offload token validation entirely to your **Azure API Management (APIM)** or **Kubernetes Ingress Controller** (e.g., NGINX Ingress with Lua JWT, or Kong Gateway) at the edge of the AKS cluster.

```
Mini App Backend               Azure API Ingress (NGINX/APIM)           Core AKS Microservice (.NET/Spring)
────────────────               ──────────────────────────────           ───────────────────────────────────
       │                                     │                                           │
       │── 1. POST /wallet/deduct ──────────>│                                           │
       │   (Bearer: Keycloak exchange JWT)   │── 2. Validate JWT against Keycloak JWKS   │
       │                                     │      (Verify Sign, Exp, Scope, Aud)       │
       │                                     │                                           │
       │                                     │── 3. Propagate Trusted Headers ──────────>│
       │                                     │      (X-User-Id: "user_9841284"           │ (No JWT overhead)
       │                                     │       X-Client-Id: "loyalty-rewards"      │ (Reads headers directly)
       │                                     │       X-User-Scopes: "write:rewards")     │
       │                                     │                                           │
       │<── 5. Proxy 200 OK Response ────────│<── 4. Process & Return ───────────────────│
```

#### The Gateway-to-Service Header Propagation Pattern
1. **Edge Validation:** The Ingress Gateway intercepts the incoming `Authorization: Bearer <Keycloak Delegated JWT>` header.
2. **Signature & Expiry Check:** The Gateway checks the token's validity against Keycloak’s JSON Web Key Set (JWKS) endpoint (`/protocol/openid-connect/certs`).
3. **Audience & Scope Check:** Enforces that the request is sent to the correct API endpoint and contains the required OAuth scope.
4. **Header Enrichment:** The Gateway strips the heavy JWT block and forwards the request to the target AKS microservice VNet IP address, injecting highly optimized **trusted headers**:
   * `X-User-Id`: The `sub` (Subject) claim containing the unique user ID.
   * `X-Client-Id`: The Keycloak client ID that initiated the request (e.g. `mini-app-loyalty-rewards`).
   * `X-User-Scopes`: Commas-separated list of scopes (e.g. `write:rewards`).
5. **Downstream Simplicity:** The internal .NET or Spring Boot service receives a standard HTTP request. It simply reads the trusted HTTP headers, completely bypassing the complexity of JWT parsing, cryptography verification, and identity extraction.

---

> [!NOTE]
> **Keycloak Performance & Network Load Analysis**
> 
> * **Zero API Calls per Microservice Request:** Edge validation is **completely stateless**. The Ingress Gateway/APIM **does not make an HTTP request to the Keycloak server** to validate individual API calls.
> * **JWKS Public Key Caching:** On startup, the Gateway calls Keycloak's public JWKS endpoint `/protocol/openid-connect/certs` once to fetch the public cryptographic keys (public RSA/ECDSA verification keys). The Gateway **caches these keys locally in memory** (typically for 24 hours).
> * **Pure Mathematical In-Memory Validation:** When an incoming token is received, the Gateway validates its signature mathematically using the cached keys. This operation runs purely in memory at the edge, taking **<1 millisecond** and placing **absolutely zero network or API load** on the Keycloak server.
> * **Keycloak Load Profile:** Keycloak only processes requests during **Token Exchange (RFC 8693)** or user logins (which occur once per session/boot), not during microservice data exchanges. Thus, this centralized gateway model scales infinitely without requiring vertical scaling of your core Keycloak database.
> 
> *(Caution: Avoid Keycloak Stateful Token Introspection (`/token/introspect`) on every request, as that would force an active HTTP round-trip on every microservice call and crush Keycloak performance).*

---

### 10.2 Downstream Microservice Code (Simplified for Header Extraction)

#### .NET (ASP.NET Core) Controller with Trusted Headers
Because the Gateway handles all security validations, a .NET microservice controller only needs to inspect headers:

```csharp
using Microsoft.AspNetCore.Mvc;

[ApiController]
[Route("api/wallet")]
public class WalletController : ControllerBase
{
    [HttpPost("deduct")]
    public IActionResult DeductPoints([FromBody] DeductionRequest request)
    {
        // 1. Extract trusted identity headers injected by Azure Ingress/APIM
        if (!Request.Headers.TryGetValue("X-User-Id", out var userId) ||
            !Request.Headers.TryGetValue("X-Client-Id", out var clientId))
        {
            return StatusCode(403, "Forbidden: Request must route through the API Ingress Gateway.");
        }

        // 2. Validate Client Scopes passed from Gateway
        Request.Headers.TryGetValue("X-User-Scopes", out var scopes);
        if (string.IsNullOrEmpty(scopes) || !scopes.ToString().Contains("write:rewards"))
        {
            return StatusCode(403, "Forbidden: Missing required write:rewards scope.");
        }

        // 3. Process business logic (Zero Cryptographic Overhead)
        Console.WriteLine($"Processing deduction for User: {userId} initiated by Mini App: {clientId}");
        return Ok(new { Status = "Success", DeductedAmount = request.Amount, CurrentUser = userId.ToString() });
    }
}

public class DeductionRequest { public decimal Amount { get; set; } }
```

#### Java (Spring Boot) RestController with Trusted Headers
The equivalent Spring Boot 3.x controller is lightweight and has zero dependency on Spring Security OAuth2 resource server packages:

```java
package com.core.wallet.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/wallet")
public class WalletController {

    @PostMapping("/deduct")
    public ResponseEntity<?> deductPoints(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Client-Id", required = false) String clientId,
            @RequestHeader(value = "X-User-Scopes", required = false) String scopes,
            @RequestBody Map<String, Object> payload) {

        // 1. Guard check: Enforce that request came through the Gateway
        if (userId == null || clientId == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Forbidden: Request must route through the API Ingress Gateway.");
        }

        // 2. Check scopes propagated by the gateway
        if (scopes == null || !scopes.contains("write:rewards")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Forbidden: Missing required write:rewards scope.");
        }

        // 3. Process transaction
        int amount = (int) payload.get("amount");
        System.out.println("Deducting " + amount + " points for User " + userId + " via " + clientId);

        return ResponseEntity.ok(Map.of(
            "status", "Success",
            "userId", userId,
            "deducted", amount
        ));
    }
}
```

---

### 10.3 Securing Downstream Microservices (Cluster Hardening)

#### 1. Kubernetes NetworkPolicies (Network Segmentation)
Apply a NetworkPolicy to your Spring/ .NET pods so they **only** accept incoming TCP traffic originating from the Ingress Controller pod or Gateway subnet. All other direct cluster traffic is immediately blocked by the Kubernetes virtual network card (CNI).

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-only-gateway-ingress
  namespace: core-services
spec:
  podSelector:
    matchLabels:
      app: core-wallet-service
  policyTypes:
  - Ingress
  ingress:
  - from:
    - namespaceSelector:
        matchLabels:
          name: ingress-nginx # Restricts access solely to the Ingress Namespace
```

#### 2. Mutual TLS (mTLS) via Service Mesh (Istio / Linkerd)
If you deploy a service mesh in AKS:
* The Ingress Gateway and the microservice pods are injected with Envoy sidecar proxies.
* All service-to-service communication within the AKS cluster is encrypted via **mTLS**.
* You configure an Istio `AuthorizationPolicy` to ensure that only the Ingress proxy is authorized to call the downstream wallet microservice.

---

### 10.4 Architectural Trade-Off: Local Validation vs. Gateway Validation

| Metric | Model A: Local Microservice JWT Validation | Model B: Gateway Ingress Token Offloading (Recommended) |
| :--- | :--- | :--- |
| **Development Speed** | **Slow:** Every single service team must configure Keycloak libraries, JWKS URLs, and claim policies. | **Fast:** Microservice developers write pure business logic controllers, reading standard HTTP headers. |
| **Performance Overhead** | **High:** Every request triggers local cryptographic decryption and JWKS HTTP fetches. | **Low:** Gateway caches Keycloak JWKS signature keys; internal proxy calls are lightning fast. |
| **Security Maintenance** | **Hard:** Patching a JWT library vulnerability requires redeploying 50+ microservices. | **Easy:** Patching or upgrading Keycloak policies is configured at a single edge point (Ingress/APIM). |
| **Bypass Vulnerability** | **None:** Pods independently verify all incoming tokens directly. | **Medium Risk:** Secure setup requires strict Kubernetes `NetworkPolicies` or mTLS sidecars. |

---

## 11. Scoped Authentication & Delegation Token Flows (Keycloak Integration)

This section details exactly how Keycloak supports: **(1) From the Host App to the Guest Mini App (Client-side)**, and **(2) From the Mini App's backend to the Core Mobile Team's backend microservices (Server-side)** using native Keycloak configurations.

### 11.1 Keycloak Client Topology Design
To orchestrate this safely inside your Keycloak Realm, you must register three distinct Keycloak Client Definitions:

1. **`flutter-host-app` (Public Client):**
   * **Access Type:** Public (No client secret).
   * **Allowed Grant Types:** Authorization Code Flow with PKCE.
   * **Access Role:** The primary login portal for standard mobile app users.
2. **`mini-app-loyalty-rewards` (Confidential Client):**
   * **Access Type:** Confidential (Requires Client Secret/Certificate credentials).
   * **Role:** The backend service running the loyalty mini-app microservice.
   * **Service Account Enabled:** Yes (Required for client-credential authentication).
3. **`core-wallet-service` (Bearer-Only / Resource Client):**
   * **Access Type:** Confidential / Bearer-only.
   * **Role:** Core AKS microservice hosting wallet and transaction endpoints.

---

### 11.2 Flow 1: Host App to Guest Mini App (Client Scoped Token)
To prevent the guest app from reading the core session token, we request a scoped access token directly from Keycloak using OAuth 2.0 Scope constraints.

```
Guest Mini App (JS)              Flutter Host App (Dart)            Keycloak Server (Realms)
──────────────────              ───────────────────────            ────────────────────────
        │                                  │                                    │
        │── 1. sdk.auth.getToken() ───────>│                                    │
        │                                  │── 2. Request Token Exchange ──────>│
        │                                  │   (Client: flutter-host-app        │
        │                                  │    Audience: mini-app-loyalty)     │
        │                                  │                                    │
        │                                  │<── 3. Return Scoped JWT Token ─────│
        │                                  │                                    │
        │<── 4. Resolve Token Promise ─────│                                    │
        │                                  │                                    │
        │── 5. GET /api/rewards ───────────┼───────────────────────────────────> [Mini App Backend]
            (Bearer Micro-JWT)             │                                    │
```

#### Keycloak Configuration Steps for Scoped Tokens:
1. Create a **Client Scope** in Keycloak named `loyalty-scope`.
2. Attach an **Audience Protocol Mapper** inside `loyalty-scope`:
   * **Name:** `loyalty-audience-mapper`
   * **Included Client Audience:** `mini-app-loyalty-rewards`
3. Associate this Client Scope with the `flutter-host-app` client as **Optional**.
4. When the user logs in, the host app obtains its master token. When a Mini App boots up and calls `getToken()`, the Flutter Host App executes an silent token refresh exchange request to Keycloak specifying `scope=loyalty-scope`, obtaining a JWT containing:
   ```json
   {
     "iss": "https://keycloak.yourdomain.com/realms/production",
     "sub": "user_9841284",
     "aud": "mini-app-loyalty-rewards",
     "scope": "loyalty-scope",
     "azp": "flutter-host-app"
   }
   ```
5. This token is passed securely to the Mini App WebView context.

---

### 11.3 Flow 2: Mini App Backend to Core Microservices (Keycloak Token Exchange RFC 8693)
When the Mini App backend needs to call the Core Team's `core-wallet-service` (AKS), it exchanges the user's Scoped Micro-JWT for a Delegated Core token. Keycloak provides native support for **OAuth 2.0 Token Exchange**.

```
Mini App Backend               Core API Gateway               Keycloak (Token Endpoint)       Core Wallet Service (AKS)
────────────────               ────────────────────────       ─────────────────────────       ─────────────────────────
       │                               │                                  │                               │
       │── 1. Exchange token POST ────>│                                  │                               │
       │   (Subject: Scoped Micro-JWT) │── 2. Forward Token Exchange ────>│                               │
       │                               │                                  │                               │
       │                               │<── 3. Return Delegated JWT ──────│                               │
       │<── 4. Return Core JWT ─────────│                                  │                               │
       │                               │                                  │                               │
       │── 5. POST /wallet/deduct ──────┼──────────────────────────────────┼──────────────────────────────>│
       │   (Bearer: Delegated CoreJWT) │                                  │                               │
```

> [!NOTE]
> **The Two-Step Backend Call Pattern (Developer Clarification)**
> 
> When a Mini App Backend needs to write data or call a central Core AKS Microservice, **exactly two sequential HTTP requests** are executed:
> 
> * **Request 1: Exchange Token Call (Keycloak Swap):** 
>   The Mini App backend takes the user's incoming `Scoped Micro-JWT` (the `subject_token`) and calls the Keycloak Token Exchange endpoint `/protocol/openid-connect/token` using its own client credentials. Keycloak validates the user session, verifies scopes, and returns a brand-new `Delegated Core-JWT` targeted specifically to the Core Service audience.
> * **Request 2: Service Invocation Call (Core Execution):** 
>   Once Request 1 succeeds and returns the new token, the Mini App backend makes the *actual* functional HTTP API call (e.g. `POST /api/wallet/deduct`) to the Core Gateway/Ingress, supplying this newly acquired `Delegated Core-JWT` in the standard `Authorization: Bearer` header.

#### Step 1: Enable Token Exchange Feature in Keycloak
Ensure that Keycloak is started with the token exchange feature enabled. If using Keycloak container builds, pass the feature flag:
```bash
bin/kc.sh start --features=token-exchange
```

#### Step 2: Grant Token Exchange Permissions in Keycloak Console
1. Navigate to **Clients** -> select `flutter-host-app`.
2. Under the **Permissions** tab, toggle **Permissions Enabled** to **ON**.
3. Click on the auto-created policy named `token-exchange.permission.client.flutter-host-app`.
4. Define a **Client Policy** allowing the `mini-app-loyalty-rewards` client permission to exchange tokens issued for the `flutter-host-app`.

#### Step 3: Execute Token Exchange Request (CURL / Backend Call)
The Mini App backend takes the user's dynamic Scoped Micro-JWT (`subject_token`) and calls the Keycloak Token Endpoint:

```http
POST /realms/production/protocol/openid-connect/token HTTP/1.1
Host: keycloak.yourdomain.com
Content-Type: application/x-www-form-urlencoded
Authorization: Basic bWluaS1hcHAtbG95YWx0eS1yZXdhcmRzOm15LXNlY3JldA== (Base64 client_id:client_secret)

grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Atoken-exchange
&subject_token=eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJ1c2VyXzk4NDEyODQiLCJhdWQiOiJtaW5pLWFwcC1sb3lhbHR5LXJld2FyZHMiLCJpc3MiOiJodHRwczovL2tleWNsb2FrLnlvdXJkb21haW4uY29tL3JlYWxtcy9wcm9kdWN0aW9uIn0...
&subject_token_type=urn%3Aietf%3Aparams%3Aoauth%3Atoken-type%3Aaccess_token
&audience=core-wallet-service
```

#### Keycloak Response:
Keycloak validates the client authorization, verifies the signature of the subject token, swaps the audience context, and issues a new delegation access token:

```json
{
  "access_token": "eyJhbGciOiJSUzI1NiIs...",
  "issued_token_type": "urn:ietf:params:oauth:token-type:access_token",
  "token_type": "Bearer",
  "expires_in": 300
}
```

---

### 11.4 Detailed Mechanics: The client-Side "Resolve Token Promise" Handshake

In the Client-Side sequence diagram (**Flow 1, Step 4**), the Guest Mini App's JavaScript execution waits for a **"Resolve Token Promise"**. 

```
 Guest WebView (JS Container)                       Host Mobile App (Flutter Core)
 ────────────────────────────                       ──────────────────────────────
              │                                                    │
 1. const token = await sdk.getToken();                            │
    [Generates req_16849, returns Pending Promise]                 │
              │── 2. window.flutter.postMessage(req_16849) ───────>│
              │                                                    │ [Processes Keycloak Exchange]
              │                                                    │ [Retrieves Scoped Micro-JWT]
              │                                                    │
              │<── 3. evaluateJavascript(Resolve req_16849) ───────│
 4. window._hostAppCallbacks['req_16849'].resolve(jwt)             │
    [Fulfills Promise! Code resumes with token]                    │
              ▼                                                    │
```

This asynchronous handshake bridges the memory-isolated boundary between Javascript running inside the OS WebView and the native Dart VM:

#### A. Guest JS SDK Mechanism (Javascript Inside WebView)
To prevent locking the UI thread, the Guest JS SDK must wrap the JS-Bridge call in a native **Javascript Promise**. It registers the Promise's native `resolve` and `reject` callbacks into a globally scoped registry map inside the WebView window context before sending the message:

```javascript
// Exposed inside the global WebView window scope
window._hostAppCallbacks = {};

const sdk = {
  auth: {
    getToken: function(scopes) {
      // 1. Return a Promise to the Guest Developer
      return new Promise((resolve, reject) => {
        // 2. Generate a unique request ID to track this specific call
        const requestId = "req_" + Date.now() + "_" + Math.floor(Math.random() * 1000);
        
        // 3. Register the resolve/reject triggers in the global registry
        window._hostAppCallbacks[requestId] = {
          resolve: resolve,
          reject: reject,
          timestamp: Date.now()
        };
        
        // 4. Send the payload across the WebView bridge boundary to Flutter
        try {
          window.flutter_inappwebview.callHandler('JSBridgeChannel', JSON.stringify({
            miniAppId: "com.vendor.loyalty-rewards",
            requestId: requestId,
            action: "auth.getToken",
            params: { scopes: scopes }
          }));
        } catch (e) {
          // Clean up on failure
          delete window._hostAppCallbacks[requestId];
          reject(new Error("JS-Bridge not available: " + e));
        }
      });
    }
  }
};
```

#### B. Flutter Host Resolution Mechanism (Dart Core)
When the Flutter Host App completes the Keycloak authentication exchange, **the Host App itself is responsible for resolving the Javascript Promise**. It does this by using the WebView Controller's script execution engine to locate and execute the corresponding registered callback by ID:

```dart
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_inappwebview/flutter_inappwebview.dart';

class HostWebViewWidget extends StatefulWidget {
  @override
  _HostWebViewWidgetState createState() => _HostWebViewWidgetState();
}

class _HostWebViewWidgetState extends State<HostWebViewWidget> {
  InAppWebViewController? _webViewController;

  @override
  Widget build(BuildContext context) {
    return InAppWebView(
      initialUrlRequest: URLRequest(url: WebUri("https://cdn.dept.yourdomain.com/rewards/")),
      onWebViewCreated: (controller) {
        _webViewController = controller;
        
        // Listen to the JS SDK bridge channels
        controller.addJavaScriptHandler(handlerName: 'JSBridgeChannel', callback: (args) async {
          final String rawJson = args.first;
          final Map<String, dynamic> request = jsonDecode(rawJson);
          
          final String requestId = request['requestId'];
          final String action = request['action'];
          
          if (action == 'auth.getToken') {
            try {
              // 1. Process Keycloak exchange securely in native background
              String scopedToken = await _fetchScopedTokenFromKeycloak(request['params']['scopes']);
              
              // 2. TRIGGER RESOLUTION: Execute evaluateJavascript to fulfill the Guest's JS Promise
              _resolveJSPromise(requestId, scopedToken);
            } catch (error) {
              // 3. TRIGGER REJECTION: Fails the JS Promise cleanly on errors
              _rejectJSPromise(requestId, error.toString());
            }
          }
        });
      },
    );
  }

  // Uses InAppWebViewController to execute JS inside the WebView runtime
  void _resolveJSPromise(String requestId, String jwtToken) {
    if (_webViewController == null) return;
    
    // Locates the global registry mapping table, calls the resolve trigger, and sweeps the callback from memory
    final String jsSource = """
      if (window._hostAppCallbacks && window._hostAppCallbacks['$requestId']) {
        window._hostAppCallbacks['$requestId'].resolve('$jwtToken');
        delete window._hostAppCallbacks['$requestId'];
      }
    """;
    
    _webViewController!.evaluateJavascript(source: jsSource);
  }

  void _rejectJSPromise(String requestId, String errorMessage) {
    if (_webViewController == null) return;
    
    final String jsSource = """
      if (window._hostAppCallbacks && window._hostAppCallbacks['$requestId']) {
        window._hostAppCallbacks['$requestId'].reject(new Error('$errorMessage'));
        delete window._hostAppCallbacks['$requestId'];
      }
    """;
    
    _webViewController!.evaluateJavascript(source: jsSource);
  }

  Future<String> _fetchScopedTokenFromKeycloak(List<dynamic> scopes) async {
    // Standard secure Keycloak OIDC authentication/refresh logic goes here
    return "eyJhbGciOiJSUzI1NiIs...";
  }
}
```

---

### 11.5 Token Validation for Isolated Mini Apps (No Core Backends)

> [!NOTE]
> **Use Case: Isolated Mini Apps (e.g. The Insurance Mini App)**
> 
> Some internal federated teams (Model 2) or external vendors (Model 3) build Mini Apps that are completely self-contained. For example, the **Insurance Mini App** does *not* need to make any calls to the central Core AKS backend microservices, but they *still* want to authenticate the active user and validate the integrity of the token (`Scoped Micro-JWT`) received by their Guest WebView front-end.
> 
> The Core Mobile Platform provides **three standard paths** to accomplish this without writing any new microservices on the Core AKS cluster:

#### Approach A: Local Stateless Verification (Highly Recommended - Zero Latency)
Since the `Scoped Micro-JWT` is a standard JSON Web Token issued by the central Keycloak, the Insurance backend can validate it **100% locally and stateless-ly** inside their own isolated infrastructure:
1. **JWKS Key Retrieval:** The Insurance backend configures its Spring Security / .NET token validation middleware to point directly to Keycloak's public JWKS endpoint:
   `https://keycloak.yourdomain.com/realms/production/protocol/openid-connect/certs`
2. **Local Verification:** The backend downloads the Keycloak public keys, caches them, and verifies the signature, expiration (`exp`), and audience (`aud = mini-app-insurance`) locally in-memory.
3. **Ecosystem Independence:** **This requires absolutely zero API calls to the Core API Gateway or Core AKS cluster.**

#### Approach B: Keycloak Default OIDC `/userinfo` Endpoint
If the Insurance team wants a standard, out-of-the-box OIDC API to check user profiles and token validity, they can call Keycloak's default endpoint:
1. **The API Call:** The Insurance backend makes an HTTP GET call:
   `GET https://keycloak.yourdomain.com/realms/production/protocol/openid-connect/userinfo`
2. **Header:** Include `Authorization: Bearer <Scoped Micro-JWT>`.
3. **Response:** Keycloak returns `200 OK` with the active user profile claims (e.g. email, username) if the token is authentic and active. If expired or forged, Keycloak returns `401 Unauthorized`.

#### Approach C: Core Gateway `/api/auth/whoami` Diagnostic API (Platform Echo Endpoint)
For diagnostic testing and rapid onboarding, the Core Mobile Team hosts a default **Echo / Dummy API** directly on the central Core API Ingress Gateway:
1. **The Endpoint:** `GET https://gateway.yourdomain.com/api/auth/whoami`
2. **Gateway Magic:** The API Gateway intercepts this call and performs the edge-token validation (as detailed in Section 10.1). If valid, it extracts the claims and returns a lightweight JSON profile:
   ```json
   {
     "authenticated": true,
     "userId": "user_9841284",
     "clientId": "mini-app-insurance",
     "scopes": ["insurance-scope"],
     "timestamp": "2026-05-27T18:10:00Z"
   }
   ```
3. **Developer Purpose:** This acts as the perfect **Dummy/Health Check API** for internal teams to verify that the JS SDK and token generation are working flawlessly before they build any business backend logic.

---

## 12. Local Proof-of-Concept (POC) Prerequisites & Orchestration Guide

To de-risk the ecosystem, we provide a complete, orchestrated **Local Proof-of-Concept (POC)** environment. This allows your platform engineers and internal teams to run the entire end-to-end token validation loop on their workstations.

### 12.1 Prerequisite Software Installation
Before initiating the local POC generation, ensure the following software is installed on your Windows workstation:

1. **Docker Desktop for Windows:**
   * **Purpose:** NGINX, Keycloak, and the Spring Boot microservices run in isolated, lightweight Linux containers. This isolates the POC from your primary system libraries.
   * **Installation:** Download and install from [Docker Desktop](https://www.docker.com/products/docker-desktop/). **Ensure WSL2 integration is enabled** during installation.
2. **Flutter SDK (Stable Channel):**
   * **Purpose:** Compile and run the mock Flutter Host Mobile App on your workstation.
   * **Installation:** Follow the official [Flutter Windows Guide](https://docs.flutter.dev/get-started/install/windows). Ensure the `flutter` binary is added to your Windows user environment `PATH`.
3. **Java Development Kit (JDK 17 or 21):**
   * **Purpose:** (Optional) To run or debug the Spring Boot backend microservices locally outside of Docker containers.
   * **Installation:** Install via Microsoft OpenJDK build or Eclipse Temurin.
4. **Node.js (LTS v18+ or v20+):**
   * **Purpose:** (Optional) If you wish to run the local guest web application development server (`npm run dev`) outside a Docker container.
5. **A Code Editor:**
   * Recommended: **Visual Studio Code** (with Flutter, Dart, Extension Pack, and Docker extensions) or **IntelliJ IDEA / Android Studio**.

---

### 12.2 Orchestration Architecture (Orchestrated via Docker Compose)

The local POC will set up a workspace folder structured as a multi-module environment:

```
local-mini-app-poc/
├── docker-compose.yml          # Main orchestration file for NGINX, Keycloak, Backends
├── keycloak/                   # Realm JSON files and configuration
├── nginx/                      # NGINX gateway configurations & Lua validation scripts
├── mini-app-be/                # Spring Boot App 1 (Loyalty Backend doing token exchange)
├── core-be/                    # Spring Boot App 2 (Core Backend verifying headers)
├── guest-webapp/               # HTML5/JS Mini App (served locally)
└── flutter-host/               # Mock Flutter Host App (WebView + SecureJSBridge)
```

The next phase will automatically generate all codebase configurations in your repository.

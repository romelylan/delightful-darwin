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

### 5.2 Threat Model: Defensive Mitigations Against Scoped Token Theft

Your colleague is **100% correct**. The JS-Bridge interface `sdk.auth.getToken()` represents a primary target for client-side token extraction. If a Mini App's frontend is compromised by **Cross-Site Scripting (XSS)** or a **compromised NPM supply-chain dependency**, an attacker can run script code to programmatically invoke `sdk.auth.getToken()`, retrieve the scoped token, and exfiltrate it.

To neutralize this threat, our architecture implements **five distinct layers of defense** that limit the blast radius, restrict access, and can completely eliminate the token from the browser scope altogether.

```
                  ┌────────────────────────────────────────────────────────┐
                  │                 Flutter Host App Container             │
                  │  ┌──────────────────────────────────────────────────┐  │
                  │  │                  WebView Sandbox                 │  │
                  │  │                                                  │  │
                  │  │  [ XSS / Malicious Script ]                      │  │
                  │  │         │                                        │  │
                  │  │         │ (Queries JS-Bridge)                    │  │
                  │  │         ▼                                        │  │
                  │  │   sdk.auth.getToken()                            │  │
                  │  │         │                                        │  │
                  │  └─────────┼────────────────────────────────────────┘  │
                  │            │ (Intercepted & Verified)                  │
                  │            ▼                                           │
                  │  ┌──────────────────────────────────────────────────┐  │
                  │  │            Secure Flutter JS-Bridge              │  │
                  │  │  - Origin URL Verification                       │  │
                  │  │  - Content Security Policy (CSP) enforcement    │  │
                  │  └─────────┬────────────────────────────────────────┘  │
                  └────────────┼───────────────────────────────────────────┘
                               │ (Authorization Code / Backchannel Flow)
                               ▼
                  ┌────────────────────────────────────────────────────────┐
                  │              Secure Backend Infrastructure             │
                  │  ┌────────────────────────┐  ┌──────────────────────┐  │
                  │  │  Mini App Backend      │  │ Core Ingress Gateway │  │
                  │  │  - Exchanges code      │  │ - Strict Token Aud   │  │
                  │  │  - Stores JWT in memory│  │   & Scope validation │  │
                  │  └────────────────────────┘  └──────────────────────┘  │
                  └────────────────────────────────────────────────────────┘
```

#### 5.2.1 Defense Layer 1: Cryptographic Audience & Scope-Bounding (Aud / Scope)
Even if an attacker successfully extracts a scoped token via XSS, **the blast radius is fully contained**:
* **No Core Access:** The token is explicitly bounded with `aud: miniapp-<domain>-backend` and `scope: <domain>-scope`. It is **useless** if replayed against Core Team APIs (banking, profiles, wallets). The Core Ingress Gateway will instantly reject it due to audience mismatch.
* **No Cross-App Replay:** A stolen token for *Mini App A* cannot be used to call *Mini App B's* backend services. Each microservice validates its own strict client-identity bound, restricting the token's worth exclusively to the compromised domain.

#### 5.2.2 Defense Layer 2: Extreme Lifespan Ephemerality (Max 5-Minute TTL)
Unlike standard user access tokens which may last 15 to 30 minutes, guest-scoped tokens generated via `getToken()` must have a maximum Time-To-Live (TTL) of **5 minutes** (with no refresh tokens issued to the Guest context).
* **The Result:** The utility window for a stolen token is extremely narrow. An attacker must exploit and exfiltrate in real-time, drastically reducing the feasibility of persistent unauthorized backend abuse.

#### 5.2.3 Defense Layer 3: Host Origin Validation & Content Security Policy (CSP)
The Flutter Host App JS-Bridge does **not** blind-trust execution requests:
1. **Origin Verification (*Active in local POC*):** Before resolving the `getToken` promise, the Flutter host controller inspects the calling frame's origin. On Web/Chrome platforms in the local POC, this is actively enforced inside `webview_web.dart` by intercepting the `message` event and verifying that `messageEvent.origin` strictly matches the sandboxed domain (`http://localhost:5000`). If an unauthorized iframe attempts to query the OIDC bridge, the Host intercepts and drops the request instantly.
2. **CSP Injection:** The Host injects and enforces a strict Content Security Policy (CSP) that forbids inline script execution (`unsafe-inline`) and limits `connect-src` only to the Mini App's registered backend API gateways, preventing stolen data from being sent to untrusted third-party servers.

#### 5.2.4 Defense Layer 4: Ephemeral In-Memory Storage Constraint
Mini App developers are strictly prohibited from writing the scoped token to persistent client-side databases:
* **Banned:** `window.localStorage`, `window.sessionStorage`, and `IndexedDB`. If stored here, any XSS script can read the token long after the initial bridge call.
* **Mandated (*Active in local POC*):** Tokens must be stored in a **closed JavaScript execution scope (a closure)** or local component state memory. Inside our guest `index.html` POC codebase, the token is actively managed as a dynamic, ephemeral closure variable (`let activeToken = null;`), guaranteeing it is never persisted to disk and is wiped instantly on reload or WebView unload.

#### 5.2.5 Defense Layer 5: Authorization Code Handshake (Backchannel Resolution - *Zero JWT in WebView*)
For high-security domains, we implement the **API Ingress Centralized Backchannel Token Resolution Pattern**. This completely eliminates the raw JWT token from both the WebView's JavaScript context and the downstream vendor backend containers:

1. **Get Code instead of Token:** Instead of returning the raw JWT over the JS-Bridge, `sdk.auth.getToken()` returns a **single-use, short-lived, ephemeral authorization code** (`exchange_code`, valid for 30 seconds).
2. **Gateway Registration:** The Flutter Host App securely registers the mapping (`exchange_code` -> `rawJWT`) directly with the central **API Ingress Gateway** via a private backchannel endpoint: `POST /api/gateway/register-code`.
3. **Forwarding Code:** The Mini App frontend sends this `exchange_code` as a Bearer token to the API Gateway when calling its backend services (e.g., `POST /api/rewards/claim`).
4. **Pre-Security Swapping:** The API Gateway's Pre-Security Filter intercepts the request, looks up the `exchange_code` in its cache, deletes it immediately (enforcing single-use), and swaps it for the real `rawJWT` in-flight.
5. **Gateway OIDC Offloading:** The Gateway validates the swapped `rawJWT` mathematically against Keycloak JWKS, extracts user claims, injects simple trusted headers (`X-User-Id`, `X-Client-Id`, `X-User-Scopes`), and forwards the clean request downstream to the Mini App Backend.
6. **Outcome:** The raw JWT token is retrieved and stored **only in memory at the secure API Gateway edge**. The browser environment only ever sees an ephemeral exchange code, and downstream vendor backends only see simple, cryptographically-sanitized identity headers. Even a complete takeover of both the browser and the vendor's backend container cannot steal active user JWTs, completely neutralizing the primary token theft vectors.

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

### 7.1 Bundle Package Format (`.mapk`) & Industry Standards

When establishing a Mini App ecosystem, selecting a packaging format requires balancing **open web standards**, **super-app ecosystem alignment**, and **security controls**.

#### 7.1.1 Industry Landscape & Standards Mapping
In the global Mini App industry, there are two primary approaches to packaging:
1. **Proprietary/Closed Formats:** Used by major super-apps (e.g., WeChat, Alipay) to bind compiled assets into custom binary containers.
2. **Open-Standard Zip Containers:** Promoted by the W3C and phone manufacturer consortiums (e.g., Quick App) to lower friction and leverage standard web technologies.

The table below contrasts the leading industry packaging formats with our enterprise ecosystem's custom format:

| Ecosystem / Standard | Extension | Underlying Container | Specification & Characteristics |
| :--- | :---: | :---: | :--- |
| **W3C MiniApp Working Group** | `.zip` | ZIP (Standard) | Defines the open specification for packaging, requiring a standardized `manifest.json`, security controls, and resource verification. |
| **Tencent WeChat** | `.wxapkg` | Custom Binary | A proprietary compiled binary archive format containing compressed and optimized pages (WXML, WXSS, and ES5 JS). Requires proprietary tools to compile and unpack. |
| **Alipay** | `.amr` | Custom Binary | *Alipay Mini Program Resource*. A proprietary container optimized for secure sandboxing and rapid asset interception inside the Alipay Core. |
| **Quick App Consortium** | `.rpk` | ZIP (Standard) | *Resource Package*. Used by major Android manufacturers (Xiaomi, Huawei, OPPO, vivo). Combines standard HTML/CSS/JS with custom layouts into a zip archive. |
| **Enterprise Custom (This Playbook)** | **`.mapk`** | **ZIP (Standard)** | **Mini App Package**. A project-specific extension wrapping a standard ZIP container that fully aligns with the W3C MiniApp Packaging specification, enriched with local cryptographic signature validation. |

#### 7.1.2 Why the Enterprise Uses a Custom `.mapk` Extension
While a `.mapk` package is fundamentally a structured `.zip` archive, utilizing the custom **`.mapk`** extension (instead of `.zip`) is an **industry best practice** for the following critical engineering reasons:

1. **Upload Sanitation & Security Inspection:**
   By restricting the Developer Portal and CI/CD pipelines to only accept `.mapk` files, we establish a strict boundary. The ingestion pipeline immediately intercepts `.mapk` uploads and routes them to a specialized vulnerability scanner that tests for **Zip Slip (relative path traversal attacks)**, malicious dynamic code injection, and compliance with size boundaries before unpacking.
2. **MIME-Type & Edge Routing Optimization:**
   Having a dedicated extension allows Azure Front Door, Azure DNS, CDN edges, and storage buckets (e.g., Azure Blob Storage) to map `.mapk` files to a specific MIME-type (e.g., `application/x-mini-app-package`). This lets edge caches apply distinct cache-control rules, optimization headers, and download patterns separate from generic zip assets.
3. **Host Client Interception & Router Binding:**
   When the Flutter Host App downloads, verifies, and unpacks the package locally, the custom `.mapk` extension serves as a clear administrative signature. The Flutter Asset Manager can easily register and route the files within the local sandbox filesystem (e.g., mapping `my-rewards-app.mapk` dynamically to a sandboxed routing virtual host like `app://miniapp-rewards/`).
4. **Cryptographic Validation & Signature Binding:**
   The `.mapk` format acts as a sealed envelope. Future security iterations can bind a detached cryptographic signature (`manifest.sig`) into the package header or append a signature block, allowing the Flutter Host App to verify that the bundle was signed by an authorized developer and has not been tampered with since release.

#### 7.1.3 Inside the `.mapk` Structure (W3C-Aligned)
The internal structure of our `.mapk` bundle closely mirrors the W3C MiniApp Packaging directory guidelines, ensuring compatibility and ease of adaptation:

```
my-rewards-app.mapk/
├── manifest.json         # Unified configuration manifest (W3C compatible)
├── index.html            # Standard SPA entrypoint bootstrapper
├── assets/               # Production-compiled, optimized static assets
│   ├── app.js            # Main application bundle
│   ├── app.css           # Styling/design tokens stylesheet
│   └── logo.png          # App brand image asset
└── locales/              # Localized translation resource mappings
    ├── en.json           # English localization strings
    └── id.json           # Bahasa Indonesia localization strings
```

##### Standard W3C-Aligned `manifest.json` Example:
```json
{
  "appId": "com.vendor.miniapp.loyaltyrewards",
  "name": "Loyalty Rewards",
  "version": "1.2.4",
  "versionCode": 24,
  "description": "Redeem your loyalty points seamlessly in the Host App.",
  "icons": [
    {
      "src": "assets/logo.png",
      "sizes": "144x144",
      "type": "image/png"
    }
  ],
  "permissions": [
    "jsbridge.auth.scopedToken",
    "jsbridge.device.getBiometricsStatus",
    "jsbridge.navigation.close"
  ],
  "window": {
    "defaultTitle": "Loyalty Rewards",
    "navigationBarBackgroundColor": "#0F172A",
    "navigationBarTextStyle": "white"
  }
}
```

---

### 7.2 Secure Ingestion, Storage & Global Distribution (Azure Blob Storage & Azure Front Door)

To safely manage and deliver `.mapk` bundles to millions of Host app instances, the architecture relies on a locked-down **Private Azure Blob Storage** origin acting as a package registry, fronted by **Azure Front Door (AFD)** as a highly secure, cached global Edge CDN.

```
┌─────────────────┐      1. Push .mapk      ┌─────────────────────────────┐
│  Developer CI   ├────────────────────────>│ Secure Ingestion Gateway    │
│  (GitHub/DevOps)│                         │ (Integrity & Scan Pipeline)  │
└─────────────────┘                         └──────────────┬──────────────┘
                                                           │ 2. Upload (Private)
                                                           ▼
┌─────────────────┐      4. Dynamic Cache   ┌─────────────────────────────┐
│  Flutter Host   │<────────────────────────┤  Azure Front Door (CDN)     │
│  (Mobile App)   │                         │  - Edge Cache (Geo-routing) │
└─────────────────┘                         │  - WAF / Origin Lockdown    │
                                            └──────────────▲──────────────┘
                                                           │ 3. Authenticate Origin
                                                           │ (Managed Identity / SAS)
                                            ┌──────────────┴──────────────┐
                                            │  Azure Blob Storage         │
                                            │  (Private Container:        │
                                            │   miniapp-registry-prod)    │
                                            └─────────────────────────────┘
```

#### 7.2.1 Phase 1: Ingestion & Verification Pipeline
Before a `.mapk` archive is stored in Azure Blob Storage, the CI/CD pipeline or a dedicated Developer Portal Ingestion service must perform mandatory static analysis and security checks:
1. **Zip Slip Prevention (Path Traversal):** Verify that the archive does not contain relative path components (`../` or `..\\`) in resource paths that could escape the local extraction directory on user devices.
2. **Size Enforcement:** Reject packages larger than the **2MB** compressed limit.
3. **Manifest Integrity:** Parse `manifest.json` at the root and assert required fields (`appId`, `version`, `versionCode`, `permissions`).
4. **Signature Verification (Optional but Recommended):** Verify the developer's cryptographic signature against registry-trusted public keys.

#### 7.2.2 Phase 2: Private Azure Blob Storage Provisioning
We host verified `.mapk` files inside a **Private Azure Blob Storage container**. Direct public access to the storage account must be disabled to prevent security bypassing.

1. **Storage Account Setup:**
   * Create an Azure Storage Account with **BlobOnly** kind and **Locally Redundant Storage (LRS)** or **Geo-Redundant Storage (GRS)** for high availability.
   * Enforce **Secure Transfer Required (HTTPS)**.
2. **Container Configuration:**
   * Create a container named `miniapp-registry-prod`.
   * Set Public Access Level to **Private (no anonymous access)**.
3. **Origin Restriction:**
   * Configure Azure Storage Firewalls to restrict incoming traffic. 
   * Allow access exclusively from the **Azure Front Door Service Tag** (`AzureFrontDoor.Backend`) or bind it via a **Private Endpoint** using Azure Private Link.

#### 7.2.3 Phase 3: Azure Front Door (Edge CDN) Configuration
Azure Front Door functions as the secure gateway that intercepts client requests, applies Web Application Firewalls (WAF), decrypts SSL/TLS, and delivers cached bundles with ultra-low latency.

1. **Custom MIME-Type Association:**
   * Ensure that `.mapk` files are served with the header: `Content-Type: application/x-mini-app-package` (or fallback `application/octet-stream`).
   * This is critical! If served as `text/html` or `application/x-zip-compressed`, default browsers might attempt to parse or execute scripting under the CDN domain, presenting an XSS attack vector.
2. **Backend/Origin Settings:**
   * Define your Private Storage Account blob endpoint (e.g. `https://<account-name>.blob.core.windows.net`) as the primary origin.
   * Authenticate Azure Front Door to Azure Storage using **System-Assigned Managed Identity** or a timed Shared Access Signature (SAS) token configured inside the origin path.
3. **Caching & Compression Rules:**
   * Set **Query String Caching** to *Ignore Query Strings* since bundles are immutable by version pathing.
   * Enable **Compression** on the edge (Gzip and Brotli) to optimize network packet overhead for users on mobile networks.
   * Enable **Edge Cache Time-To-Live (TTL)** aggressively (e.g., 30 days) because `.mapk` versions are static and versioned in their URLs (e.g., `/bundles/rewards-app/v1.2.4/rewards-app.mapk`).

#### 7.2.4 Phase 4: Automated CI/CD Deployment Pipeline (GitHub Actions)
Below is a production-ready GitHub Actions workflow template demonstrating how to package, authenticate, upload with a custom MIME type to Azure Blob Storage, and immediately purge the Front Door cache edges.

```yaml
name: Deploy Mini App Frontend to Azure

on:
  push:
    tags:
      - 'v*'  # Trigger on version tags (e.g. v1.2.4)

permissions:
  id-token: write  # Required for Azure OIDC Federated Login
  contents: read

jobs:
  deploy:
    runs-on: ubuntu-latest
    env:
      STORAGE_ACCOUNT: "stminiappregprod"
      CONTAINER_NAME: "miniapp-registry-prod"
      FRONT_DOOR_PROFILE: "afd-core-prod"
      FRONT_DOOR_ENDPOINT: "fde-miniapp-prod"

    steps:
    - name: Checkout Source Code
      uses: actions/checkout@v4

    - name: Setup Node.js (Frontend Build)
      uses: actions/setup-node@v4
      with:
        node-version: 20
        cache: 'npm'

    - name: Install & Compile Assets
      run: |
        npm ci
        npm run build # Outputs to ./dist directory

    - name: Parse Version from Tag
      id: get_version
      run: echo "VERSION=${GITHUB_REF#refs/tags/v}" >> $GITHUB_OUTPUT

    - name: Package Bundle (.mapk ZIP format)
      run: |
        cd dist
        # Zip all standard W3C assets, including root manifest.json
        zip -r ../my-rewards-app.mapk .
        cd ..

    - name: Log in to Azure (OIDC Identity Federation)
      uses: azure/login@v2
      with:
        client-id: ${{ secrets.AZURE_CLIENT_ID }}
        tenant-id: ${{ secrets.AZURE_TENANT_ID }}
        subscription-id: ${{ secrets.AZURE_SUBSCRIPTION_ID }}

    - name: Upload .mapk Bundle to Private Azure Blob Storage
      run: |
        # Construct versioned destination path
        DEST_BLOB="bundles/rewards-app/${{ steps.get_version.outputs.VERSION }}/rewards-app.mapk"
        
        echo "Uploading to Azure Storage..."
        az storage blob upload \
          --account-name ${{ env.STORAGE_ACCOUNT }} \
          --container-name ${{ env.CONTAINER_NAME }} \
          --name "$DEST_BLOB" \
          --file "my-rewards-app.mapk" \
          --content-type "application/x-mini-app-package" \
          --overwrite true \
          --auth-mode login

    - name: Purge Azure Front Door CDN Cache Edge
      run: |
        # Purge edge cache specifically for the rewards app path to force reload
        echo "Purging Front Door edge caches..."
        az afd endpoint purge \
          --resource-group rg-core-infra-prod \
          --profile-name ${{ env.FRONT_DOOR_PROFILE }} \
          --endpoint-name ${{ env.FRONT_DOOR_ENDPOINT }} \
          --domains "cdn.coreapp.com" \
          --content-paths "/bundles/rewards-app/*"
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

### 10.1.1 Spring Cloud Gateway Reference Implementation

For enterprise teams deploying **Spring Cloud Gateway** (e.g. in Azure Kubernetes Service or Azure Spring Apps), we provide below the complete, production-ready implementation of the **API Ingress Token Swap and Session Caching Engine**. 

This single class implements:
1. **Stateless Ephemeral Code Registration Endpoint** (`POST /api/gateway/register-code`).
2. **Pre-Security Swapping Filter** (`TokenSwappingWebFilter`) running before Spring Security to swap `code_guest_xxxx` for raw OIDC JWTs with an active 30-second TTL.
3. **WebSession Cookie Handler** to statefully cache and resolve tokens in a secure browser cookie context (Pattern A).
4. **Decoupled Security Chain & Custom Routing** forwarding clean headers downstream to microservices.

```java
package com.core.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;

@SpringBootApplication
public class CoreGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreGatewayApplication.class, args);
    }

    @Value("${CORE_BE_URI:http://core-be:8082}")
    private String coreBackendUri;

    @Value("${MINI_APP_BE_URI:http://mini-app-be:8081}")
    private String miniAppBackendUri;

    @Value("${KEYCLOAK_URI:http://keycloak:8080}")
    private String keycloakUri;

    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
        TokenPropagationFilter coreBeFilter = new TokenPropagationFilter(true); // Strips JWT
        TokenPropagationFilter miniAppBeFilter = new TokenPropagationFilter(false); // Keeps JWT

        return builder.routes()
                .route("core-be-route", r -> r.path("/api/wallet/**")
                        .filters(f -> f.filter(coreBeFilter))
                        .uri(coreBackendUri))
                .route("mini-app-be-route", r -> r.path("/api/rewards/**")
                        .filters(f -> f.filter(miniAppBeFilter))
                        .uri(miniAppBackendUri))
                .route("keycloak-token-route", r -> r.path("/realms/production/protocol/openid-connect/token")
                        .uri(keycloakUri))
                .build();
    }
}

// 1. Gateway Security Configuration: Decoupled JWT Token Offloading & Cross-Origin Credentials
@Configuration
@EnableWebFluxSecurity
class SecurityConfig {

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.disable())
            .authorizeExchange(exchanges -> exchanges
                .pathMatchers("/api/wallet/**", "/api/rewards/claim").authenticated()
                .anyExchange().permitAll()
            )
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}));
        return http.build();
    }

    @Bean
    public org.springframework.web.cors.reactive.CorsConfigurationSource corsConfigurationSource() {
        org.springframework.web.cors.CorsConfiguration configuration = new org.springframework.web.cors.CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("http://localhost:*", "http://127.0.0.1:*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Cookie"));
        configuration.setAllowCredentials(true);
        
        org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource source = 
            new org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}

// 2. Gateway Controller: Stateless Ephemeral Code Ingestion Endpoint
@RestController
@CrossOrigin(origins = "*", allowCredentials = "false")
class GatewayCodeRegistrationController {

    public static class CodeDetails {
        final String token;
        final long expiryTime;
        CodeDetails(String token, long expiryTime) {
            this.token = token;
            this.expiryTime = expiryTime;
        }
    }

    public static final Map<String, CodeDetails> tempCodeCache = new java.util.concurrent.ConcurrentHashMap<>();

    @PostMapping("/api/gateway/register-code")
    public Mono<ResponseEntity<?>> registerCode(@RequestBody Map<String, String> body) {
        String code = body.get("code");
        String token = body.get("token");
        
        if (code == null || token == null) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of("error", "Invalid payload")));
        }
        
        // Cache code with 30 seconds TTL (Layer 5)
        tempCodeCache.put(code, new CodeDetails(token, System.currentTimeMillis() + 30000));
        return Mono.just(ResponseEntity.ok(Map.of("status", "code_registered")));
    }
}

// 3. Gateway Pre-Security WebFilter: Intercepts & Resolves Ephemeral Codes / WebSession Cookies
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class TokenSwappingWebFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
        
        // Case 1: Ephemeral Code Swapping (Pattern B-1 / B-2)
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            
            if (token.startsWith("code_guest_")) {
                GatewayCodeRegistrationController.CodeDetails details = 
                    GatewayCodeRegistrationController.tempCodeCache.remove(token); // Single-use!
                
                if (details != null) {
                    if (System.currentTimeMillis() > details.expiryTime) {
                        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                        return exchange.getResponse().setComplete();
                    }
                    
                    final String realJwt = details.token;
                    
                    // Bind JWT to WebSession for subsequent stateful operations (Pattern A)
                    return exchange.getSession().flatMap(session -> {
                        session.getAttributes().put("SCOPED_TOKEN", realJwt);
                        
                        ServerWebExchange mutatedExchange = exchange.mutate()
                            .request(r -> r.header("Authorization", "Bearer " + realJwt))
                            .build();
                        return chain.filter(mutatedExchange);
                    });
                } else {
                    exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                    return exchange.getResponse().setComplete();
                }
            }
        }
        
        // Case 2: Stateful Cookie Sessions (Pattern A)
        if (authHeader == null || authHeader.trim().isEmpty()) {
            return exchange.getSession().flatMap(session -> {
                String cachedJwt = (String) session.getAttribute("SCOPED_TOKEN");
                if (cachedJwt != null) {
                    ServerWebExchange mutatedExchange = exchange.mutate()
                        .request(r -> r.header("Authorization", "Bearer " + cachedJwt))
                        .build();
                    return chain.filter(mutatedExchange);
                }
                return chain.filter(exchange);
            }).switchIfEmpty(chain.filter(exchange));
        }
        
        return chain.filter(exchange);
    }
}

// 4. Gateway Filter: Secure User Identity Propagation Pattern
class TokenPropagationFilter implements GatewayFilter {

    private final boolean stripAuthorization;

    public TokenPropagationFilter(boolean stripAuthorization) {
        this.stripAuthorization = stripAuthorization;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, org.springframework.cloud.gateway.filter.GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
            .map(SecurityContext::getAuthentication)
            .flatMap(authentication -> {
                if (authentication != null && authentication.getPrincipal() instanceof Jwt) {
                    Jwt jwt = (Jwt) authentication.getPrincipal();
                    
                    String userId = jwt.getSubject();
                    String clientId = jwt.getClaimAsString("azp");
                    String scopes = jwt.getClaimAsString("scope");

                    ServerWebExchange mutatedExchange = exchange.mutate()
                        .request(r -> {
                            r.header("X-User-Id", userId != null ? userId : "");
                            r.header("X-Client-Id", clientId != null ? clientId : "");
                            r.header("X-User-Scopes", scopes != null ? scopes : "");
                            
                            if (stripAuthorization) {
                                r.headers(headers -> headers.remove("Authorization"));
                            }
                        })
                        .build();
                    
                    return chain.filter(mutatedExchange);
                }
                return chain.filter(exchange);
            })
            .switchIfEmpty(chain.filter(exchange));
    }
}
```

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

> [!IMPORTANT]
> **Separation of Scope-Down and Token-Exchange Grants**
> To minimize configuration overhead and strictly isolate vendor trust boundaries, our architecture enforces a clear separation between client-side and server-side token delegation:
> * **Client-Side Scope-Down (For all models, including Model 1 & 2):** The Flutter Host App requests client-scoped access tokens (e.g., `loyalty-scope`, `insurance-scope`) for WebView sandboxes using the standard **OAuth 2.0 Refresh Token Grant (`grant_type=refresh_token`)**. This is a standard OIDC capability and requires **zero security policies** or client credentials inside Keycloak.
> * **Server-Side Core Delegation (Strictly for Vendor Model 2 & 3 backends):** The vendor backend exchanges the scoped token for a delegated core JWT using the **OAuth 2.0 Token Exchange (`grant_type=token-exchange` / RFC 8693)** to transact with Core APIs. This is the only flow that requires Keycloak's Token Exchange feature flag and fine-grained cross-client authorization policy configurations.

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

### 11.2 Flow 1: Host App to Guest Mini App (Client Scoped Token with Ephemeral Ingress Swap)

To prevent the guest app from reading the raw access tokens, we implement the **Layer 5 Ephemeral In-Memory Swap** at the API Ingress Gateway. The Guest Mini App's JS context only ever receives a single-use exchange code (`code_guest_xxxx`), which the Gateway swaps in-flight for the genuine scoped JWT before forwarding downstream.

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant MiniApp as Guest Mini App (JS)
    participant Host as Flutter Host App (Dart)
    participant Gateway as API Ingress Gateway
    participant Keycloak as Keycloak Server
    participant Backend as Mini App Backend

    User->>MiniApp: Launch / Request Token
    MiniApp->>Host: JS-Bridge Request: sdk.auth.getToken()
    activate Host
    Host->>Keycloak: Standard Refresh Token Scope Down (scope=loyalty-scope)
    Keycloak-->>Host: Return Scoped JWT Token
    Host->>Host: Generate Ephemeral Code (code_guest_xxxx, 30s TTL)
    Host->>Gateway: Backchannel POST /api/gateway/register-code (code -> JWT)
    Gateway-->>Host: 200 OK (Registered in Cache)
    Host-->>MiniApp: Resolve JS Promise with code_guest_xxxx
    deactivate Host

    User->>MiniApp: Trigger Action (e.g., Claim Reward)
    MiniApp->>Gateway: POST /api/rewards/claim (Bearer: code_guest_xxxx)
    activate Gateway
    Gateway->>Gateway: TokenSwappingWebFilter intercepts & swaps code_guest_xxxx for Scoped JWT
    Gateway->>Gateway: Bind Scoped JWT to WebSession (if Pattern A)
    Gateway->>Gateway: Perform OAuth2 JWT validation against Keycloak JWKS
    Gateway->>Backend: Forward request with Scoped JWT in Authorization Header
    activate Backend
    Backend-->>Gateway: Process & Return Response
    deactivate Backend
    Gateway-->>MiniApp: Proxy Response
    deactivate Gateway
```

#### Keycloak Configuration Steps for Scoped Tokens:
1. Create a **Client Scope** in Keycloak named `loyalty-scope`.
2. Attach an **Audience Protocol Mapper** inside `loyalty-scope`:
   * **Name:** `loyalty-audience-mapper`
   * **Included Client Audience:** `mini-app-loyalty-rewards`
3. Associate this Client Scope with the `flutter-host-app` client as **Optional** (or **Default**).
4. When the user logs in, the host app obtains its master access token and master refresh token. When a Mini App boots up and calls `getToken()`, the Flutter Host App executes a standard OIDC refresh token scope-down request to Keycloak specifying `scope=loyalty-scope`, obtaining a JWT containing:
   ```json
   {
     "iss": "https://keycloak.yourdomain.com/realms/production",
     "sub": "user_9841284",
     "aud": "mini-app-loyalty-rewards",
     "scope": "loyalty-scope",
     "azp": "flutter-host-app"
   }
   ```
5. Instead of passing this raw JWT to the WebView context, the Host App registers the JWT against an ephemeral code (`code_guest_xxxx`) on the Ingress Gateway, and resolves the WebView promise with that code.

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

### 11.4 Detailed Mechanics: The client-Side "Resolve Token Promise" Handshake with Ephemeral Swap

In the Client-Side sequence diagram (**Flow 1**), the Guest Mini App's JavaScript execution waits for a **"Resolve Token Promise"**. 

```
 Guest WebView (JS Container)                       Host Mobile App (Flutter Core)                      API Ingress Gateway
 ────────────────────────────                       ──────────────────────────────                      ───────────────────
              │                                                    │                                             │
 1. const code = await sdk.getToken();                             │                                             │
    [Generates req_16849, returns Pending Promise]                 │                                             │
              │── 2. window.flutter.postMessage(req_16849) ───────>│                                             │
              │                                                    │ [Executes Refresh Scope Down]               │
              │                                                    │ [Retrieves Scoped Micro-JWT]                │
              │                                                    │ [Generates code_guest_xxxx]                 │
              │                                                    │                                             │
              │                                                    │── 3. POST /register-code ──────────────────>│
              │                                                    │      (code_guest_xxxx -> Scoped JWT)        │
              │                                                    │<── 4. 200 OK (Registered in Gateway)────────│
              │                                                    │                                             │
              │<── 5. evaluateJavascript / postMessage ────────────│                                             │
              │    (Resolve req_16849 with code_guest_xxxx)        │                                             │
 6. window._hostAppCallbacks['req_16849'].resolve(code)            │                                             │
    [Fulfills Promise! Code resumes with ephemeral code]           │                                             │
              ▼                                                    │                                             │
```

This asynchronous handshake bridges the memory-isolated boundary between Javascript running inside the OS WebView and the native Dart VM, while securely preventing the raw JWT from ever reaching the guest context:

#### A. Guest JS SDK Mechanism (Javascript Inside WebView)
To prevent locking the UI thread, the Guest JS SDK wraps the JS-Bridge call in a native **Javascript Promise**. It registers the Promise's native `resolve` and `reject` callbacks into a globally scoped registry map inside the WebView window context before sending the message:

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
          const payload = JSON.stringify({
            miniAppId: "com.vendor.loyalty-rewards",
            requestId: requestId,
            action: "auth.getToken",
            params: { scopes: scopes }
          });
          
          if (window.flutter_inappwebview) {
            window.flutter_inappwebview.callHandler('JSBridgeChannel', payload);
          } else if (window.parent !== window) {
            window.parent.postMessage(payload, "*");
          } else {
            throw new Error("WebView Host context not found.");
          }
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
When the Flutter Host App completes the Keycloak authentication exchange and registers the generated exchange code with the Gateway, **the Host App resolves the Javascript Promise** by dispatching the result back to the WebView:

```dart
// Part of lib/main.dart
onMessageReceived: (rawJson) async {
  final Map<String, dynamic> request = jsonDecode(rawJson);
  final String requestId = request['requestId'];
  final String action = request['action'];
  
  if (action == "auth.getToken") {
    try {
      final scopes = request['params']['scopes'] ?? [];
      
      // 1. Execute standard OIDC scope down via Refresh Token against Keycloak
      final String scopedToken = await _executeKeycloakScopeDown(scopes);
      
      // 2. Generate dynamic, short-lived ephemeral exchange code (30s TTL)
      final int randomId = DateTime.now().millisecondsSinceEpoch % 1000000;
      final String tempCode = "code_guest_$randomId";
      
      // 3. Securely register the code to the Central Gateway over the backchannel
      final String registerUrl = "http://localhost:9000/api/gateway/register-code";
      final regResponse = await http.post(
        Uri.parse(registerUrl),
        headers: {"Content-Type": "application/json"},
        body: jsonEncode({
          "code": tempCode,
          "token": scopedToken,
        }),
      );
      
      if (regResponse.statusCode != 200) {
        throw Exception("Gateway rejected code registration.");
      }
      
      // 4. Return the ephemeral exchange code instead of the raw JWT to the WebView context
      _webViewController?.postMessage(jsonEncode({
        'requestId': requestId,
        'status': 'success',
        'token': tempCode,
      }));
      
    } catch (e) {
      _webViewController?.postMessage(jsonEncode({
        'requestId': requestId,
        'status': 'error',
        'error': e.toString(),
      }));
    }
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

### 12.3 Local Ports Mapping & Services Reference

| Service Name | Port | Description |
| :--- | :--- | :--- |
| **`keycloak`** | `8080` | Keycloak 24 IAM Server running standard OIDC / RFC 8693 endpoints |
| **`core-gateway`** | `9000` | Spring Cloud Edge Ingress Gateway doing JWKS validation & Header Propagation |
| **`mini-app-be`** | `8081` | Spring Boot Mini App Backend executing basic-auth token exchange |
| **`core-be`** | `8082` | Spring Boot Core Backend doing wallet actions (deduct/credit) via trusted headers |
| **`guest-webapp`** | `5000` | Mock CDN hosting "Loyalty Rewards" (`/index.html`) & "Insurance Points" (`/points.html`) |
| **`flutter-host`** | `5000+` | Flutter Host Mobile App Shell with dynamic sandbox toggle controls |

---

## 13. Local POC Walkthrough & Architectural Verification

This section documents the end-to-end local POC implementation that fully de-risked and validated our core architectural hypotheses, specifically focusing on cross-origin iframe security, OIDC Scope Delegation (RFC 8693), and Zero-Trust Header Propagation.

### 13.1 Key Technical Resolutions

During the local POC implementation, three critical integration hurdles were successfully solved:

1. **Stateful Platform WebView in Flutter Web (`StatefulWidget`)**:
   In Flutter Web, using a stateless wrapper around `HtmlElementView` causes the dynamic `viewType` and `IFrameElement` to recreate on every state change (e.g. logging telemetry). This destroys the Guest App's active JS context and reloads the page.
   * **Resolution**: The web view is encapsulated inside a `StatefulWidget` where the `IFrameElement` is instantiated exactly once in `initState()`. Telemetry logs are safely deferred via `WidgetsBinding.instance.addPostFrameCallback` to prevent `setState() during build` runtime exceptions.
2. **Dynamic Issuer URL Matching in Keycloak (`KC_HOSTNAME`)**:
   Keycloak checks the `iss` claim strictly. In a dual-environment (external browser at `localhost:8080` vs internal container at `keycloak:8080`), Keycloak dynamically stamps different issuers based on the Host header, causing the backend token exchange to fail with `invalid_token`.
   * **Resolution**: Forced Keycloak to resolve a fixed hostname URL via `KC_HOSTNAME: localhost`, aligning both external and internal token scopes to the identical issuer URL: `http://localhost:8080/realms/production`.
3. **Scope Transference during RFC 8693 Token Exchange (`defaultClientScopes`)**:
   Keycloak V1 token exchange is highly conservative and strips optional scopes during transacting exchanges unless complex delegation rules are declared.
   * **Resolution**: Reconfigured the realm mappings so that `loyalty-scope` is defined as a **Default Client Scope** (`defaultClientScopes`) on both `flutter-host-app` and `core-wallet-service` clients, ensuring Keycloak implicitly stamps and propagates the active scopes down to the final microservices.

### 13.2 Architectural Verification Sequence

To verify the ecosystem on your local machine, run the following verification sequence:

#### 1. Boot up the Ecosystem Stack
Run the commands to pull, compile, and launch all services in detached mode:
```powershell
cd local-mini-app-poc
docker compose down
docker compose build mini-app-be
docker compose up -d --remove-orphans
```

#### 2. Re-apply Keycloak Fine-Grained Permissions
* Log in to the Keycloak Admin Console at `http://localhost:8080` (admin / adminpassword).
* Under the **`production`** realm, navigate to **`Clients`** -> click **`core-wallet-service`** -> open **`Permissions`** tab.
* Toggle **`Permissions Enabled`** to **`ON`** and click **`Save`**.
* Click the blue **`token-exchange`** link in the permissions list table.
* Click **`Create policy`** -> select **`Client`**.
  * **Name**: `allow-loyalty-rewards`
  * **Clients**: Select **`mini-app-loyalty-rewards`**
* Click **`Save`** at the bottom.
* Go back to the **`token-exchange`** permission page, select **`allow-loyalty-rewards`** in the **`Policies`** field, and click **`Save`**.

#### 3. Compile and Run the Flutter Host App on Chrome
Navigate to the Flutter Host App directory and compile for web:
```powershell
cd flutter-host
flutter create --platforms=web .
flutter run -d chrome
```

#### 4. The Live Execution Handshake & Demo Modes

Our verified codebase includes an interactive **Demo Strategy Selector** dropdown inside the Loyalty Rewards Mini App UI. This lets you showcase the security tradeoffs and token resolution handshakes live to your colleagues without changing code.

Below are the sequence diagrams and validation steps for each of the three active demo modes:

##### Mode 1: Pattern B-1: Strict One-Time Code (Manual Replay Demo - *Default*)
In this mode, the Gateway's `TokenSwappingWebFilter` intercepts `/api/rewards/claim` with `code_guest_xxxx`, checks its cache, swaps it, and *deletes* it immediately. A subsequent replay attempt fails at the Ingress Gateway.

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant MiniApp as Guest Mini App (JS)
    participant Host as Flutter Host App (Dart)
    participant Gateway as API Ingress Gateway
    participant Backend as Mini App Backend (Spring)

    User->>MiniApp: Click "1. Request Scoped Token..."
    MiniApp->>Host: JS-Bridge Request: auth.getToken()
    activate Host
    Host->>Host: Request Keycloak Token & Generate code_guest_xxxx
    Host->>Gateway: Backchannel POST /api/gateway/register-code (code -> JWT)
    Gateway-->>Host: 200 OK (Registered in Cache)
    Host-->>MiniApp: Resolve with code_guest_xxxx
    deactivate Host
    
    User->>MiniApp: Click "2. Claim Premium..."
    MiniApp->>Gateway: POST /api/rewards/claim (Authorization: Bearer code_guest_xxxx)
    activate Gateway
    Gateway->>Gateway: Swapping filter intercepts, retrieves JWT & deletes code from Cache
    Gateway->>Backend: Forward POST /claim with real JWT in Authorization header
    activate Backend
    Backend->>Backend: Execute Keycloak Exchange & Wallet Deduct
    Backend-->>Gateway: 200 Success Response
    deactivate Backend
    Gateway-->>MiniApp: 200 Success
    deactivate Gateway
    
    User->>MiniApp: Click "2. Claim Premium..." again (Replay Attempt)
    MiniApp->>Gateway: POST /api/rewards/claim (Authorization: Bearer code_guest_xxxx)
    activate Gateway
    Gateway->>Gateway: Filter checks Cache for code_guest_xxxx -> NOT FOUND!
    Gateway-->>MiniApp: 401 Unauthorized (Already consumed / invalid)
    deactivate Gateway
```

1. Click **`1. Request Scoped Token...`** to retrieve a temporary single-use code (`code_guest_xxxx`).
2. Click **`2. Claim Premium...`** to execute the backchannel swap and deduct points successfully.
3. Click **`2. Claim Premium...`** a second time.
4. **Expected Result:** The request immediately fails with:
   `Claim Failed: Unauthorized`
   *This proves to your team that codes cannot be replayed or hijacked by attackers once consumed.*

##### Mode 2: Pattern B-2: Stateless SDK Silent Refresh (Seamless SDK Flow)
This mode simulates a professional SDK implementation where client-side statelessness is maintained silently.

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant MiniApp as Guest Mini App (JS)
    participant Host as Flutter Host App (Dart)
    participant Gateway as API Ingress Gateway
    participant Backend as Mini App Backend (Spring)

    Note over MiniApp, Backend: First Transaction
    User->>MiniApp: Click "2. Claim Premium..."
    activate MiniApp
    MiniApp->>Host: JS-Bridge Request: auth.getToken()
    activate Host
    Host->>Gateway: Backchannel POST /api/gateway/register-code (code_guest_1 -> JWT)
    Gateway-->>Host: 200 OK (Registered)
    Host-->>MiniApp: Resolve with code_guest_1
    deactivate Host
    MiniApp->>Gateway: POST /api/rewards/claim (Authorization: Bearer code_guest_1)
    activate Gateway
    Gateway->>Gateway: Consume & swap code_guest_1 -> JWT
    Gateway->>Backend: Forward claims with JWT
    activate Backend
    Backend-->>Gateway: 200 Success Response
    deactivate Backend
    Gateway-->>MiniApp: 200 Success
    deactivate Gateway
    deactivate MiniApp

    Note over MiniApp, Backend: Second Transaction (Silent Refresh)
    User->>MiniApp: Click "2. Claim Premium..." again
    activate MiniApp
    MiniApp->>Host: JS-Bridge Request: auth.getToken()
    activate Host
    Host->>Gateway: Backchannel POST /api/gateway/register-code (code_guest_2 -> JWT)
    Gateway-->>Host: 200 OK (Registered)
    Host-->>MiniApp: Resolve with code_guest_2
    deactivate Host
    MiniApp->>Gateway: POST /api/rewards/claim (Authorization: Bearer code_guest_2)
    activate Gateway
    Gateway->>Gateway: Consume & swap code_guest_2 -> JWT
    Gateway->>Backend: Forward claims with JWT
    activate Backend
    Backend-->>Gateway: 200 Success Response
    deactivate Backend
    Gateway-->>MiniApp: 200 Success
    deactivate Gateway
    deactivate MiniApp
```

1. Select **`Pattern B-2: Stateless SDK Silent Refresh`** in the dropdown.
2. Click **`2. Claim Premium...`** multiple times consecutively.
3. **Expected Result:** Every transaction completes successfully! 
   *Under the hood, the Guest JS SDK silently requests a fresh ephemeral code from the Host JS-Bridge before dispatching each API call, demonstrating seamless stateless execution.*

##### Mode 3: Pattern A: Stateful Session Cookies (Seamless Backend State)
This mode demonstrates standard server-side web session caching using secure, cross-origin cookies.

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant MiniApp as Guest Mini App (JS)
    participant Host as Flutter Host App (Dart)
    participant Gateway as API Ingress Gateway
    participant Backend as Mini App Backend (Spring)

    Note over MiniApp, Backend: Session Establishment (First Call)
    User->>MiniApp: Click "1. Request Scoped Token..."
    MiniApp->>Host: JS-Bridge Request: auth.getToken()
    activate Host
    Host->>Gateway: Backchannel POST /api/gateway/register-code (code_guest_xxxx -> JWT)
    Gateway-->>Host: 200 OK (Registered)
    Host-->>MiniApp: Resolve with code_guest_xxxx
    deactivate Host
    
    User->>MiniApp: Click "2. Claim Premium..."
    MiniApp->>Gateway: POST /api/rewards/claim (Authorization: Bearer code_guest_xxxx)
    activate Gateway
    Gateway->>Gateway: Consume code_guest_xxxx, resolve JWT
    Gateway->>Gateway: Cache JWT in WebSession & Generate Session Cookie
    Gateway->>Backend: Forward claims with JWT
    activate Backend
    Backend-->>Gateway: 200 Success Response
    deactivate Backend
    Gateway-->>MiniApp: 200 Success + Set-Cookie (SESSION)
    deactivate Gateway
    
    Note over MiniApp, Backend: Session Reuse (Subsequent Calls)
    User->>MiniApp: Click "2. Claim Premium..." again
    MiniApp->>Gateway: POST /api/rewards/claim (Cookie: SESSION)
    activate Gateway
    Gateway->>Gateway: Resolve JWT directly from WebSession Cache!
    Gateway->>Backend: Forward claims with JWT
    activate Backend
    Backend-->>Gateway: 200 Success Response
    deactivate Backend
    Gateway-->>MiniApp: 200 Success Response
    deactivate Gateway
```

1. Select **`Pattern A: Stateful Session Cookies`** in the dropdown.
2. Click **`1. Request Scoped Token...`** and then **`2. Claim Premium...`** once to establish the session.
3. Click **`2. Claim Premium...`** multiple times consecutively.
4. **Expected Result:** Every subsequent click succeeds seamlessly without invoking the Host App's JS-Bridge again!
   *During the first exchange, the Ingress Gateway caches the resolved JWT inside the server-side `WebSession` and returns a secure cookie. Subsequent calls automatically transmit this cookie, and the Gateway resolves the token statefully.*

###### Mode 4: Model 1 Scope-Down Flow (Direct Core API - "Insurance Points")
This mode demonstrates the **Model 1 (Core Team Hosting)** architecture. The frontend uses standard OIDC refresh scope-down to request the `insurance-scope` token, and calls the Core Backend directly via the Gateway `/api/wallet/credit` endpoint—with **no server-side token exchange**.

```mermaid
sequenceDiagram
    autonumber
    actor User
    participant MiniApp as Guest Mini App (JS)
    participant Host as Flutter Host App (Dart)
    participant Gateway as API Ingress Gateway
    participant Backend as Core Backend (Spring)

    User->>MiniApp: Click "1. Request Scoped Token..."
    MiniApp->>Host: JS-Bridge Request: auth.getToken()
    activate Host
    Host->>Host: Standard Refresh Token Scope Down (scope=insurance-scope)
    Host->>Gateway: Backchannel POST /api/gateway/register-code (code -> JWT)
    Gateway-->>Host: 200 OK (Registered)
    Host-->>MiniApp: Resolve with code_guest_xxxx
    deactivate Host
    
    User->>MiniApp: Click "2. Credit 200 Wallet Points..."
    MiniApp->>Gateway: POST /api/wallet/credit (Authorization: Bearer code_guest_xxxx)
    activate Gateway
    Gateway->>Gateway: Swapping filter intercepts, retrieves JWT & deletes code
    Gateway->>Gateway: Validate JWT & Propagate trusted headers
    Gateway->>Backend: Forward POST /credit with X-User-Id, X-User-Scopes headers
    activate Backend
    Backend->>Backend: Verify insurance-scope & credit points
    Backend-->>Gateway: 200 Success Response
    deactivate Backend
    Gateway-->>MiniApp: 200 Success Response
    deactivate Gateway
```

1. Select **`2. Insurance Points (Model 1 - Direct Core)`** in the Flutter Host App's top dropdown to navigate the WebView to `points.html`.
2. Click **`1. Request Scoped Token...`** to fetch the scoped exchange code.
3. Click **`2. Credit 200 Wallet Points (Direct Core API)`**.
4. **Expected Result:** Points are credited successfully! In `docker logs -f poc-core-be`, you will see:
   ```
   ====== CORE AKS BACKEND RECEIVED CREDIT CALL ======
   X-User-Id    (User UUID)     : 48da6d26-bacb-44e5-bca7-231b1438c919
   X-Client-Id  (Calling Client): flutter-host-app
   X-User-Scopes(Active Scopes) : insurance-scope
   ==========================================
   ```
   *This proves that the Model 1 app directly invoked the Core Backend via header propagation, without any Keycloak token exchange backend execution.*

---

This successfully validates that our central architectural goals—**strict isolation, cryptographic offloading, dynamic scope delegation, and header propagation**—are fully realized and production-ready!

---

## 14. Enterprise Keycloak Onboarding & Configuration Guide

To scale your ecosystem and onboard multiple internal or third-party Mini Apps, your Identity and Access Management (IAM) team must follow a standardized, secure process in Keycloak. This guide provides the complete administrative playbook for onboarding a new Mini App, establishing OIDC scope constraints, and configuring token exchange permissions.

### 14.1 Keycloak Client Topology Blueprint

For every Mini App you onboard, you must define its operational role in Keycloak. We categorize clients into three distinct OIDC profiles:

1. **Confidential Backend Client (`mini-app-[name]-be`)**:
   * **Purpose**: Represents the Mini App’s secure server-side container.
   * **Client Authentication**: Enabled (Confidential).
   * **Authorization**: Enabled.
   * **Service Accounts**: Enabled (Required for backend-to-backend calls).
2. **Public Mobile Client Scope (`flutter-host-app`)**:
   * **Purpose**: Exists as a single client representing your host shell. It requests scoped tokens on behalf of the user when a Mini App is launched.
3. **Core API Resource Client (`core-[service]-service`)**:
   * **Purpose**: Represents the core AKS microservice API cluster (e.g. `core-wallet-service`) that the Mini App wants to transact with.

---

### 14.2 Step-by-Step Mini App Onboarding Guide

Follow these five administrative steps to onboard a new Mini App named **`mini-app-insurance`** which needs to transact with the core backend service **`core-wallet-service`**.

#### Step 1: Register the Mini App Backend Client
1. Log in to the Keycloak Admin Console. Select your designated **Ecosystem Realm** (e.g. `production`).
2. Go to **Clients** in the left sidebar and click **Create client**.
3. Configure the General Settings:
   * **Client type**: `OpenID Connect`
   * **Client ID**: `mini-app-insurance-rewards`
   * **Name**: `Insurance Loyalty Rewards Backend`
   * Click **Next**.
4. Configure the Capability Settings (Crucial for Backends):
   * **Client authentication**: Toggle **ON** (Confidential).
   * **Authorization**: Toggle **ON**.
   * **Authentication flow**: Check **Service accounts roles** and **Direct access grants**.
   * Click **Save**.
5. Retrieve the Client Secret:
   * Navigate to the **Credentials** tab.
   * Copy the generated **Client Secret** (e.g., `insurance-secret-uuid`). Hand this over securely to the Mini App development team.

#### Step 2: Define and Map the Dedicated Client Scope
Creating a dedicated scope prevents a compromised Mini App from accessing resources belonging to other Mini Apps.

1. Go to **Client scopes** in the left sidebar and click **Create client scope**.
2. Configure the Scope Details:
   * **Name**: `insurance-scope`
   * **Type**: `Default` (To ensure automatic propagation during token exchange).
   * **Protocol**: `OpenID Connect`
   * Click **Save**.
3. Configure the Audience Mapper (Ties the scope securely to the Mini App Backend):
   * Go to the **Mappers** tab in the `insurance-scope` page.
   * Click **Configure a new mapper** $\rightarrow$ select **Audience**.
   * **Name**: `insurance-audience-mapper`
   * **Included client audience**: `mini-app-insurance-rewards`
   * **Add to access token**: Toggle **ON**.
   * Click **Save**.
4. Associate Scope with the Core API Service:
   * Go to **Clients** $\rightarrow$ click **`core-wallet-service`**.
   * Click the **Client scopes** tab.
   * Click **Add client scope** $\rightarrow$ select **`insurance-scope`** $\rightarrow$ click **Add** $\rightarrow$ select **Default** (highly recommended to prevent Keycloak stripping it during token exchange).
5. Associate Scope with the Flutter Host App:
   * Go to **Clients** $\rightarrow$ click **`flutter-host-app`**.
   * Click the **Client scopes** tab.
   * Click **Add client scope** $\rightarrow$ select **`insurance-scope`** $\rightarrow$ click **Add** $\rightarrow$ select **Default** (or **Optional** if you want to require user consent screen prompts during bridge startup).

#### Step 3: Enable Target Client Permissions (Fine-Grained Admin Auth)
By default, Keycloak blocks cross-client token exchanges. We must enable management permissions on the target microservice to allow delegation.

1. Go to **Clients** $\rightarrow$ click on the **target client** (e.g., **`core-wallet-service`**).
2. Navigate to the **Permissions** tab.
3. Toggle **Permissions Enabled** to **ON** and click **Save**.
4. You will see a list of auto-generated permission scopes in the permissions table (e.g. `token-exchange`, `map-roles`).

#### Step 4: Create the Client Exchange Policy
We define an explicit authorization policy stating that *only* the new Mini App is authorized to exchange tokens targeting the core API.

1. In the **Permissions** tab table of `core-wallet-service`, click the blue **`token-exchange`** link.
2. Under the **Policies** section, click the **Create policy** dropdown $\rightarrow$ select **Client**.
3. Configure the Policy:
   * **Name**: `allow-insurance-exchange`
   * **Description**: `Allow insurance mini-app backend to exchange tokens for core-wallet.`
   * **Clients**: Select **`mini-app-insurance-rewards`** from the search dropdown.
   * Click **Save**.

#### Step 5: Bind Policy to the Token Exchange Permission
1. Navigate back to the **`token-exchange`** permission page (or click **Clients** $\rightarrow$ **`core-wallet-service`** $\rightarrow$ **Permissions** $\rightarrow$ **`token-exchange`**).
2. In the **Policies** selection box, select your newly created **`allow-insurance-exchange`** client policy.
3. Click **Save**.

---

### 14.3 Developer Handover Matrix

Once onboarding is complete, the Core Platform Team hands over the following configuration values to the Mini App Vendor Team to establish their environment connectivity:

| Parameter | Enterprise Value | Description |
| :--- | :--- | :--- |
| **`Client ID`** | `mini-app-insurance-rewards` | The unique OIDC client ID identifying the vendor backend |
| **`Client Secret`** | `[Generated UUID]` | Confidential secret used to authenticate the Token Exchange call |
| **`Token Endpoint`** | `https://gateway.company.com/realms/production/protocol/openid-connect/token` | The public Core API Gateway route proxying Keycloak |
| **`Core Service Audience`** | `core-wallet-service` | The target audience to supply in the token exchange parameter |
| **`Required Scope`** | `insurance-scope` | The OAuth scope required to transact with the core backend APIs |

---

### 14.4 Production Scale & Maintenance Best Practices

1. **Strict mTLS for Backchannel Communication**:
   In production, secure the connection between the Mini App Backend and Keycloak (Step 3 in token exchange) using Mutual TLS (mTLS) with client certificates rather than plain client secrets. Keycloak supports this natively via **Signed JWT Client Authentication (private_key_jwt)**.
2. **Rotate Client Secrets Automatically**:
   Configure a secret rotation lifecycle (e.g., every 90 days) utilizing **Azure Key Vault** to automatically update the client secrets in Keycloak via the Admin REST API and sync them to the vendor's container deployment environments.
3. **Rotate Client Secrets Automatically**:
   Enable Keycloak’s **Audit Event Listeners** (`LoginEventListener` and `AdminEventListener`) to log every token exchange request (`TOKEN_EXCHANGE` event type). Feed these logs into **Azure Monitor / Log Analytics** to monitor for unusual exchange volumes or access patterns, providing early detection of hijacked micro-tokens.

---

## 15. Enterprise AKS Cluster Isolation & Organization Blueprint

When hosting multiple first-party and third-party Mini App Backends within the Core Team's **Azure Kubernetes Service (AKS)** infrastructure, you must enforce strict logical, compute, and network separation. By default, Kubernetes allows open pod-to-pod communication across all namespaces. This blueprint details the security patterns required to organize these backends and shield your critical Core microservices from potential compromise.

### 15.1 Logical Namespace Segmentation & RBAC

Every Mini App must reside inside its own isolated Kubernetes Namespace. Do **NOT** deploy Mini App backends into the default namespace or the Core Team's namespace.

```
       Core Team Namespace: "core-system"
       ┌────────────────────────────────────────────────────────┐
       │ ┌───────────────────┐             ┌──────────────────┐ │
       │ │ core-wallet-pod   │             │ core-profile-pod │ │
       │ └───────────────────┘             └──────────────────┘ │
       └────────────────────────────────────────────────────────┘
                               ▲
                               │ (Strict RBAC & Network Isolation)
                               ▼
       Mini App Namespace: "miniapp-loyalty"
       ┌────────────────────────────────────────────────────────┐
       │ ┌───────────────────────┐                              │
       │ │ loyalty-backend-pod   │                              │
       │ └───────────────────────┘                              │
       └────────────────────────────────────────────────────────┘
```

1. **Namespace Naming Convention**:
   * Core Team services: `core-system` or `core-services`
   * Gateway and IAM: `ingress-gateway`
   * Onboarded Mini Apps: `miniapp-[name]` (e.g. `miniapp-loyalty`, `miniapp-insurance`).
2. **Kubernetes RBAC (Role-Based Access Control)**:
   * Define granular `Role` and `RoleBinding` resources. 
   * **The Rule**: Developers from the Loyalty department are only granted access to the `miniapp-loyalty` namespace. They are strictly blocked from listing, viewing logs, or executing shells inside pods running in `core-system` or `ingress-gateway`.

---

### 15.2 Network Micro-Segmentation (Strict NetworkPolicies)

By default, any pod inside `miniapp-loyalty` can bypass the API Gateway and call Core microservices directly via internal Core DNS (`http://core-wallet-service.core-system.svc.cluster.local`). We enforce a **Zero-Trust Network Segmentation** policy.

1. **Global Default Deny-All Policy**:
   Apply a `DefaultDeny` NetworkPolicy to all Mini App namespaces. This shuts down all incoming and outgoing pod communication by default.
2. **Allow Only Gateway Ingress**:
   Configure the Mini App pods to *only* accept incoming traffic originating from your API Gateway (e.g. KrakenD or NGINX Ingress) sitting in the `ingress-gateway` namespace:
   ```yaml
   apiVersion: networking.k8s.io/v1
   kind: NetworkPolicy
   metadata:
     name: allow-ingress-only
     namespace: miniapp-loyalty
   spec:
     podSelector: {} # Applies to all pods in this namespace
     ingress:
     - from:
       - namespaceSelector:
           matchLabels:
             kubernetes.io/metadata.name: ingress-gateway # Only allow gateway
   ```
3. **Block Direct Core Microservice Egress**:
   Ensure that no pod inside `miniapp-loyalty` can establish a direct network connection to `core-system`. If the Loyalty backend needs to call the Core Wallet service, it **must** route its call outbound through the public Core API Gateway, triggering full edge WAF inspection and Keycloak token checks!

---

### 15.3 Compute & Resource Isolation (Dedicated Node Pools)

To prevent a resource leak (e.g. a JVM out-of-memory or infinite loop) in a vendor's Mini App from starving your critical Core bank or payment services, enforce physical compute boundaries using **Azure Node Pools**.

1. **Dedicated User Node Pools**:
   * **System Node Pool** (`systempool`): Reserved exclusively for Core Kubernetes system services, KrakenD, and Core microservices.
   * **Mini App Node Pool** (`miniapppool`): Dedicated virtual machines running only guest Mini App backends.
2. **Kubernetes Taints and Tolerations**:
   Apply a taint to the Core nodes so that guest apps cannot be scheduled on them:
   ```bash
   kubectl taint nodes [core-node-name] tier=core:NoSchedule
   ```
   Add the matching toleration *only* to the deployment manifests of your Core microservices.
3. **Strict Resource Quotas**:
   Enforce strict CPU and memory `limits` on every Mini App namespace to prevent noisy-neighbor scenarios:
   ```yaml
   apiVersion: v1
   kind: ResourceQuota
   metadata:
     name: namespace-limits
     namespace: miniapp-loyalty
   spec:
     hard:
       requests.cpu: "2"
       requests.memory: 4Gi
       limits.cpu: "4"
       limits.memory: 8Gi
   ```

---

### 15.4 Secrets & Cloud Resources Isolation (Azure Workload Identity)

Do **NOT** share database connection strings, storage keys, or API secrets across teams.

1. **Azure Workload Identity**:
   * Bind each Mini App namespace's Kubernetes `ServiceAccount` to a separate **Azure User-Assigned Managed Identity**.
   * The Loyalty backend pod runs using its own Managed Identity, which is only granted access to its own specific Azure SQL Database and Azure Blob Container. It has **no permission** to read Core database resources.
2. **Namespace Key Vault Access**:
   * Use **Azure Key Vault Secrets Provider**. 
   * Each Mini App namespace mounts secrets from its own dedicated Key Vault instance. Under no circumstances should a Mini App pod be able to access the Core Team's production Key Vault.

---

## 16. Repository Architecture & Standard Naming Conventions

To prevent architectural drift, ensure ease of integration, and simplify multi-vendor management in Azure DevOps or GitHub, all Mini App frontends and backends must conform to a standardized repository architecture and naming taxonomy.

### 16.1 Mini App Frontend Repository Architecture

Frontend mini-apps are built as lightweight Single Page Applications (SPAs) optimized for local sandboxing. They must be structured to build into a clean distribution directory (`/dist`) before packaging into the `.mapk` archive.

#### 16.1.1 Frontend Directory Structure
Every frontend project repository must follow this layout:

```
miniapp-<domain>-frontend/
├── package.json               # Package configuration & dependencies
├── tsconfig.json              # TypeScript compilation rules
├── vite.config.ts             # Compilation and bundler configuration
├── /public                    # Static assets copied as-is to the bundle root
│   ├── manifest.json          # W3C-aligned Mini App configuration manifest
│   └── logo.png               # High-res logo (144x144 png)
├── /src                       # Application source code
│   ├── main.ts                # Application bootstrap entrypoint
│   ├── App.vue                # Root layout component
│   ├── /assets                # Sub-assets (styles, SVGs, optimized images)
│   ├── /components            # Reusable UI components conforming to design tokens
│   ├── /views                 # Page-level route views
│   ├── /locales               # i18n translation bundles
│   │   ├── en.json
│   │   └── id.json
│   └── /services              # External API & JS-Bridge bindings
│       ├── jsbridge.ts        # JS-Bridge event listener & wrappers
│       └── api.ts             # HTTP client (Axios/Fetch) with token injection
└── /scripts                   # CI/CD and deployment utilities
    └── package-mapk.js        # Automated script to zip /dist into .mapk
```

#### 16.1.2 Automated `.mapk` Packaging Script (`package-mapk.js`)
To guarantee that the zipped bundle does not contain redundant build tools, source directories, or parent folders (avoiding decompression hierarchy failures inside the Host app), the repository must include a build-packaging script. 

Add the following Node.js script under `/scripts/package-mapk.js` to run post-build:

```javascript
const fs = require('fs');
const path = require('path');
const archiver = require('archiver');

const outputDir = path.join(__dirname, '../');
const sourceDir = path.join(__dirname, '../dist');
const manifestPath = path.join(sourceDir, 'manifest.json');

// Assert W3C manifest.json exists in compiled output
if (!fs.existsSync(manifestPath)) {
  console.error("ERROR: manifest.json is missing from compile target (/dist). Build failed.");
  process.exit(1);
}

const packageJson = require('../package.json');
const zipFileName = `${packageJson.name}-${packageJson.version}.mapk`;
const output = fs.createWriteStream(path.join(outputDir, zipFileName));
const archive = archiver('zip', { zlib: { level: 9 } });

output.on('close', () => {
  console.log(`\nSUCCESS: Packaged Mini App successfully!`);
  console.log(`Bundle: ${zipFileName}`);
  console.log(`Total Size: ${(archive.pointer() / 1024 / 1024).toFixed(2)} MB`);
});

archive.on('error', (err) => {
  throw err;
});

archive.pipe(output);
// Append dist folder contents directly to root of the ZIP
archive.directory(sourceDir, false);
archive.finalize();
```

Execute this in `package.json` scripts:
```json
"scripts": {
  "build": "vite build",
  "postbuild": "node scripts/package-mapk.js"
}
```

---

### 16.2 Mini App Backend Repository Blueprint & Naming Conventions

While downstream backends adhere to existing Core microservice coding guidelines, they must conform to strict naming boundaries to ensure namespace cleanliness, seamless Azure AKS routing, and clear Keycloak identity management.

#### 16.2.1 Unified Repository Naming Rules
Standardizing repository names prevents organizational sprawl and identifies ownership instantly:

| Project Origin | Repository Type | Naming Template | Example Repository Name |
| :--- | :--- | :--- | :--- |
| **Vendor / Internal Teams** | Frontend | `miniapp-<domain>-frontend` | `miniapp-loyaltyrewards-frontend` |
| **Vendor / Internal Teams** | Backend | `miniapp-<domain>-backend` | `miniapp-loyaltyrewards-backend` |
| **Core Dev Teams** | Frontend | `core-miniapp-<domain>-frontend` | `core-miniapp-wallet-frontend` |
| **Core Dev Teams** | Backend | `core-miniapp-<domain>-backend` | `core-miniapp-wallet-backend` |

*Note: `<domain>` must be in all-lowercase, alphanumeric characters only (no hyphens in domain if possible, or single-hyphen to ensure clean database/namespace mapping).*

#### 16.2.2 Infrastructure & Container Naming Conventions
To keep AKS, Azure Container Registry (ACR), and Kubernetes network configurations clean:

1. **Docker Container Image Tagging:**
   * **Template:** `<registry-name>.azurecr.io/miniapps/miniapp-<domain>-backend:<tag>`
   * **Example:** `stregminiappprod.azurecr.io/miniapps/miniapp-loyaltyrewards-backend:v1.2.4`
2. **Kubernetes Resources (Deployments, Services):**
   * **Namespace:** `miniapp-<domain>` (e.g., `miniapp-loyaltyrewards`)
   * **Deployment:** `miniapp-<domain>-be-deploy` (e.g., `miniapp-loyaltyrewards-be-deploy`)
   * **Service:** `miniapp-<domain>-be-svc` (e.g., `miniapp-loyaltyrewards-be-svc`)

#### 16.2.3 Database & Isolated Schema Naming
To maintain data sovereignty boundaries:
* **Database Instance (Dedicated):** `db_miniapp_<domain>`
* **Database Schema (Standard SQL Schema):** `<domain>` (instead of `dbo` or `public` to prevent accidental resource sharing)
* **Example:**
  * Database Name: `db_miniapp_loyaltyrewards`
  * Schema Name: `loyaltyrewards`

#### 16.2.4 Keycloak Identity Naming Conventions
For OIDC client routing and token exchanging validation:
* **Frontend Scoped Client ID:** `miniapp-<domain>-client` (e.g., `miniapp-loyaltyrewards-client`)
* **Backend Confidential Client ID:** `miniapp-<domain>-backend` (e.g., `miniapp-loyaltyrewards-backend`)

#### 16.2.5 Edge API Gateway Mapping & URL Naming
All endpoints exposed externally through the API Gateway (KrakenD/Kong) must be prefixed dynamically under a centralized API route to isolate Core microservices:

* **Public Facing Gateway Path:** 
  `https://api.coreapp.com/api/v1/miniapps/<domain>/{route}`
* **Private Internal Cluster Path:**
  `http://miniapp-<domain>-be-svc.miniapp-<domain>.svc.cluster.local:8081/api/v1/{route}`
* **Example Mapping:**
  * Client Request: `GET https://api.coreapp.com/api/v1/miniapps/loyaltyrewards/offers`
  * Gateway routes to: `GET http://miniapp-loyaltyrewards-be-svc.miniapp-loyaltyrewards.svc.cluster.local:8081/api/v1/offers`




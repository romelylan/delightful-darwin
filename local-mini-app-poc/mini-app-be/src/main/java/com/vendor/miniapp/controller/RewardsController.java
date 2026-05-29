package com.vendor.miniapp.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

@RestController
@RequestMapping("/api/rewards")
@CrossOrigin(origins = "*") // Allow WebView cross-origin requests
public class RewardsController {

    private final RestTemplate restTemplate;

    @Value("${KEYCLOAK_TOKEN_URL:http://localhost:8080/realms/production/protocol/openid-connect/token}")
    private String keycloakTokenUrl;

    @Value("${CORE_GATEWAY_URL:http://localhost:9000}")
    private String coreGatewayUrl;

    public RewardsController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @PostMapping("/claim")
    public ResponseEntity<?> claimReward(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader,
            @RequestBody Map<String, Object> payload) {

        System.out.println("====== MINI APP VENDOR BACKEND RECEIVED CALL ======");
        System.out.println("Authorization Header: " + (authorizationHeader != null ? "PRESENT" : "MISSING"));
        System.out.println("==================================================");

        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "Unauthorized", "message", "Missing or invalid Bearer micro-token."));
        }

        String microToken = authorizationHeader.substring(7);

        try {
            // ==========================================
            // STEP 1: KEYCLOAK TOKEN EXCHANGE (RFC 8693)
            // ==========================================
            System.out.println("Executing Keycloak Token Exchange...");
            
            HttpHeaders exchangeHeaders = new HttpHeaders();
            exchangeHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            
            // Authenticate the Mini App Backend client using basic auth
            String clientCredentials = "mini-app-loyalty-rewards:loyalty-secret";
            String base64Credentials = Base64.getEncoder().encodeToString(clientCredentials.getBytes(StandardCharsets.UTF_8));
            exchangeHeaders.set("Authorization", "Basic " + base64Credentials);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("grant_type", "urn:ietf:params:oauth:grant-type:token-exchange");
            body.add("subject_token", microToken);
            body.add("subject_token_type", "urn:ietf:params:oauth:token-type:access_token");
            body.add("audience", "core-wallet-service"); // Audience maps to core-be auth requirements
            body.add("scope", "loyalty-scope"); // Request delegation of the loyalty scope

            HttpEntity<MultiValueMap<String, String>> exchangeRequest = new HttpEntity<>(body, exchangeHeaders);

            ResponseEntity<Map> exchangeResponse = restTemplate.postForEntity(keycloakTokenUrl, exchangeRequest, Map.class);
            
            if (exchangeResponse.getBody() == null || !exchangeResponse.getBody().containsKey("access_token")) {
                throw new IllegalStateException("Keycloak token exchange did not return an access token.");
            }

            String delegatedCoreToken = (String) exchangeResponse.getBody().get("access_token");
            System.out.println("Token Exchange Succeeded! Acquired Delegated Core JWT.");

            // ==========================================
            // STEP 2: CALL CORE AKS MICROSERVICE API
            // ==========================================
            System.out.println("Forwarding transaction to Core API Gateway...");

            HttpHeaders coreHeaders = new HttpHeaders();
            coreHeaders.setContentType(MediaType.APPLICATION_JSON);
            coreHeaders.set("Authorization", "Bearer " + delegatedCoreToken); // Inject Core JWT

            // Deducting 100 points for the claimed reward
            Map<String, Object> coreBody = Map.of("amount", 100);
            HttpEntity<Map<String, Object>> coreRequest = new HttpEntity<>(coreBody, coreHeaders);

            String coreApiUrl = coreGatewayUrl + "/api/wallet/deduct";
            ResponseEntity<Map> coreResponse = restTemplate.postForEntity(coreApiUrl, coreRequest, Map.class);

            System.out.println("Core Wallet Transaction completed! Points deducted.");

            // Return consolidated response to the guest Mini App
            return ResponseEntity.ok(Map.of(
                "status", "REWARD_CLAIMED_SUCCESSFULLY",
                "rewardItem", payload.getOrDefault("rewardName", "Mystery Gift"),
                "coreWalletResult", coreResponse.getBody()
            ));

        } catch (HttpClientErrorException e) {
            System.err.println("API error: " + e.getResponseBodyAsString());
            return ResponseEntity.status(e.getStatusCode())
                    .body(Map.of("error", "API_ERROR", "details", e.getResponseBodyAsString()));
        } catch (Exception e) {
            System.err.println("Internal processor error: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "PROCESSOR_ERROR", "details", e.getMessage()));
        }
    }
}

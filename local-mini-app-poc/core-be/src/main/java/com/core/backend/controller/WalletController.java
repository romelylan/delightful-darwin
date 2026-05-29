package com.core.backend.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/wallet")
public class WalletController {

    private final Map<String, Integer> userBalances = new HashMap<>();

    public WalletController() {
        // Mock default user balance for our test user in Keycloak
        // Keycloak uses UUIDs, but we will seed a default or populate on-demand
        userBalances.put("testuser", 5000);
    }

    @PostMapping("/deduct")
    public ResponseEntity<?> deductPoints(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Client-Id", required = false) String clientId,
            @RequestHeader(value = "X-User-Scopes", required = false) String scopes,
            @RequestBody Map<String, Object> payload) {

        System.out.println("====== CORE AKS BACKEND RECEIVED CALL ======");
        System.out.println("X-User-Id    (User UUID)     : " + userId);
        System.out.println("X-Client-Id  (Calling Client): " + clientId);
        System.out.println("X-User-Scopes(Active Scopes) : " + scopes);
        System.out.println("==========================================");

        // 1. Guard check: Enforce that request came through Gateway (offloaded safety)
        if (userId == null || userId.isEmpty() || clientId == null || clientId.isEmpty()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Forbidden: Direct pod access without gateway header verification is blocked.");
        }

        // 2. Scope verification (Zero crypt overhead)
        if (scopes == null || !scopes.contains("loyalty-scope")) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Forbidden: Missing loyalty-scope required to transact.");
        }

        // 3. Process business logic (Simulated Wallet Deduction)
        int amountToDeduct = (int) payload.getOrDefault("amount", 0);
        int currentBalance = userBalances.getOrDefault(userId, 1000); // Seed 1000 if new

        if (currentBalance < amountToDeduct) {
            return ResponseEntity.badRequest().body("Insufficient funds. Current balance: " + currentBalance);
        }

        int newBalance = currentBalance - amountToDeduct;
        userBalances.put(userId, newBalance);

        return ResponseEntity.ok(Map.of(
            "status", "SUCCESS",
            "userId", userId,
            "transactingClient", clientId,
            "deductedAmount", amountToDeduct,
            "remainingBalance", newBalance,
            "message", "Wallet points deducted successfully."
        ));
    }
}

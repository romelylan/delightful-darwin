import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'webview.dart' as universal_webview;

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Ecosystem Host Shell',
      theme: ThemeData(
        brightness: Brightness.dark,
        primaryColor: const Color(0xFF0F172A),
        scaffoldBackgroundColor: const Color(0xFF020617),
        useMaterial3: true,
      ),
      home: const HostShellScreen(),
    );
  }
}

class HostShellScreen extends StatefulWidget {
  const HostShellScreen({super.key});

  @override
  State<HostShellScreen> createState() => _HostShellScreenState();
}

class _HostShellScreenState extends State<HostShellScreen> {
  universal_webview.WebViewController? _webViewController;
  String _masterAccessToken = "Awaiting Login...";
  String _masterRefreshToken = "";
  String _currentMiniAppUrl = "http://localhost:5000";
  String _telemetryLogs = "[Telemetry Core] Booting Host App Shell...\n";
  bool _isLoggedIn = false;

  // Local Keycloak configurations (Docker mapped ports)
  // For Windows/Chrome, use localhost. For Android emulator, use 10.0.2.2.
  final String keycloakBaseUrl = "http://localhost:8080/realms/production/protocol/openid-connect";

  @override
  void initState() {
    super.initState();
    // Automatically login on boot to simulate active user session
    _performMockUserLogin();
  }

  void _logTelemetry(String message) {
    setState(() {
      _telemetryLogs += "[${DateTime.now().toString().substring(11, 19)}] $message\n";
    });
  }

  // 1. Simulates native login of user inside Host App using standard Keycloak flow
  Future<void> _performMockUserLogin() async {
    _logTelemetry("Logging in standard user: 'testuser' into Keycloak Production Realm...");
    try {
      final response = await http.post(
        Uri.parse("$keycloakBaseUrl/token"),
        headers: {"Content-Type": "application/x-www-form-urlencoded"},
        body: {
          "grant_type": "password",
          "client_id": "flutter-host-app",
          "username": "testuser",
          "password": "password",
        },
      );

      if (response.statusCode == 200) {
        final Map<String, dynamic> data = jsonDecode(response.body);
        setState(() {
          _masterAccessToken = data['access_token'];
          _masterRefreshToken = data['refresh_token'] ?? "";
          _isLoggedIn = true;
        });
        _logTelemetry("User logged in successfully! Master JWT & Refresh Token obtained.");
      } else {
        _logTelemetry("Login Failed: Status ${response.statusCode} - ${response.body}");
      }
    } catch (e) {
      _logTelemetry("Connection Error: Could not connect to Keycloak at $keycloakBaseUrl. Ensure docker containers are running!");
    }
  }

  // 2. Perform standard OAuth 2.0 Scope Down using Refresh Token on behalf of Guest Mini App
  Future<String> _executeKeycloakScopeDown(List<dynamic> scopes) async {
    if (!_isLoggedIn) {
      throw Exception("User is not authenticated in Host App.");
    }

    _logTelemetry("Bridge request: Initiating standard OIDC scope refresh scope down for ${scopes.join(", ")}...");

    final response = await http.post(
      Uri.parse("$keycloakBaseUrl/token"),
      headers: {"Content-Type": "application/x-www-form-urlencoded"},
      body: {
        "grant_type": "refresh_token",
        "client_id": "flutter-host-app",
        "refresh_token": _masterRefreshToken,
        "scope": scopes.join(" "),
      },
    );

    if (response.statusCode == 200) {
      final Map<String, dynamic> data = jsonDecode(response.body);
      return data['access_token']; // Returns Scoped Micro-JWT
    } else {
      throw Exception("Scope down rejected: Status ${response.statusCode} - ${response.body}");
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Core Host Mobile App Shell', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
        backgroundColor: const Color(0xFF0F172A),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: () {
              _webViewController?.reload();
              _logTelemetry("Webview container reloaded.");
            },
          )
        ],
      ),
      body: Column(
        children: [
          // Native Host App Telemetry and Controls (Top Half)
          Container(
            height: 220,
            color: const Color(0xFF0F172A),
            padding: const EdgeInsets.all(12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    Text(
                      _isLoggedIn ? "User: testuser (Authenticated)" : "User: Authenticating...",
                      style: TextStyle(
                        fontWeight: FontWeight.bold,
                        color: _isLoggedIn ? Colors.greenAccent : Colors.amberAccent,
                        fontSize: 13,
                      ),
                    ),
                    const Text("Master VNet ID: keycloak:8080", style: TextStyle(color: Colors.white38, fontSize: 11)),
                  ],
                ),
                const SizedBox(height: 6),
                Row(
                  mainAxisAlignment: MainAxisAlignment.spaceBetween,
                  children: [
                    const Text(
                      "Active Mini App Sandbox:",
                      style: TextStyle(fontWeight: FontWeight.bold, color: Colors.blueAccent, fontSize: 12),
                    ),
                    DropdownButton<String>(
                      value: _currentMiniAppUrl,
                      dropdownColor: const Color(0xFF0F172A),
                      underline: Container(),
                      isDense: true,
                      style: const TextStyle(fontSize: 11, color: Colors.white, fontWeight: FontWeight.bold),
                      items: const [
                        DropdownMenuItem(
                          value: "http://localhost:5000",
                          child: Text("1. Loyalty Rewards (Model 2/3 - Exchange)"),
                        ),
                        DropdownMenuItem(
                          value: "http://localhost:5000/points.html",
                          child: Text("2. Insurance Points (Model 1 - Direct Core)"),
                        ),
                      ],
                      onChanged: (String? newUrl) {
                        if (newUrl != null) {
                          setState(() {
                            _currentMiniAppUrl = newUrl;
                          });
                          _logTelemetry("Navigating WebView to: $newUrl");
                        }
                      },
                    ),
                  ],
                ),
                const SizedBox(height: 6),
                const Text(
                  "Platform Telemetry Logs:",
                  style: TextStyle(fontWeight: FontWeight.bold, color: Colors.blueAccent, fontSize: 12),
                ),
                const SizedBox(height: 4),
                Expanded(
                  child: Container(
                    width: double.infinity,
                    padding: const EdgeInsets.all(8),
                    decoration: BoxDecoration(
                      color: const Color(0xFF020617),
                      border: Border.all(color: Colors.white10),
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: SingleChildScrollView(
                      child: Text(
                        _telemetryLogs,
                        style: const TextStyle(fontFamily: 'monospace', fontSize: 10, color: Colors.greenAccent),
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
          
          // Isolated Web Sandbox Container (Bottom Half)
          Expanded(
            child: Container(
              decoration: const BoxDecoration(
                border: Border(top: BorderSide(color: Colors.white10, width: 2)),
              ),
              child: universal_webview.createWebView(
                initialUrl: _currentMiniAppUrl,
                onMessageReceived: (rawJson) async {
                  final Map<String, dynamic> request = jsonDecode(rawJson);
                  final String requestId = request['requestId'];
                  final String action = request['action'];
                  
                  _logTelemetry("Inbound Bridge request: '$action' [ID: $requestId]");

                  if (action == "auth.getToken") {
                    try {
                      final scopes = request['params']['scopes'] ?? [];
                      
                      // 1. Execute standard OIDC scope down via Refresh Token against Keycloak
                      final String scopedToken = await _executeKeycloakScopeDown(scopes);
                      
                      // 2. Generate dynamic, short-lived ephemeral exchange code (Layer 5)
                      final int randomId = DateTime.now().millisecondsSinceEpoch % 1000000;
                      final String tempCode = "code_guest_$randomId";
                      _logTelemetry("Token acquired. Registering secure temp code: $tempCode");
                      
                      // 3. Securely register the code to the Mini App Backend over the backchannel
                      try {
                        final String registerUrl = "http://localhost:9000/api/gateway/register-code";
                        final regResponse = await http.post(
                          Uri.parse(registerUrl),
                          headers: {"Content-Type": "application/json"},
                          body: jsonEncode({
                            "code": tempCode,
                            "token": scopedToken,
                          }),
                        );
                        
                        if (regResponse.statusCode == 200) {
                          _logTelemetry("Code registered to Backend. Dispatching code to Webview...");
                        } else {
                          throw Exception("Backend rejected registration: Status ${regResponse.statusCode}");
                        }
                      } catch (err) {
                        _logTelemetry("Backchannel registration error: ${err.toString()}");
                        throw Exception("Failed to secure temp code on backend.");
                      }
                      
                      // 4. Return the ephemeral code instead of the raw JWT to the WebView context
                      _webViewController?.postMessage(jsonEncode({
                        'requestId': requestId,
                        'status': 'success',
                        'token': tempCode,
                      }));
                      
                    } catch (e) {
                      _logTelemetry("Handshake failed: ${e.toString()}");
                      _webViewController?.postMessage(jsonEncode({
                        'requestId': requestId,
                        'status': 'error',
                        'error': e.toString(),
                      }));
                    }
                  }
                },
                onWebViewCreated: (controller) {
                  _webViewController = controller;
                  _logTelemetry("Injecting Secure JavaScript Bridge SDK channel...");
                },
              ),
            ),
          ),
        ],
      ),
    );
  }
}

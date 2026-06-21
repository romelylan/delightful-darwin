// -------------------------------------------------------------
// HOST APP JS BRIDGE SDK
// Shared bridge infrastructure for Mini App ↔ Host App communication.
// Each page initializes with: const hostAppSdk = createHostAppSdk({ miniAppId: "..." });
// -------------------------------------------------------------
(function (global) {
  'use strict';

  // Pending promise callback registry (keyed by requestId)
  global._hostAppCallbacks = {};

  // ---------------------------------------------------------------
  // Console Logging Utility
  // ---------------------------------------------------------------
  function logConsole(message, type) {
    type = type || "normal";
    var consoleEl = document.getElementById("consoleOutput");
    if (!consoleEl) return;

    var line = document.createElement("div");
    line.className = "console-line";

    if (type === "success") line.classList.add("success-text");
    if (type === "error") line.classList.add("error-text");
    if (type === "system") line.classList.add("system-text");

    var time = new Date().toLocaleTimeString();
    line.innerText = "[" + time + "] " + message;
    consoleEl.appendChild(line);
    consoleEl.scrollTop = consoleEl.scrollHeight;
  }

  // ---------------------------------------------------------------
  // SDK Factory
  // ---------------------------------------------------------------
  function createHostAppSdk(config) {
    if (!config || !config.miniAppId) {
      throw new Error("createHostAppSdk requires config.miniAppId");
    }

    function requestScopedBootstrapToken(scopes) {
      return new Promise(function (resolve, reject) {
        var requestId = "req_" + Date.now() + "_" + Math.floor(Math.random() * 1000);

        global._hostAppCallbacks[requestId] = {
          resolve: function (token) {
            logConsole("Promise Resolved successfully for ID: " + requestId, "success");
            resolve(token);
          },
          reject: function (err) {
            logConsole("Promise Rejected for ID: " + requestId + " - Error: " + err, "error");
            reject(err);
          }
        };

        logConsole("Generating pending JS Promise [" + requestId + "] for scopes: [" + scopes.join(', ') + "]", "system");

        try {
          var payload = JSON.stringify({
            miniAppId: config.miniAppId,
            requestId: requestId,
            action: "auth.getToken",
            params: { scopes: scopes }
          });

          if (global.flutter_inappwebview) {
            logConsole("Sending postMessage payload to Flutter Host App (Mobile)...", "system");
            global.flutter_inappwebview.callHandler('JSBridgeChannel', payload);
          } else if (global.parent !== global) {
            logConsole("Sending postMessage payload to Parent Window (Web iFrame)...", "system");
            global.parent.postMessage(payload, "*");
          } else {
            throw new Error("Webview Host context not found. Are you running this inside the Flutter Host App?");
          }
        } catch (e) {
          delete global._hostAppCallbacks[requestId];
          reject(e.message);
        }
      });
    }

    return {
      auth: {
        /**
         * Request a scoped token from the Host App via the JS-Bridge.
         * Returns a Promise that resolves with the token (or ephemeral code).
         */
        getToken: function (scopes) {
          return requestScopedBootstrapToken(scopes);
        },

        bootstrapSession: function (scopes, gatewayBaseUrl) {
          var baseUrl = gatewayBaseUrl || global.__MINI_APP_GATEWAY_URL__ || "http://localhost:9000";

          return requestScopedBootstrapToken(scopes).then(function (token) {
            logConsole("One-time Spring Gateway bootstrap token acquired. Establishing BFF session...", "system");

            return fetch(baseUrl + "/api/gateway/bootstrap-session", {
              method: "POST",
              headers: {
                "Authorization": "Bearer " + token,
                "Content-Type": "application/json"
              },
              credentials: "include",
              body: JSON.stringify({ bootstrap: true })
            }).then(function (response) {
              return response.json().catch(function () {
                return {};
              }).then(function (data) {
                if (!response.ok) {
                  throw new Error(data.error || ("Gateway bootstrap failed with status " + response.status));
                }

                logConsole("Spring Gateway BFF session established successfully.", "success");
                return data;
              });
            });
          });
        }
      }
    };
  }

  // ---------------------------------------------------------------
  // Message Listener: receives bridge responses from host
  // ---------------------------------------------------------------
  global.addEventListener('message', function (event) {
    try {
      var data = JSON.parse(event.data);
      if (data && data.requestId && global._hostAppCallbacks[data.requestId]) {
        if (data.status === "success") {
          global._hostAppCallbacks[data.requestId].resolve(data.token);
        } else {
          global._hostAppCallbacks[data.requestId].reject(data.error);
        }
      }
    } catch (e) {
      // Ignore parsing errors for other messages
    }
  });

  // ---------------------------------------------------------------
  // Public API
  // ---------------------------------------------------------------
  global.logConsole = logConsole;
  global.createHostAppSdk = createHostAppSdk;

})(window);

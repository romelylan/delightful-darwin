import 'package:flutter/material.dart';
import 'package:flutter_inappwebview/flutter_inappwebview.dart';
import 'webview_stub.dart';
export 'webview_stub.dart' show WebViewController;
import 'dart:convert';

Widget createWebView({
  required String initialUrl,
  required Function(String) onMessageReceived,
  required Function(WebViewController) onWebViewCreated,
}) {
  return InAppWebView(
    initialUrlRequest: URLRequest(url: Uri.parse(initialUrl)),
    initialOptions: InAppWebViewGroupOptions(
      crossPlatform: InAppWebViewOptions(
        javaScriptEnabled: true,
        supportZoom: false,
      ),
    ),
    onWebViewCreated: (controller) {
      final wrapper = MobileWebViewController(controller);
      onWebViewCreated(wrapper);
      
      controller.addJavaScriptHandler(
        handlerName: 'JSBridgeChannel',
        callback: (args) async {
          final String rawJson = args.first;
          onMessageReceived(rawJson);
        },
      );
    },
  );
}

class MobileWebViewController implements WebViewController {
  final InAppWebViewController controller;
  MobileWebViewController(this.controller);

  @override
  void postMessage(String message) {
    final Map<String, dynamic> data = jsonDecode(message);
    final String requestId = data['requestId'];
    final String status = data['status'];
    
    String jsSource;
    if (status == "success") {
      final String token = data['token'];
      jsSource = """
        if (window._hostAppCallbacks && window._hostAppCallbacks['$requestId']) {
          window._hostAppCallbacks['$requestId'].resolve('$token');
          delete window._hostAppCallbacks['$requestId'];
        }
      """;
    } else {
      final String error = data['error'];
      jsSource = """
        if (window._hostAppCallbacks && window._hostAppCallbacks['$requestId']) {
          window._hostAppCallbacks['$requestId'].reject('$error');
          delete window._hostAppCallbacks['$requestId'];
        }
      """;
    }
    controller.evaluateJavascript(source: jsSource);
  }

  @override
  void reload() {
    controller.reload();
  }
}

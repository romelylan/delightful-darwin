import 'package:flutter/material.dart';

Widget createWebView({
  required String initialUrl,
  required Function(String) onMessageReceived,
  required Function(WebViewController) onWebViewCreated,
}) {
  return const Center(child: Text("WebView not supported on this platform"));
}

abstract class WebViewController {
  void postMessage(String message);
  void reload();
}

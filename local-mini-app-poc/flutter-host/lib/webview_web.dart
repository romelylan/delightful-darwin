import 'dart:convert';
import 'dart:html' as html;
import 'dart:ui_web' as ui_web;
import 'package:flutter/material.dart';
import 'webview_stub.dart';
export 'webview_stub.dart' show WebViewController;

Widget createWebView({
  required String initialUrl,
  required Function(String) onMessageReceived,
  required Function(WebViewController) onWebViewCreated,
}) {
  return WebWebViewWidget(
    initialUrl: initialUrl,
    onMessageReceived: onMessageReceived,
    onWebViewCreated: onWebViewCreated,
  );
}

class WebWebViewWidget extends StatefulWidget {
  final String initialUrl;
  final Function(String) onMessageReceived;
  final Function(WebViewController) onWebViewCreated;

  const WebWebViewWidget({
    super.key,
    required this.initialUrl,
    required this.onMessageReceived,
    required this.onWebViewCreated,
  });

  @override
  State<WebWebViewWidget> createState() => _WebWebViewWidgetState();
}

class _WebWebViewWidgetState extends State<WebWebViewWidget> {
  late final html.IFrameElement _iframe;
  late final String _viewType;
  late final WebWebViewController _controller;
  static bool _factoryRegistered = false;

  @override
  void initState() {
    super.initState();
    _viewType = 'iframe-webview-guest';
    
    _iframe = html.IFrameElement()
      ..src = widget.initialUrl
      ..style.border = 'none'
      ..style.width = '100%'
      ..style.height = '100%';

    if (!_factoryRegistered) {
      ui_web.platformViewRegistry.registerViewFactory(_viewType, (int viewId) => _iframe);
      _factoryRegistered = true;
    }

    // Setup message listener on Web
    html.window.addEventListener('message', _handleMessage);

    _controller = WebWebViewController(_iframe);
    
    // Safely trigger callback after the current build frame completes
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) {
        widget.onWebViewCreated(_controller);
      }
    });
  }

  void _handleMessage(html.Event event) {
    final messageEvent = event as html.MessageEvent;
    if (messageEvent.data is String) {
      final String rawJson = messageEvent.data as String;
      try {
        final Map<String, dynamic> data = jsonDecode(rawJson);
        if (data.containsKey('requestId') && data.containsKey('action')) {
          widget.onMessageReceived(rawJson);
        }
      } catch (_) {
        // Ignore parsing errors for unrelated messages
      }
    }
  }

  @override
  void dispose() {
    html.window.removeEventListener('message', _handleMessage);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return HtmlElementView(viewType: _viewType);
  }
}

class WebWebViewController implements WebViewController {
  final html.IFrameElement iframe;
  WebWebViewController(this.iframe);

  @override
  void postMessage(String message) {
    iframe.contentWindow?.postMessage(message, '*');
  }

  @override
  void reload() {
    iframe.src = iframe.src;
  }
}

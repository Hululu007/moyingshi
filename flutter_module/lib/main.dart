import 'package:flutter/material.dart';

import 'pigeon/c0_messages.g.dart';

void main() {
  runApp(const C0ShellApp());
}

/// B8 C0 placeholder screen: proves the FlutterFragment renders and one
/// Pigeon round-trip reaches native code and back.
class C0ShellApp extends StatelessWidget {
  const C0ShellApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Mooyingshi C0',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF0B57D0)),
      ),
      home: const C0HomePage(),
    );
  }
}

class C0HomePage extends StatefulWidget {
  const C0HomePage({super.key});

  @override
  State<C0HomePage> createState() => _C0HomePageState();
}

class _C0HomePageState extends State<C0HomePage> {
  final C0HostApi _api = C0HostApi();
  String _hostInfo = '尚未调用原生接口';
  bool _calling = false;

  Future<void> _callHost() async {
    if (_calling) return;
    setState(() {
      _calling = true;
      _hostInfo = '调用中…';
    });
    try {
      final String info = await _api.getHostInfo();
      if (!mounted) return;
      setState(() => _hostInfo = info);
    } catch (e) {
      if (!mounted) return;
      setState(() => _hostInfo = '调用失败：$e');
    } finally {
      if (mounted) {
        setState(() => _calling = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Flutter 实验（C0）'),
      ),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text(
                '这是 Flutter 渲染的占位屏。',
                style: TextStyle(fontSize: 18),
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 16),
              Text(
                _hostInfo,
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 24),
              FilledButton(
                onPressed: _calling ? null : _callHost,
                child: const Text('调用原生接口'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

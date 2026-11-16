import 'package:material_ui/material_ui.dart';

import 'c1_about_page.dart';
import 'c1_subtitle_page.dart';
import 'pigeon/c0_messages.g.dart';
import 'pigeon/c1_messages.g.dart';

/// The host tells Dart which screen this engine should show first, so one
/// module binary serves the C0 shell ('/') and the C1 screens ('/about',
/// '/subtitle'). Asking the host avoids depending on the cached-engine
/// FlutterFragment initialRoute API, whose availability in Flutter 3.50 was
/// not verified on this machine. A failed call falls back to the C0 shell.
void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  String initialRoute = '/';
  try {
    initialRoute = await C1HostApi().getInitialRoute();
  } catch (_) {
    // Keep the C0 shell as the safe default.
  }
  runApp(C0ShellApp(initialRoute: initialRoute));
}

/// B8 C0 placeholder screen + B8 C1 screens, routed by the host.
/// Theme seed stays the Mooyingshi accent #0B57D0 for every route.
class C0ShellApp extends StatelessWidget {
  const C0ShellApp({super.key, required this.initialRoute});

  final String initialRoute;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Mooyingshi C0',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF0B57D0)),
      ),
      initialRoute: initialRoute,
      routes: {
        '/': (context) => const C0HomePage(),
        '/about': (context) => const C1AboutPage(),
        '/subtitle': (context) => const C1SubtitlePage(),
      },
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
  final C1HostApi _c1Api = C1HostApi();
  String _hostInfo = '尚未调用原生接口';
  bool _calling = false;
  bool _c1Enabled = false;

  @override
  void initState() {
    super.initState();
    _loadC1Flag();
  }

  Future<void> _loadC1Flag() async {
    try {
      final bool enabled = await _c1Api.isC1Enabled();
      if (!mounted) return;
      setState(() => _c1Enabled = enabled);
    } catch (_) {
      // The switch stays off; the native screens remain the default.
    }
  }

  Future<void> _setC1Flag(bool value) async {
    setState(() => _c1Enabled = value);
    try {
      await _c1Api.setC1Enabled(value);
    } catch (_) {
      if (!mounted) return;
      setState(() => _c1Enabled = !value);
    }
  }

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
              const SizedBox(height: 24),
              const Divider(),
              SwitchListTile(
                title: const Text('用 Flutter 打开关于 / 字幕设置'),
                subtitle: const Text('打开后，设置页里的“关于”和“字幕设置”走 Flutter 屏；关掉就回到原生。'),
                value: _c1Enabled,
                onChanged: _setC1Flag,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

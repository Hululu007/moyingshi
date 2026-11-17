import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';

import 'b10_collect_page.dart';
import 'b11_home_page.dart';
import 'b14_following_page.dart';
import 'b15_local_page.dart';
import 'b16_report_page.dart';
import 'b17_person_page.dart';
import 'b18_widget_page.dart';
import 'b19_plugin_page.dart';
import 'b20_hk_page.dart';
import 'b21_lab_page.dart';
import 'b22_drama_page.dart';
import 'b2_keep_page.dart';
import 'b3_settings_a_page.dart';
import 'b8_category_page.dart';
import 'b9_search_page.dart';
import 'c1_about_page.dart';
import 'c1_subtitle_page.dart';
import 'c2_history_page.dart';
import 'pigeon/b10_collect_messages.g.dart';
import 'pigeon/b11_home_messages.g.dart';
import 'pigeon/b12_home_a_messages.g.dart';
import 'pigeon/b13_home_b_messages.g.dart';
import 'pigeon/b14_following_messages.g.dart';
import 'pigeon/b15_local_messages.g.dart';
import 'pigeon/b16_report_messages.g.dart';
import 'pigeon/b17_person_messages.g.dart';
import 'pigeon/b18_widget_messages.g.dart';
import 'pigeon/b19_plugin_messages.g.dart';
import 'pigeon/b20_hk_messages.g.dart';
import 'pigeon/b21_lab_messages.g.dart';
import 'pigeon/b22_drama_messages.g.dart';
import 'pigeon/b2_keep_messages.g.dart';
import 'pigeon/b3_settings_a_messages.g.dart';
import 'pigeon/b4_enhance_messages.g.dart';
import 'pigeon/b5_personal_messages.g.dart';
import 'pigeon/b6_player_messages.g.dart';
import 'pigeon/b7_settings_home_messages.g.dart';
import 'pigeon/b8_category_messages.g.dart';
import 'pigeon/b9_search_messages.g.dart';
import 'pigeon/c0_messages.g.dart';
import 'pigeon/c1_messages.g.dart';
import 'pigeon/c2_messages.g.dart';

/// B8 relaunch mechanism (see docs/b8-c1-relaunch-mechanism.md): the host
/// tells Dart which route the foreground C1 screen must show, so a reused
/// cached engine can be redirected to a new route instead of showing the
/// stale one (the G2 race). A raw MethodChannel, not Pigeon: the C1
/// contract is frozen, and this is a host-to-Dart push, not a
/// request/response call. The host only ever sends on engine reuse; the
/// initial-route flow below is untouched.
const MethodChannel _relaunchChannel =
    MethodChannel('com.github.moyingshi.osc.tv/c1_relaunch');

/// The root navigator, so the relaunch handler can reset the route stack.
final GlobalKey<NavigatorState> _rootNavigatorKey = GlobalKey<NavigatorState>();

/// The last route Dart was told to show: the initial route, or the last
/// accepted showRoute message. A repeated assertion of the same route is
/// ignored, so rotation and same-route resumes never reset family
/// Navigator depth.
String? _currentTopRoute;

/// Switches the root navigator to [route], dropping the whole stack. A
/// no-op when Dart is already showing [route], or when the navigator is
/// not built yet (unreachable in practice: the host only sends on engine
/// reuse, when the app is already built). An unknown route name leaves
/// the current screen untouched; the host re-asserts on the next resume.
void _showRoute(String route) {
  if (route == _currentTopRoute) return;
  final NavigatorState? navigator = _rootNavigatorKey.currentState;
  if (navigator == null) return;
  try {
    navigator.pushNamedAndRemoveUntil(route, (_) => false);
  } catch (_) {
    return;
  }
  _currentTopRoute = route;
}

/// The host tells Dart which screen this engine should show first, so one
/// module binary serves the C0 shell ('/'), the C1 screens ('/about',
/// '/subtitle'), the C2 screen ('/history'), the B2 screen ('/keep') and
/// the B3 settings family ('/settings' with a ?member= query). Asking the
/// host avoids depending on the cached-engine
/// FlutterFragment initialRoute API, whose availability in Flutter 3.50 was
/// not verified on this machine. A failed call falls back to the C0 shell.
void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  // B8 relaunch: register before anything awaits, so the handler exists
  // as early as possible in the isolate's life.
  _relaunchChannel.setMethodCallHandler((MethodCall call) async {
    if (call.method == 'showRoute' && call.arguments is String) {
      _showRoute(call.arguments as String);
    }
    return null;
  });
  String initialRoute = '/';
  try {
    initialRoute = await C1HostApi().getInitialRoute();
  } catch (_) {
    // Keep the C0 shell as the safe default.
  }
  _currentTopRoute = initialRoute;
  runApp(C0ShellApp(initialRoute: initialRoute));
}

/// B8 C0 placeholder screen + B8 C1/C2 screens, routed by the host.
/// Theme seed stays the Mooyingshi accent #0B57D0 for every route.
class C0ShellApp extends StatelessWidget {
  const C0ShellApp({super.key, required this.initialRoute});

  final String initialRoute;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Mooyingshi C0',
      // B8 relaunch: the root navigator key lets the host-driven
      // showRoute handler reset the route stack on engine reuse.
      navigatorKey: _rootNavigatorKey,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF0B57D0)),
      ),
      initialRoute: initialRoute,
      routes: {
        '/': (context) => const C0HomePage(),
        '/about': (context) => const C1AboutPage(),
        '/subtitle': (context) => const C1SubtitlePage(),
        '/history': (context) => const C2HistoryPage(),
        '/keep': (context) => const B2KeepPage(),
        // B8 B14: the mobile following page; only this literal route is
        // added, the entries above are untouched.
        '/following': (context) => const B14FollowingPage(),
        // B8 B15: the app-branding page; only this literal route is
        // added, the entries above are untouched.
        '/branding': (context) => const B15BrandingPage(),
        // B8 B19: the mobile plugin manager page; only this literal
        // route is added, the entries above are untouched.
        '/plugins': (context) => const B19PluginPage(),
        // B8 B20: the mobile Hk miniapp manager page; only this
        // literal route is added, the entries above are untouched.
        '/hk-manager': (context) => const B20HkPage(),
        // B8 B21: the mobile lab list + detail pages (family route
        // with an in-family Navigator); only this literal route is
        // added, the entries above are untouched.
        '/lab': (context) => const B21LabPage(),
        // B8 B22: the short-drama theater grid and history list
        // (scheme B: the native ShortDramaActivity stays the entry
        // and play-stream host; only its theater/history tabs divert
        // here). Two independent literal routes, not a family route;
        // only these two entries are added, the entries above are
        // untouched.
        '/drama-theater': (context) => const B22TheaterPage(),
        '/drama-history': (context) => const B22HistoryPage(),
        // B8 B11: the mobile home shell (top bar + tabs + glass bottom
        // bar); only this literal route is added, the entries above are
        // untouched. hk:// sites and WebHome themes fall back to the
        // native home in the host diversion, never in Dart.
        '/home': (context) => const B11HomePage(),
        // B8 B3: the settings family. One literal route per member (the
        // routes table matches exact strings); '/settings' without a
        // member defaults to the first member, TMDB. B4/B5/B6/B7 add
        // members the same way, without touching these entries.
        // B8 B7: the settings home joins the family as the default
        // member; '/settings' without a member now opens the home.
        '/settings': (context) => const B3SettingsPage(member: 'home'),
        '/settings?member=home': (context) =>
            const B3SettingsPage(member: 'home'),
        '/settings?member=tmdb': (context) =>
            const B3SettingsPage(member: 'tmdb'),
        '/settings?member=ai': (context) => const B3SettingsPage(member: 'ai'),
        '/settings?member=ad': (context) => const B3SettingsPage(member: 'ad'),
        '/settings?member=danmaku': (context) =>
            const B3SettingsPage(member: 'danmaku'),
        // B8 B4: the enhance page joins the settings family the same way;
        // only this literal route is added, the B3 entries are untouched.
        '/settings?member=enhance': (context) =>
            const B3SettingsPage(member: 'enhance'),
        // B8 B5: the personal page and the settings search join the
        // settings family the same way; only these literal routes are
        // added, the B3/B4 entries are untouched.
        '/settings?member=personal': (context) =>
            const B3SettingsPage(member: 'personal'),
        '/settings?member=search': (context) =>
            const B3SettingsPage(member: 'search'),
        // B8 B6: the player settings page joins the settings family the
        // same way; only this literal route is added, the B3/B4/B5
        // entries are untouched.
        '/settings?member=player': (context) =>
            const B3SettingsPage(member: 'player'),
      },
      // B8 B8: the category list cannot use the literal routes table —
      // its parameters (key, typeId, the Class JSON, history-resume
      // params) are unbounded, so onGenerateRoute parses them from the
      // query string instead. Anything not starting with '/category'
      // returns null, so every literal route above keeps working.
      // B8 B9: the search input page joins onGenerateRoute the same way
      // (keyword, siteKey, pic, wallPic are URL-encoded in the query);
      // '/settings?member=search' is a different, literal route and is
      // untouched.
      // B8 B10: the aggregate search page joins onGenerateRoute the
      // same way (keyword, scope, group, pictures and history-resume
      // params are URL-encoded in the query); it is also pushed from
      // the B9 input page inside the family (pure Dart, no route).
      // B8 B15: the local-file browser joins onGenerateRoute the same
      // way (selectDir and readMode are URL-encoded booleans in the
      // query); '/branding' is a different, literal route and is
      // untouched.
      // B8 B16: the viewing-report page joins onGenerateRoute the same
      // way (range is a ViewingReportRange name in the query, default
      // ALL); the range dialog itself lives inside the page.
      // B8 B17: the TMDB person page joins onGenerateRoute the same
      // way (personId, name, subtitle, profile, department, biography,
      // siteKey, detailMode are URL-encoded in the query); the initial
      // person fields are the intent extras the native page shows
      // before its TMDB request returns.
      // B8 B18: the widget browse page joins onGenerateRoute the same
      // way (widgetId is URL-encoded in the query); it takes over the
      // native WidgetBrowseActivity and the B13 home-block "more"
      // opening (see docs/b8-b18-widget.md).
      onGenerateRoute: (RouteSettings settings) {
        final String? name = settings.name;
        if (name != null && name.startsWith('/widgets')) {
          final Uri uri = Uri.parse(name);
          final Map<String, String> params = uri.queryParameters;
          return MaterialPageRoute<void>(
            builder: (_) => B18BrowsePage(
              widgetId: params['widgetId'] ?? '',
            ),
            settings: settings,
          );
        }
        if (name != null && name.startsWith('/person')) {
          final Uri uri = Uri.parse(name);
          final Map<String, String> params = uri.queryParameters;
          return MaterialPageRoute<void>(
            builder: (_) => B17PersonPage(
              personId: int.tryParse(params['personId'] ?? '') ?? 0,
              name: params['name'] ?? '',
              subtitle: params['subtitle'] ?? '',
              profileUrl: params['profile'] ?? '',
              department: params['department'] ?? '',
              biography: params['biography'] ?? '',
              siteKey: params['siteKey'] ?? '',
              detailMode: int.tryParse(params['detailMode'] ?? '') ?? 0,
            ),
            settings: settings,
          );
        }
        if (name != null && name.startsWith('/report')) {
          final Uri uri = Uri.parse(name);
          final Map<String, String> params = uri.queryParameters;
          return MaterialPageRoute<void>(
            builder: (_) => B16ReportPage(
              initialRange: params['range'] ?? 'ALL',
            ),
            settings: settings,
          );
        }
        if (name != null && name.startsWith('/local')) {
          final Uri uri = Uri.parse(name);
          final Map<String, String> params = uri.queryParameters;
          return MaterialPageRoute<void>(
            builder: (_) => B15LocalPage(
              selectDir: params['selectDir'] == 'true',
              readMode: params['readMode'] == 'true',
            ),
            settings: settings,
          );
        }
        if (name != null && name.startsWith('/collect')) {
          final Uri uri = Uri.parse(name);
          final Map<String, String> params = uri.queryParameters;
          return MaterialPageRoute<void>(
            builder: (_) => B10CollectPage(
              keyword: params['keyword'] ?? '',
              currentSite: params['currentSite'] == 'true',
              siteKey: params['siteKey'] ?? '',
              group: params['group'] ?? '',
              pic: params['pic'] ?? '',
              wallPic: params['wallPic'] ?? '',
              historyResumeCid:
                  int.tryParse(params['hrCid'] ?? '') ?? -1,
              historyResumeKey: params['hrKey'] ?? '',
              historyResumeTargetCid:
                  int.tryParse(params['hrTargetCid'] ?? '') ?? -1,
            ),
            settings: settings,
          );
        }
        if (name != null && name.startsWith('/search')) {
          final Uri uri = Uri.parse(name);
          final Map<String, String> params = uri.queryParameters;
          return MaterialPageRoute<void>(
            builder: (_) => B9SearchPage(
              keyword: params['keyword'] ?? '',
              siteKey: params['siteKey'] ?? '',
              pic: params['pic'] ?? '',
              wallPic: params['wallPic'] ?? '',
            ),
            settings: settings,
          );
        }
        if (name != null && name.startsWith('/category')) {
          final Uri uri = Uri.parse(name);
          final Map<String, String> params = uri.queryParameters;
          return MaterialPageRoute<void>(
            builder: (_) => B8CategoryPage(
              siteKey: params['key'] ?? '',
              typeId: params['typeId'] ?? '',
              typeJson: params['type'] ?? '',
              historyResumeCid:
                  int.tryParse(params['hrCid'] ?? '') ?? -1,
              historyResumeKey: params['hrKey'] ?? '',
              historyResumeTargetCid:
                  int.tryParse(params['hrTargetCid'] ?? '') ?? -1,
            ),
            settings: settings,
          );
        }
        return null;
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
  final C2HostApi _c2Api = C2HostApi();
  final B2HostApi _b2Api = B2HostApi();
  final B3HostApi _b3Api = B3HostApi();
  final B4HostApi _b4Api = B4HostApi();
  final B5HostApi _b5Api = B5HostApi();
  final B6HostApi _b6Api = B6HostApi();
  final B7HostApi _b7Api = B7HostApi();
  final B8HostApi _b8Api = B8HostApi();
  final B9HostApi _b9Api = B9HostApi();
  final B10HostApi _b10Api = B10HostApi();
  final B11HostApi _b11Api = B11HostApi();
  final B12HostApi _b12Api = B12HostApi();
  final B13HostApi _b13Api = B13HostApi();
  final B14HostApi _b14Api = B14HostApi();
  final B15HostApi _b15Api = B15HostApi();
  final B16HostApi _b16Api = B16HostApi();
  final B17HostApi _b17Api = B17HostApi();
  final B18HostApi _b18Api = B18HostApi();
  final B19HostApi _b19Api = B19HostApi();
  final B20HostApi _b20Api = B20HostApi();
  final B21HostApi _b21Api = B21HostApi();
  final B22HostApi _b22Api = B22HostApi();
  String _hostInfo = '尚未调用原生接口';
  bool _calling = false;
  bool _c1Enabled = false;
  bool _c2Enabled = false;
  bool _b2Enabled = false;
  bool _b3Enabled = false;
  bool _b4Enabled = false;
  bool _b5Enabled = false;
  bool _b6Enabled = false;
  bool _b7Enabled = false;
  bool _b8Enabled = false;
  bool _b9Enabled = false;
  bool _b10Enabled = false;
  bool _b11Enabled = false;
  bool _b12Enabled = false;
  bool _b13Enabled = false;
  bool _b14Enabled = false;
  bool _b15Enabled = false;
  bool _b16Enabled = false;
  bool _b17Enabled = false;
  bool _b18Enabled = false;
  bool _b19Enabled = false;
  bool _b20Enabled = false;
  bool _b21Enabled = false;
  bool _b22Enabled = false;

  @override
  void initState() {
    super.initState();
    _loadC1Flag();
    _loadC2Flag();
    _loadB2Flag();
    _loadB3Flag();
    _loadB4Flag();
    _loadB5Flag();
    _loadB6Flag();
    _loadB7Flag();
    _loadB8Flag();
    _loadB9Flag();
    _loadB10Flag();
    _loadB11Flag();
    _loadB12Flag();
    _loadB13Flag();
    _loadB14Flag();
    _loadB15Flag();
    _loadB16Flag();
    _loadB17Flag();
    _loadB18Flag();
    _loadB19Flag();
    _loadB20Flag();
    _loadB21Flag();
    _loadB22Flag();
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

  Future<void> _loadC2Flag() async {
    try {
      final bool enabled = await _c2Api.isC2Enabled();
      if (!mounted) return;
      setState(() => _c2Enabled = enabled);
    } catch (_) {
      // The switch stays off; the native screen remains the default.
    }
  }

  Future<void> _setC2Flag(bool value) async {
    setState(() => _c2Enabled = value);
    try {
      await _c2Api.setC2Enabled(value);
    } catch (_) {
      if (!mounted) return;
      setState(() => _c2Enabled = !value);
    }
  }

  Future<void> _loadB2Flag() async {
    try {
      final bool enabled = await _b2Api.isB2Enabled();
      if (!mounted) return;
      setState(() => _b2Enabled = enabled);
    } catch (_) {
      // The switch stays off; the native screen remains the default.
    }
  }

  Future<void> _setB2Flag(bool value) async {
    setState(() => _b2Enabled = value);
    try {
      await _b2Api.setB2Enabled(value);
    } catch (_) {
      if (!mounted) return;
      setState(() => _b2Enabled = !value);
    }
  }

  Future<void> _loadB3Flag() async {
    try {
      final bool enabled = await _b3Api.isB3Enabled();
      if (!mounted) return;
      setState(() => _b3Enabled = enabled);
    } catch (_) {
      // The switch stays off; the native screens remain the default.
    }
  }

  Future<void> _setB3Flag(bool value) async {
    setState(() => _b3Enabled = value);
    try {
      await _b3Api.setB3Enabled(value);
    } catch (_) {
      if (!mounted) return;
      setState(() => _b3Enabled = !value);
    }
  }

  Future<void> _loadB4Flag() async {
    try {
      final bool enabled = await _b4Api.isB4Enabled();
      if (!mounted) return;
      setState(() => _b4Enabled = enabled);
    } catch (_) {
      // The switch stays off; the native page remains the default.
    }
  }

  Future<void> _setB4Flag(bool value) async {
    setState(() => _b4Enabled = value);
    try {
      await _b4Api.setB4Enabled(value);
    } catch (_) {
      if (!mounted) return;
      setState(() => _b4Enabled = !value);
    }
  }

  Future<void> _loadB5Flag() async {
    try {
      final bool enabled = await _b5Api.isB5Enabled();
      if (!mounted) return;
      setState(() => _b5Enabled = enabled);
    } catch (_) {
      // The switch stays off; the native pages remain the default.
    }
  }

  Future<void> _setB5Flag(bool value) async {
    setState(() => _b5Enabled = value);
    try {
      await _b5Api.setB5Enabled(value);
    } catch (_) {
      if (!mounted) return;
      setState(() => _b5Enabled = !value);
    }
  }

  Future<void> _loadB6Flag() async {
    try {
      final bool enabled = await _b6Api.isB6Enabled();
      if (!mounted) return;
      setState(() => _b6Enabled = enabled);
    } catch (_) {
      // The switch stays off; the native page remains the default.
    }
  }

  Future<void> _setB6Flag(bool value) async {
    setState(() => _b6Enabled = value);
    try {
      await _b6Api.setB6Enabled(value);
    } catch (_) {
      if (!mounted) return;
      setState(() => _b6Enabled = !value);
    }
  }

  Future<void> _loadB7Flag() async {
    try {
      final bool enabled = await _b7Api.isB7Enabled();
      if (!mounted) return;
      setState(() => _b7Enabled = enabled);
    } catch (_) {
      // The switch stays off; the native page remains the default.
    }
  }

  Future<void> _setB7Flag(bool value) async {
    setState(() => _b7Enabled = value);
    try {
      await _b7Api.setB7Enabled(value);
    } catch (_) {
      if (!mounted) return;
      setState(() => _b7Enabled = !value);
    }
  }

  Future<void> _loadB8Flag() async {
    try {
      final bool enabled = await _b8Api.isB8Enabled();
      if (!mounted) return;
      setState(() => _b8Enabled = enabled);
    } catch (_) {
      // The switch stays off; the native screen remains the default.
    }
  }

  Future<void> _setB8Flag(bool value) async {
    setState(() => _b8Enabled = value);
    try {
      await _b8Api.setB8Enabled(value);
    } catch (_) {
      if (!mounted) return;
      setState(() => _b8Enabled = !value);
    }
  }

  Future<void> _loadB9Flag() async {
    try {
      final bool enabled = await _b9Api.isB9Enabled();
      if (!mounted) return;
      setState(() => _b9Enabled = enabled);
    } catch (_) {
      // The switch stays off; the native screen remains the default.
    }
  }

  Future<void> _setB9Flag(bool value) async {
    setState(() => _b9Enabled = value);
    try {
      await _b9Api.setB9Enabled(value);
    } catch (_) {
      if (!mounted) return;
      setState(() => _b9Enabled = !value);
    }
  }

  Future<void> _loadB10Flag() async {
    try {
      final bool enabled = await _b10Api.isB10Enabled();
      if (!mounted) return;
      setState(() => _b10Enabled = enabled);
    } catch (_) {
      // The switch stays off; the native page remains the default.
    }
  }

  Future<void> _setB10Flag(bool value) async {
    setState(() => _b10Enabled = value);
    try {
      await _b10Api.setB10Enabled(value);
    } catch (_) {
      if (!mounted) return;
      setState(() => _b10Enabled = !value);
    }
  }

  Future<void> _loadB11Flag() async {
    try {
      final bool enabled = await _b11Api.isB11Enabled();
      if (!mounted) return;
      setState(() => _b11Enabled = enabled);
    } catch (_) {
      // The switch stays off; the native home remains the default.
    }
  }

  Future<void> _setB11Flag(bool value) async {
    setState(() => _b11Enabled = value);
    try {
      await _b11Api.setB11Enabled(value);
    } catch (_) {
      if (!mounted) return;
      setState(() => _b11Enabled = !value);
    }
  }

  Future<void> _loadB12Flag() async {
    try {
      final bool enabled = await _b12Api.isB12Enabled();
      if (!mounted) return;
      setState(() => _b12Enabled = enabled);
    } catch (_) {
      // The switch stays off; the native home blocks remain the default.
    }
  }

  Future<void> _setB12Flag(bool value) async {
    setState(() => _b12Enabled = value);
    try {
      await _b12Api.setB12Enabled(value);
    } catch (_) {
      if (!mounted) return;
      setState(() => _b12Enabled = !value);
    }
  }

  Future<void> _loadB13Flag() async {
    try {
      final bool enabled = await _b13Api.isB13Enabled();
      if (!mounted) return;
      setState(() => _b13Enabled = enabled);
    } catch (_) {
      // The switch stays off; the JS widget sections stay hidden.
    }
  }

  Future<void> _setB13Flag(bool value) async {
    setState(() => _b13Enabled = value);
    try {
      await _b13Api.setB13Enabled(value);
    } catch (_) {
      if (!mounted) return;
      setState(() => _b13Enabled = !value);
    }
  }

  Future<void> _loadB14Flag() async {
    try {
      final bool enabled = await _b14Api.isB14Enabled();
      if (!mounted) return;
      setState(() => _b14Enabled = enabled);
    } catch (_) {
      // The switch stays off; the native page remains the default.
    }
  }

  Future<void> _setB14Flag(bool value) async {
    setState(() => _b14Enabled = value);
    try {
      await _b14Api.setB14Enabled(value);
    } catch (_) {
      if (!mounted) return;
      setState(() => _b14Enabled = !value);
    }
  }

  Future<void> _loadB15Flag() async {
    try {
      final bool enabled = await _b15Api.isB15Enabled();
      if (!mounted) return;
      setState(() => _b15Enabled = enabled);
    } catch (_) {
      // The switch stays off; the native pages remain the default.
    }
  }

  Future<void> _setB15Flag(bool value) async {
    setState(() => _b15Enabled = value);
    try {
      await _b15Api.setB15Enabled(value);
    } catch (_) {
      if (!mounted) return;
      setState(() => _b15Enabled = !value);
    }
  }

  Future<void> _loadB16Flag() async {
    try {
      final bool enabled = await _b16Api.isB16Enabled();
      if (!mounted) return;
      setState(() => _b16Enabled = enabled);
    } catch (_) {
      // The switch stays off; the native page remains the default.
    }
  }

  Future<void> _setB16Flag(bool value) async {
    setState(() => _b16Enabled = value);
    try {
      await _b16Api.setB16Enabled(value);
    } catch (_) {
      if (!mounted) return;
      setState(() => _b16Enabled = !value);
    }
  }

  Future<void> _loadB17Flag() async {
    try {
      final bool enabled = await _b17Api.isB17Enabled();
      if (!mounted) return;
      setState(() => _b17Enabled = enabled);
    } catch (_) {
      // The switch stays off; the native page remains the default.
    }
  }

  Future<void> _setB17Flag(bool value) async {
    setState(() => _b17Enabled = value);
    try {
      await _b17Api.setB17Enabled(value);
    } catch (_) {
      if (!mounted) return;
      setState(() => _b17Enabled = !value);
    }
  }

  Future<void> _loadB18Flag() async {
    try {
      final bool enabled = await _b18Api.isB18Enabled();
      if (!mounted) return;
      setState(() => _b18Enabled = enabled);
    } catch (_) {
      // The switch stays off; the native page remains the default.
    }
  }

  Future<void> _setB18Flag(bool value) async {
    setState(() => _b18Enabled = value);
    try {
      await _b18Api.setB18Enabled(value);
    } catch (_) {
      if (!mounted) return;
      setState(() => _b18Enabled = !value);
    }
  }

  Future<void> _loadB19Flag() async {
    try {
      final bool enabled = await _b19Api.isB19Enabled();
      if (!mounted) return;
      setState(() => _b19Enabled = enabled);
    } catch (_) {
      // The switch stays off; the native page remains the default.
    }
  }

  Future<void> _setB19Flag(bool value) async {
    setState(() => _b19Enabled = value);
    try {
      await _b19Api.setB19Enabled(value);
    } catch (_) {
      if (!mounted) return;
      setState(() => _b19Enabled = !value);
    }
  }

  Future<void> _loadB20Flag() async {
    try {
      final bool enabled = await _b20Api.isB20Enabled();
      if (!mounted) return;
      setState(() => _b20Enabled = enabled);
    } catch (_) {
      // The switch stays off; the native page remains the default.
    }
  }

  Future<void> _setB20Flag(bool value) async {
    setState(() => _b20Enabled = value);
    try {
      await _b20Api.setB20Enabled(value);
    } catch (_) {
      if (!mounted) return;
      setState(() => _b20Enabled = !value);
    }
  }

  Future<void> _loadB21Flag() async {
    try {
      final bool enabled = await _b21Api.isB21Enabled();
      if (!mounted) return;
      setState(() => _b21Enabled = enabled);
    } catch (_) {
      // The switch stays off; the native page remains the default.
    }
  }

  Future<void> _setB21Flag(bool value) async {
    setState(() => _b21Enabled = value);
    try {
      await _b21Api.setB21Enabled(value);
    } catch (_) {
      if (!mounted) return;
      setState(() => _b21Enabled = !value);
    }
  }

  Future<void> _loadB22Flag() async {
    try {
      final bool enabled = await _b22Api.isB22Enabled();
      if (!mounted) return;
      setState(() => _b22Enabled = enabled);
    } catch (_) {
      // The switch stays off; the native tabs remain the default.
    }
  }

  Future<void> _setB22Flag(bool value) async {
    setState(() => _b22Enabled = value);
    try {
      await _b22Api.setB22Enabled(value);
    } catch (_) {
      if (!mounted) return;
      setState(() => _b22Enabled = !value);
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
      // B8 B2: the switch list outgrows one screen, so the experiment
      // screen scrolls now; only the container changed, the switches
      // and their semantics are untouched.
      body: SingleChildScrollView(
        child: Center(
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
              SwitchListTile(
                title: const Text('用 Flutter 打开历史记录'),
                subtitle: const Text('打开后，首页的历史入口走 Flutter 屏；关掉就回到原生。'),
                value: _c2Enabled,
                onChanged: _setC2Flag,
              ),
              SwitchListTile(
                title: const Text('用 Flutter 打开收藏'),
                subtitle: const Text('打开后，首页菜单里的“收藏”走 Flutter 屏；关掉就回到原生。'),
                value: _b2Enabled,
                onChanged: _setB2Flag,
              ),
              SwitchListTile(
                title: const Text('用 Flutter 打开 TMDB / AI / 去广告 / 弹幕设置'),
                subtitle: const Text('打开后，设置页里的这四个子设置页走 Flutter 屏；关掉就回到原生。'),
                value: _b3Enabled,
                onChanged: _setB3Flag,
              ),
              SwitchListTile(
                title: const Text('用 Flutter 打开增强设置'),
                subtitle: const Text('打开后，设置页里的“增强设置”走 Flutter 屏；关掉就回到原生。'),
                value: _b4Enabled,
                onChanged: _setB4Flag,
              ),
              SwitchListTile(
                title: const Text('用 Flutter 打开个人设置 / 设置内搜索'),
                subtitle: const Text('打开后，设置页里的“个人设置”和设置搜索走 Flutter 屏；关掉就回到原生。'),
                value: _b5Enabled,
                onChanged: _setB5Flag,
              ),
              SwitchListTile(
                title: const Text('用 Flutter 打开播放器设置'),
                subtitle: const Text('打开后，设置页里的“播放器设置”走 Flutter 屏；关掉就回到原生。'),
                value: _b6Enabled,
                onChanged: _setB6Flag,
              ),
              SwitchListTile(
                title: const Text('用 Flutter 打开设置主屏'),
                subtitle: const Text('打开后，设置首页走 Flutter 屏；关掉就回到原生。'),
                value: _b7Enabled,
                onChanged: _setB7Flag,
              ),
              SwitchListTile(
                title: const Text('用 Flutter 打开分类列表'),
                subtitle: const Text('打开后，详情页类型入口和搜索结果文件夹入口走 Flutter 分类页；关掉就回到原生。'),
                value: _b8Enabled,
                onChanged: _setB8Flag,
              ),
              SwitchListTile(
                title: const Text('用 Flutter 打开搜索输入页'),
                subtitle: const Text('打开后，首页搜索栏等搜索入口走 Flutter 输入页，结果页由“聚合搜索”开关控制；关掉就回到原生。'),
                value: _b9Enabled,
                onChanged: _setB9Flag,
              ),
              SwitchListTile(
                title: const Text('用 Flutter 打开聚合搜索'),
                subtitle: const Text('打开后，搜索结果页（多站源并行搜索）走 Flutter 屏；关掉就回到原生。'),
                value: _b10Enabled,
                onChanged: _setB10Flag,
              ),
              SwitchListTile(
                title: const Text('用 Flutter 打开首页'),
                subtitle: const Text('打开后，手机首页（顶部栏、分类 Tab、玻璃底栏）走 Flutter 屏；hk:// 站点和 Web 主题仍走原生首页。关掉就回到原生。'),
                value: _b11Enabled,
                onChanged: _setB11Flag,
              ),
              SwitchListTile(
                title: const Text('用 Flutter 打开首页轮播 / 继续观看'),
                subtitle: const Text('打开后，Flutter 首页里的轮播横幅和“继续观看”行走 Flutter；关掉就回到 B11 的占位区（原生首页本身不受影响）。'),
                value: _b12Enabled,
                onChanged: _setB12Flag,
              ),
              SwitchListTile(
                title: const Text('用 Flutter 打开首页小组件区'),
                subtitle: const Text('打开后，Flutter 首页里的 JS 小组件区（番剧 / 豆瓣 / 蜜柑 / TMDB）走 Flutter；关掉就回到 B11 的占位区（原生首页本身不受影响）。'),
                value: _b13Enabled,
                onChanged: _setB13Flag,
              ),
              SwitchListTile(
                title: const Text('用 Flutter 打开追更'),
                subtitle: const Text('打开后，底栏的“追更”走 Flutter 屏；关掉就回到原生。'),
                value: _b14Enabled,
                onChanged: _setB14Flag,
              ),
              SwitchListTile(
                title: const Text('用 Flutter 打开本地文件 / 应用图标'),
                subtitle: const Text('打开后，本地文件浏览和应用图标选择走 Flutter 屏；关掉就回到原生。'),
                value: _b15Enabled,
                onChanged: _setB15Flag,
              ),
              SwitchListTile(
                title: const Text('用 Flutter 打开观看报表'),
                subtitle: const Text('打开后，历史页的“观看报表”走 Flutter 屏；关掉就回到原生。'),
                value: _b16Enabled,
                onChanged: _setB16Flag,
              ),
              SwitchListTile(
                title: const Text('用 Flutter 打开 TMDB 人物'),
                subtitle: const Text('打开后，详情页的人物入口走 Flutter 屏；关掉就回到原生。'),
                value: _b17Enabled,
                onChanged: _setB17Flag,
              ),
              SwitchListTile(
                title: const Text('用 Flutter 打开小组件浏览'),
                subtitle: const Text('打开后，首页小组件的“更多”和小组件浏览页走 Flutter 屏；关掉就回到原生。'),
                value: _b18Enabled,
                onChanged: _setB18Flag,
              ),
              SwitchListTile(
                title: const Text('用 Flutter 打开插件管理'),
                subtitle: const Text('打开后，设置页里的“插件管理”走 Flutter 屏；关掉就回到原生。'),
                value: _b19Enabled,
                onChanged: _setB19Flag,
              ),
              SwitchListTile(
                title: const Text('用 Flutter 打开海阔小程序管理'),
                subtitle: const Text('打开后，实验室“海阔小程序”分组的管理页走 Flutter 屏，小程序列表 / 详情 / 搜索仍走原生；关掉就回到原生。'),
                value: _b20Enabled,
                onChanged: _setB20Flag,
              ),
              SwitchListTile(
                title: const Text('用 Flutter 打开实验室'),
                subtitle: const Text('打开后，实验室列表和详情页走 Flutter 屏，终端和输出仍走原生；关掉就回到原生。'),
                value: _b21Enabled,
                onChanged: _setB21Flag,
              ),
              SwitchListTile(
                title: const Text('用 Flutter 打开短剧剧场 / 历史'),
                subtitle: const Text('打开后，短剧页的“剧场”和“历史”标签走 Flutter 屏，一点就播的竖流和播放器仍走原生；关掉就回到原生。'),
                value: _b22Enabled,
                onChanged: _setB22Flag,
              ),
            ],
          ),
        ),
      ),
    ),
  );
}
}

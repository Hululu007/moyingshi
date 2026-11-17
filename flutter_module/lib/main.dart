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
import 'pigeon/c1_messages.g.dart';

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
/// module binary serves every migrated screen (the C1/C2/B2 screens and
/// the settings family, among others). Asking the
/// host avoids depending on the cached-engine
/// FlutterFragment initialRoute API, whose availability in Flutter 3.50 was
/// not verified on this machine. A failed call falls back to '/' (the
/// unreachable placeholder below); the host re-asserts the real route.
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
    // Keep '/' as the safe default.
  }
  _currentTopRoute = initialRoute;
  runApp(C0ShellApp(initialRoute: initialRoute));
}

/// B8 screens, routed by the host.
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
        '/': (context) => const _UnreachablePlaceholder(),
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

/// Placeholder for the '/' route: unreachable in practice — every host
/// entry passes an explicit route, and the C0 experiment screen that used
/// to live here was removed by the master-switch change (see
/// docs/b8-master-switch.md). It only exists so route resolution never
/// fails if the host ever reports '/'.
class _UnreachablePlaceholder extends StatelessWidget {
  const _UnreachablePlaceholder();

  @override
  Widget build(BuildContext context) => const Scaffold();
}

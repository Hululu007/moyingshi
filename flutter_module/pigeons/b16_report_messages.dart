import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    // Same layout as c0/c1/c2/b2/b15_messages.dart: generated Dart code
    // lives inside the module; the generated Java code lands in the host
    // app source tree so Gradle compiles it. Paths are relative to the
    // directory `dart run pigeon` runs from (the module root, see
    // .github/workflows/b8-c0.yml).
    dartOut: 'lib/pigeon/b16_report_messages.g.dart',
    javaOut:
        '../app/src/main/java/com/github/moyingshi/osc/tv/flutter/B16Messages.java',
    javaOptions: JavaOptions(
      package: 'com.github.moyingshi.osc.tv.flutter',
      className: 'B16Messages',
    ),
  ),
)
@HostApi()
abstract class B16HostApi {
  /// Static chrome for the report page (labels + the range list).
  /// Synchronous: Dart renders the AppBar and the range selector from
  /// this before the first report snapshot arrives. All strings come
  /// from the host; Dart hard-codes no locale strings.
  B16ReportShell getReportShell();

  /// One-shot snapshot of the report for one range (native
  /// ViewingReportActivity.loadReport, in order: cache read unless
  /// forced, local generation, AI analysis when an AI service is
  /// configured, cache write). The AI call is 100% host-side; Dart
  /// only renders the returned summary/tags/insights. Runs on a
  /// background thread; completes exactly once on every path; the 35s
  /// watchdog completes with an error snapshot (Dart shows its
  /// error/retry state). forceRefresh clears the cache first (native
  /// refresh button / pull-to-refresh).
  @async
  B16ReportScreen getReport(String rangeName, bool forceRefresh);
}

/// Static page chrome. All display strings come from the host.
class B16ReportShell {
  B16ReportShell({
    required this.title,
    required this.refreshLabel,
    required this.loadingLabel,
    required this.emptyText,
    required this.retryLabel,
    required this.selectRangeTitle,
    required this.generateLabel,
    required this.cancelLabel,
    required this.ranges,
  });

  /// viewing_report_title (AppBar title).
  String title;

  /// viewing_report_refresh (refresh action tooltip).
  String refreshLabel;

  /// viewing_report_loading_local (shown while the first snapshot
  /// loads; the native "generating" / "AI analyzing" stage texts are
  /// not streamed — see docs/b8-b16-report.md §5 V2).
  String loadingLabel;

  /// viewing_report_empty (empty state, no records in range).
  String emptyText;

  /// error_retry, for the error state.
  String retryLabel;

  /// viewing_report_select_range (range dialog title).
  String selectRangeTitle;

  /// viewing_report_generate (range dialog confirm).
  String generateLabel;

  /// dialog_negative (range dialog cancel).
  String cancelLabel;

  /// All ranges in ViewingReportRange order, with host-computed
  /// display labels (year/quarter/month are time-dependent).
  List<B16RangeItem> ranges;
}

/// One selectable range (ViewingReportRange).
class B16RangeItem {
  B16RangeItem({
    required this.name,
    required this.displayLabel,
  });

  /// Enum name, e.g. "ALL", "THIS_MONTH".
  String name;

  /// ViewingReportRange.getDisplayLabel(), e.g. "全部", "本月 (11月)".
  String displayLabel;
}

/// One report snapshot. All display strings and computed values come
/// from the host (native ViewingReportActivity.fill* methods,
/// replicated in the impl); Dart only renders. Section content is a
/// pre-built multi-line string exactly like the native TextViews,
/// because the native page renders each section as one TextView.
class B16ReportScreen {
  B16ReportScreen({
    required this.rangeName,
    required this.rangeLabel,
    required this.isEmpty,
    required this.errorText,
    required this.overviewLabel,
    required this.overviewHours,
    required this.overviewSub,
    required this.timeTitle,
    required this.timeContent,
    required this.genreVisible,
    required this.genreTitle,
    required this.genreContent,
    required this.peopleVisible,
    required this.peopleTitle,
    required this.peopleContent,
    required this.aiVisible,
    required this.aiTitle,
    required this.aiSummary,
    required this.aiTags,
    required this.aiInsights,
    required this.badgesVisible,
    required this.badgesTitle,
    required this.badgeContent,
  });

  /// Echo of the requested range (host-parsed, unknown names fall back
  /// to "ALL"); the display label from getDisplayLabel().
  String rangeName;
  String rangeLabel;

  /// True when the range has no records (ViewingReport.isEmpty());
  /// Dart shows the shell empty text.
  bool isEmpty;

  /// Whole-snapshot error text (load timeout: error_play_timeout).
  /// Empty means no error; Dart shows its error/retry state when
  /// non-empty (B8 errorScreen precedent).
  String errorText;

  /// viewing_report_overview_watch + the big hours line ("186.5 小时")
  /// + the two-line sub ("📺 观看 N 部作品 · M 集\n🏆 完播率 N%").
  String overviewLabel;
  String overviewHours;
  String overviewSub;

  /// viewing_report_time_pattern + the time-distribution text
  /// (top slot / weekend ratio / average watch / late-night count).
  String timeTitle;
  String timeContent;

  /// viewing_report_content_preference + the genre lines (emoji +
  /// name + percent, then TV/movie ratio and completion rate). The
  /// card is hidden when both genre lists are empty and both ratios
  /// are 0 (native fillPreference).
  bool genreVisible;
  String genreTitle;
  String genreContent;

  /// viewing_report_people_preference + the actor/director lines.
  /// The card is hidden when both lists are empty (native fillPeople).
  bool peopleVisible;
  String peopleTitle;
  String peopleContent;

  /// viewing_report_ai_persona + summary + style tags + insights.
  /// Visible only when the report was AI-analyzed and has a summary
  /// (native fillAi). AI not configured or the call failing degrades
  /// silently to hidden, like native.
  bool aiVisible;
  String aiTitle;
  String aiSummary;

  /// Style tags, rendered as chips (native ChipGroup).
  List<String> aiTags;

  /// Insights, one "• ..." line each; empty means the insights view
  /// is hidden (native fillAi).
  String aiInsights;

  /// viewing_report_badges + the badge lines (icon + name, optional
  /// description). The card is hidden when there are no badges
  /// (native fillBadges).
  bool badgesVisible;
  String badgesTitle;
  String badgeContent;
}

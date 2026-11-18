import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    // Same layout as c0/c1/c2/b2_messages.dart: generated Dart code lives
    // inside the module; the generated Java code lands in the host app
    // source tree so Gradle compiles it. Paths are relative to the
    // directory `dart run pigeon` runs from (the module root, see
    // .github/workflows/b8-c0.yml).
    dartOut: 'lib/pigeon/b3_settings_a_messages.g.dart',
    javaOut:
        '../app/src/main/java/com/github/moyingshi/osc/tv/flutter/B3Messages.java',
    javaOptions: JavaOptions(
      package: 'com.github.moyingshi.osc.tv.flutter',
      className: 'B3Messages',
    ),
  ),
)
@HostApi()
abstract class B3HostApi {
  /// TMDB settings page (native SettingTmdbFragment): one snapshot with
  /// every row's title, value text and visibility, so Dart never sees a
  /// torn state between two calls. All strings are resolved by the host.
  B3TmdbSettings getTmdbSettings();

  /// Opens the native TMDB source dialog. Async: the Future completes
  /// when the dialog dismisses (any outcome), because a native dialog
  /// produces no Activity lifecycle event for Dart to refresh on. Dart
  /// re-fetches getTmdbSettings() after awaiting this.
  @async
  void openTmdbSource();

  /// Opens the native single-choice dialog for the detail open mode.
  /// Async: completes on dismiss (selection writes Setting first).
  @async
  void openDetailOpenMode();

  /// Opens the native single-choice dialog for the detail theme mode.
  /// Only reachable while the theme row is visible (TMDB open mode).
  /// Async: completes on dismiss.
  @async
  void openDetailThemeMode();

  /// Cycles the TMDB match mode to the next value (native tap behavior).
  /// Dart re-fetches getTmdbSettings() afterwards.
  void cycleTmdbMatchMode();

  /// Flips the episode-file-size switch. Dart re-fetches afterwards.
  void toggleTmdbEpisodeFileSize();

  /// Flips the history-aggregation switch (also posts RefreshEvent,
  /// like the native row). Dart re-fetches afterwards.
  void toggleHistoryAggregation();

  /// AI settings page (native SettingAiFragment): one snapshot.
  B3AiSettings getAiSettings();

  /// Opens the native AI config dialog. Async: completes on dismiss.
  @async
  void openAiConfig();

  /// Flips personal recommendation: turning it off writes Setting
  /// directly; turning it on shows the native confirm dialog first.
  /// Async: completes on dismiss.
  @async
  void togglePersonalRecommendation();

  /// Opens the native recommendation-feedback dialog (mobile-only class,
  /// opened reflectively by the host). Async: completes when the dialog
  /// reports a change; closing it without a change completes nothing,
  /// and Dart does not await these calls (see the dialog contract in
  /// docs/b8-b3-settings-a.md), so no refresh is owed then.
  @async
  void openRecommendationFeedback();

  /// Opens the native realtime-model picker dialog. Async: completes
  /// when a model is picked (download starts in the background) or a
  /// model delete finishes; closing the picker without picking completes
  /// nothing, and Dart does not await these calls. While a model is
  /// downloading or being deleted, Dart polls getAiSettings() so the
  /// progress text stays live.
  @async
  void openRealtimeModel();

  /// Opens the native number dialog for AI subtitle max concurrency
  /// (1-8). Async: completes when a value is picked.
  @async
  void setSubtitleAiMaxConcurrency();

  /// Opens the native number dialog for AI subtitle chunk count (1-32).
  /// Async: completes when a value is picked.
  @async
  void setSubtitleAiChunkCount();

  /// Ad settings page (native SettingAdFragment): one snapshot. Each
  /// row's summary is computed by the host inside a try/catch mirroring
  /// the native safeRun(): on failure the row carries the host error
  /// text and its error flag, and Dart renders it in the error style.
  B3AdSettings getAdSettings();

  /// Flips the adblock switch. Dart re-fetches afterwards.
  void toggleAdblock();

  /// Flips the AI ad-detection switch. Dart re-fetches afterwards.
  void toggleAiAdDetection();

  /// Opens the native ad-rule manager dialog (mobile-only class, opened
  /// reflectively by the host). Async: completes when the dialog reports
  /// a rule change.
  @async
  void openAdRuleManage();

  /// Opens the native ad-block stats dialog (mobile-only class, opened
  /// reflectively by the host). Fire-and-forget: it changes no setting.
  void openAdBlockStats();

  /// Cycles the auto skip intro/outro mode to the next value.
  /// Dart re-fetches afterwards.
  void cycleAutoSkipIntroOutro();

  /// Opens the native intro-skip-kinds multi-choice dialog. Async:
  /// completes when the dialog reports a change.
  @async
  void openIntroSkipKinds();

  /// Danmaku settings page (native SettingDanmakuFragment): one
  /// snapshot. The four-level visibility chain (load -> api -> auto ->
  /// spider) is computed entirely by the host; Dart only renders the
  /// visible flags it is given.
  B3DanmakuSettings getDanmakuSettings();

  /// Flips the danmaku load switch. Dart re-fetches afterwards.
  void toggleDanmakuLoad();

  /// Opens the native danmaku API dialog (mobile-only class, opened
  /// reflectively by the host through a headless DanmakuListener
  /// fragment). Async: completes when an API URL is saved.
  @async
  void openDanmakuApi();

  /// Flips the danmaku auto-load switch. Dart re-fetches afterwards.
  void toggleDanmakuAuto();

  /// Flips the spider-first switch. Dart re-fetches afterwards.
  void toggleDanmakuSpider();

  /// Finishes the host Activity (back navigation out of the family).
  void closeScreen();
}

class B3TmdbSettings {
  B3TmdbSettings({
    required this.screenTitle,
    required this.sourceTitle,
    required this.sourceText,
    required this.openModeTitle,
    required this.openModeText,
    required this.themeModeTitle,
    required this.themeModeText,
    required this.themeModeVisible,
    required this.matchModeTitle,
    required this.matchModeText,
    required this.episodeFileSizeTitle,
    required this.episodeFileSizeText,
    required this.historyAggregationTitle,
    required this.historyAggregationText,
    required this.historyAggregationVisible,
  });

  String screenTitle;
  String sourceTitle;
  String sourceText;
  String openModeTitle;
  String openModeText;
  String themeModeTitle;
  String themeModeText;
  bool themeModeVisible;
  String matchModeTitle;
  String matchModeText;
  String episodeFileSizeTitle;
  String episodeFileSizeText;
  String historyAggregationTitle;
  String historyAggregationText;
  bool historyAggregationVisible;
}

class B3AiSettings {
  B3AiSettings({
    required this.screenTitle,
    required this.recommendationGroupTitle,
    required this.recommendationTitle,
    required this.recommendationText,
    required this.personalTitle,
    required this.personalText,
    required this.feedbackTitle,
    required this.feedbackText,
    required this.subtitleGroupTitle,
    required this.subtitleAiSettingsVisible,
    required this.realtimeModelTitle,
    required this.realtimeModelText,
    required this.realtimeModelDownloading,
    required this.realtimeModelDeleting,
    required this.maxConcurrencyTitle,
    required this.maxConcurrencyValue,
    required this.chunkCountTitle,
    required this.chunkCountValue,
  });

  String screenTitle;
  String recommendationGroupTitle;
  String recommendationTitle;
  String recommendationText;
  String personalTitle;
  String personalText;
  String feedbackTitle;
  String feedbackText;
  String subtitleGroupTitle;
  bool subtitleAiSettingsVisible;
  String realtimeModelTitle;
  String realtimeModelText;
  bool realtimeModelDownloading;
  bool realtimeModelDeleting;
  String maxConcurrencyTitle;
  int maxConcurrencyValue;
  String chunkCountTitle;
  int chunkCountValue;
}

class B3AdSettings {
  B3AdSettings({
    required this.screenTitle,
    required this.basicGroupTitle,
    required this.adblockTitle,
    required this.adblockText,
    required this.adblockError,
    required this.aiAdDetectionTitle,
    required this.aiAdDetectionText,
    required this.aiAdDetectionVisible,
    required this.aiAdDetectionError,
    required this.ruleGroupTitle,
    required this.ruleManageTitle,
    required this.ruleManageText,
    required this.ruleManageError,
    required this.blockStatsTitle,
    required this.segmentGroupTitle,
    required this.autoSkipTitle,
    required this.autoSkipText,
    required this.autoSkipError,
    required this.introSkipKindsTitle,
    required this.introSkipKindsText,
    required this.introSkipKindsError,
  });

  String screenTitle;
  String basicGroupTitle;
  String adblockTitle;
  String adblockText;
  bool adblockError;
  String aiAdDetectionTitle;
  String aiAdDetectionText;
  bool aiAdDetectionVisible;
  bool aiAdDetectionError;
  String ruleGroupTitle;
  String ruleManageTitle;
  String ruleManageText;
  bool ruleManageError;
  String blockStatsTitle;
  String segmentGroupTitle;
  String autoSkipTitle;
  String autoSkipText;
  bool autoSkipError;
  String introSkipKindsTitle;
  String introSkipKindsText;
  bool introSkipKindsError;
}

class B3DanmakuSettings {
  B3DanmakuSettings({
    required this.screenTitle,
    required this.loadTitle,
    required this.loadText,
    required this.apiTitle,
    required this.apiText,
    required this.apiVisible,
    required this.autoTitle,
    required this.autoText,
    required this.autoVisible,
    required this.spiderTitle,
    required this.spiderText,
    required this.spiderVisible,
  });

  String screenTitle;
  String loadTitle;
  String loadText;
  String apiTitle;
  String apiText;
  bool apiVisible;
  String autoTitle;
  String autoText;
  bool autoVisible;
  String spiderTitle;
  String spiderText;
  bool spiderVisible;
}

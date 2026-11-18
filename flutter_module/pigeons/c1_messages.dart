import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    // Same layout as c0_messages.dart: generated Dart code lives inside
    // the module; the generated Java code lands in the host app source tree
    // so Gradle compiles it. Paths are relative to the directory
    // `dart run pigeon` runs from (the module root, see
    // .github/workflows/b8-c0.yml).
    dartOut: 'lib/pigeon/c1_messages.g.dart',
    javaOut:
        '../app/src/main/java/com/github/moyingshi/osc/tv/flutter/C1Messages.java',
    javaOptions: JavaOptions(
      package: 'com.github.moyingshi.osc.tv.flutter',
      className: 'C1Messages',
    ),
  ),
)
@HostApi()
abstract class C1HostApi {
  /// Route this engine should show first: '/' (C0 shell), '/about' or
  /// '/subtitle'. Dart main() awaits this before runApp; the C0 engine's
  /// impl returns '/', the C1 engine's impl returns the route from its
  /// Activity intent.
  String getInitialRoute();

  /// All display strings for the About screen, resolved by the host from
  /// resources so Dart never hard-codes locale strings.
  C1AboutInfo getAboutInfo();

  /// Toggles the hidden-settings switch (the version-line 5-tap easter egg).
  /// The host writes Setting and shows the toast; returns the new state.
  bool toggleHiddenSettings();

  /// Runs the native updater flow on the host Activity.
  void checkUpdate();

  /// Opens the native update-settings dialog.
  void openUpdateSettings();

  /// Opens the native GitHub-proxy dialog (reused from AboutDialog).
  void openGithubProxy();

  /// All display strings and state for the subtitle settings screen.
  C1SubtitleInfo getSubtitleInfo();

  void setSubtitleAutoMatch(bool enabled);

  /// Opens the native single-choice language dialog; writes Setting on
  /// pick. Dart re-fetches getSubtitleInfo() on resume.
  void openSubtitleLanguage();

  /// Opens the native subtitle-source summary dialog. Dart re-fetches
  /// getSubtitleInfo() on resume.
  void openSubtitleSource();

  /// Finishes the host Activity (the "I understand" button).
  void closeScreen();
}

class C1AboutInfo {
  C1AboutInfo({
    required this.title,
    required this.badge,
    required this.versionLine,
    required this.disclaimer,
    required this.checkUpdateLabel,
    required this.githubProxyLabel,
    required this.confirmLabel,
    required this.updateSettingsLabel,
    required this.hiddenSettingsEnabled,
  });

  String title;
  String badge;
  String versionLine;
  String disclaimer;
  String checkUpdateLabel;
  String githubProxyLabel;
  String confirmLabel;
  String updateSettingsLabel;
  bool hiddenSettingsEnabled;
}

class C1SubtitleInfo {
  C1SubtitleInfo({
    required this.screenTitle,
    required this.autoMatchTitle,
    required this.autoMatchEnabled,
    required this.onText,
    required this.offText,
    required this.languageTitle,
    required this.languageText,
    required this.languageLabels,
    required this.languageValues,
    required this.currentLanguage,
    required this.sourceTitle,
    required this.sourceText,
  });

  String screenTitle;
  String autoMatchTitle;
  bool autoMatchEnabled;
  String onText;
  String offText;
  String languageTitle;
  String languageText;
  List<String> languageLabels;
  List<String> languageValues;
  String currentLanguage;
  String sourceTitle;
  String sourceText;
}

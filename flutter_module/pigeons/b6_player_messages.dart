import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    // Same layout as c0_messages.dart / c1_messages.dart: generated Dart
    // code lives inside the module; the generated Java code lands in the
    // host app source tree so Gradle compiles it. Paths are relative to
    // the directory `dart run pigeon` runs from (the module root, see
    // .github/workflows/b8-c0.yml).
    dartOut: 'lib/pigeon/b6_player_messages.g.dart',
    javaOut:
        '../app/src/main/java/com/github/moyingshi/osc/tv/flutter/B6Messages.java',
    javaOptions: JavaOptions(
      package: 'com.github.moyingshi.osc.tv.flutter',
      className: 'B6Messages',
    ),
  ),
)
@HostApi()
abstract class B6HostApi {
  /// B6 feature flag (persisted in Setting, default false = native page).
  bool isB6Enabled();
  void setB6Enabled(bool enabled);

  /// One snapshot of the mobile player settings page
  /// (SettingPlayerFragment): all 23 visible rows plus the visibility
  /// flags the host computes (pad-only, MPV-kernel-only, caption
  /// availability). The 16 performance rows the native page hides via
  /// hidePerformanceRows() are not in this snapshot — they are managed
  /// through the exo4kCompat performance dialog instead. Every title
  /// and value text comes from the host; Dart only renders.
  B6PlayerSettings getPlayerSettings();

  /// Sync toggles (Dart `act` pattern: call, then reload the snapshot).
  /// Each mirrors the native row's click handler exactly.
  void toggleBlurayMenu();
  void toggleCaption();
  void toggleAutoPlay();
  void toggleAutoChange();
  void toggleDeferFlagSwitch();
  void toggleRememberBrightness();
  void toggleLiveSourceFallback();
  void toggleMusicNotification();
  void toggleAudioBookNotification();

  /// Pad-only row: cycles the pad live mode (+1 mod the option count),
  /// mirroring the native setPadLiveMode().
  void cyclePadLiveMode();

  /// Native dialogs (Dart `afterDialog` pattern: never awaited, reload
  /// on completion). Each completes its Future only when the dialog
  /// reports a change; closing without a change completes nothing and
  /// needs no reload. The one exception is openSpeed: SpeedDialog calls
  /// setSpeed on both its positive and its negative button, so it
  /// always completes on close (with the new value or the original one).
  @async
  void openPlayerButtons();
  @async
  void openExo4kCompat();
  @async
  void openKernel();
  @async
  void openMpvConfig();
  @async
  void openMpvRender();
  @async
  void openScale();
  @async
  void openLut();
  @async
  void openOsd();
  @async
  void openOsdMediaBadgePosition();
  @async
  void openBackground();
  @async
  void openFailureFallback();
  @async
  void openUa();
  @async
  void openSpeed();

  /// Caption row long-press: opens the system captioning settings, but
  /// only when captions are enabled — mirroring the native onCaption().
  /// Fire-and-forget; Dart re-fetches on resume when the user returns.
  void openSystemCaptioning();
}

class B6PlayerSettings {
  B6PlayerSettings({
    required this.screenTitle,
    required this.playerButtonsTitle,
    required this.playerButtonsText,
    required this.padLiveVisible,
    required this.padLiveTitle,
    required this.padLiveText,
    required this.exo4kCompatTitle,
    required this.exo4kCompatText,
    required this.kernelTitle,
    required this.kernelText,
    required this.mpvVisible,
    required this.mpvConfigTitle,
    required this.mpvConfigText,
    required this.mpvRenderTitle,
    required this.mpvRenderText,
    required this.blurayMenuTitle,
    required this.blurayMenuText,
    required this.scaleTitle,
    required this.scaleText,
    required this.lutTitle,
    required this.lutText,
    required this.captionVisible,
    required this.captionTitle,
    required this.captionText,
    required this.osdTitle,
    required this.osdText,
    required this.osdMediaBadgePositionTitle,
    required this.osdMediaBadgePositionText,
    required this.autoPlayTitle,
    required this.autoPlayText,
    required this.autoChangeTitle,
    required this.autoChangeText,
    required this.deferFlagSwitchTitle,
    required this.deferFlagSwitchText,
    required this.rememberBrightnessTitle,
    required this.rememberBrightnessText,
    required this.liveSourceFallbackTitle,
    required this.liveSourceFallbackText,
    required this.failureFallbackTitle,
    required this.failureFallbackText,
    required this.speedTitle,
    required this.speedText,
    required this.musicNotificationTitle,
    required this.musicNotificationText,
    required this.audioBookNotificationTitle,
    required this.audioBookNotificationText,
    required this.backgroundTitle,
    required this.backgroundText,
    required this.uaTitle,
    required this.uaText,
  });

  String screenTitle;

  /// "已配置 x/y 个按钮" — the host formats
  /// R.string.player_button_config_summary with the visible/total
  /// counts, exactly like the native setPlayerButtonsText().
  String playerButtonsTitle;
  String playerButtonsText;

  /// Pad-only row (ResUtil.isPad()); hidden on phones.
  bool padLiveVisible;
  String padLiveTitle;
  String padLiveText;

  /// Performance-dialog summary (PlaybackPerformanceSetting.getSummary()).
  String exo4kCompatTitle;
  String exo4kCompatText;

  String kernelTitle;
  String kernelText;

  /// True when the current kernel is MPV; gates the three MPV rows.
  bool mpvVisible;
  String mpvConfigTitle;
  String mpvConfigText;
  String mpvRenderTitle;
  String mpvRenderText;
  String blurayMenuTitle;
  String blurayMenuText;

  String scaleTitle;
  String scaleText;

  String lutTitle;
  String lutText;

  /// Shown only when the device has a captioning service
  /// (PlayerSetting.hasCaption()).
  bool captionVisible;
  String captionTitle;
  String captionText;

  /// Checked OSD items joined with " / "; setting_off when none.
  String osdTitle;
  String osdText;

  String osdMediaBadgePositionTitle;
  String osdMediaBadgePositionText;

  String autoPlayTitle;
  String autoPlayText;
  String autoChangeTitle;
  String autoChangeText;
  String deferFlagSwitchTitle;
  String deferFlagSwitchText;
  String rememberBrightnessTitle;
  String rememberBrightnessText;
  String liveSourceFallbackTitle;
  String liveSourceFallbackText;
  String failureFallbackTitle;
  String failureFallbackText;

  /// DecimalFormat("0.#") value plus the R.string.times ("倍") suffix,
  /// concatenated by the host — the native row shows the two as adjacent
  /// TextViews with no space between them.
  String speedTitle;
  String speedText;

  String musicNotificationTitle;
  String musicNotificationText;
  String audioBookNotificationTitle;
  String audioBookNotificationText;
  String backgroundTitle;
  String backgroundText;

  /// Current UA string verbatim.
  String uaTitle;
  String uaText;
}

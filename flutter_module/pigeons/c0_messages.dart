import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(
  PigeonOptions(
    // Generated Dart code lives inside the module; the generated Java code
    // lands in the host app source tree so Gradle compiles it. Paths are
    // relative to the directory `dart run pigeon` runs from (the module
    // root, see .github/workflows/b8-c0.yml).
    dartOut: 'lib/pigeon/c0_messages.g.dart',
    javaOut:
        '../app/src/main/java/com/github/moyingshi/osc/tv/flutter/C0Messages.java',
    javaOptions: JavaOptions(
      package: 'com.github.moyingshi.osc.tv.flutter',
      className: 'C0Messages',
    ),
  ),
)
@HostApi()
abstract class C0HostApi {
  /// Returns one line of native host info (app version, device model, SDK
  /// level). The single C0 round-trip: Dart button press -> native -> Dart.
  String getHostInfo();
}

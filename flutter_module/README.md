# moyingshi_flutter — B8 C0 shell module

Hand-written equivalent of `flutter create -t module` output (this workspace
has no Flutter SDK, so the module was authored by hand from the official
add-to-app docs). It contains one placeholder screen and one Pigeon API.

Do not edit `lib/pigeon/` or `.android/`: both are generated in CI
(`flutter pub get` + `dart run pigeon`).

Everything about the C0 shell — design choices, version locks, CI steps,
device acceptance, rollback — lives in `docs/b8-c0-shell.md`.

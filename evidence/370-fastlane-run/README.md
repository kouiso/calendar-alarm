fastlane documentation
----

# Installation

Make sure you have the latest version of the Xcode command line tools installed:

```sh
xcode-select --install
```

For _fastlane_ installation instructions, see [Installing _fastlane_](https://docs.fastlane.tools/#installing-fastlane)

# Available Actions

## iOS

### ios fad

```sh
[bundle exec] fastlane ios fad
```

AdHoc 署名で IPA をビルドする

### ios testflight_upload

```sh
[bundle exec] fastlane ios testflight_upload
```

AppStore 署名の IPA を作り TestFlight へ上げる（手元の Mac 用。match は readonly 固定。upload:false で IPA 作成まで）

### ios adhoc_local

```sh
[bundle exec] fastlane ios adhoc_local
```

AdHoc 署名で IPA を作る（手元の Mac から実機へ入れる用。match は readonly 固定で何も書かない）

----

This README.md is auto-generated and will be re-generated every time [_fastlane_](https://fastlane.tools) is run.

More information about _fastlane_ can be found on [fastlane.tools](https://fastlane.tools).

The documentation of _fastlane_ can be found on [docs.fastlane.tools](https://docs.fastlane.tools).

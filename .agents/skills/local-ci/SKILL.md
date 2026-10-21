---
name: local-ci
description: calendar-alarm の変更を確かめる時に、どの検査をどの順で手元で回すか（Android unit・debug APK・lint・iOS simulator ビルド・emulator の停止 E2E）。
---

# local-ci

変更の種類ごとに、手元で回す検査を選ぶ。入口は CI と同じ `scripts/ci.sh`（詳細は `doc/ci-local.md`）。

| 変えたもの | 回す検査 |
|---|---|
| `shared/` だけ | `bash scripts/ci.sh fast` |
| `androidApp/` | `bash scripts/ci.sh all` と `./gradlew :androidApp:lintDebug` |
| 鳴動・停止の経路（`engine/`） | 上に加えて emulator の停止 E2E（`task ci:emulator:build` → `task ci:emulator`） |
| `iosApp/` | `bash scripts/ci.sh ios`（Xcode と xcodegen が要る。署名・実機は対象外） |
| `iosApp/iosApp/ring-history.swift` | 上に加えて `bash scripts/ci.sh ios-logic`（swiftc だけで動く単体テスト） |
| `AGENTS.md` か `.agents/skills/` | `node scripts/sync-ai-rule.mjs --check` |

- Android の成功を iOS や emulator の成功として扱わない。
- 実機で音を出す確認は、持ち主の同意を取ってからにする。
- emulator の陰性対照は `doc/ci-local.md` のコミットを使う。修正側だけの成功で二つのレーンの成功とはしない。

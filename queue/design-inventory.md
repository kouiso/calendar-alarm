# calendar-alarm design inventory (steps 1-3, code-reading pass)

Source: design/project/ (27 boards) read at origin/master 0e5e6f2 (newer than 89af2d1; #370 is already merged there).
Design CSS: design/asset/41384cd4....css is in the repo and gives exact values (phone frame 360x740, .card r20 pad 14/16, .sw 52x32,
.c chip h40 r20 accent-filled when on, .seg r16 pad2 gap2 / buttons h40 r12, .hero r22 + 2 rings 148/104, .tab bottom bar h72 + pill 56x30,
.dial 240 (inner inset 24) + time 92/200, .rt 25/600, .snz h64 r32 19/600, .track h72 knob 60 pad 6 label 17/600, .sheet r28, .dlg r28,
.btn h52 r26, .h1 21 / .h1.lg 27, .big 44/300, .eye 11/600 ls .14em, --out #7B8494 for switch-off, --ng #FF8A80, --ok #6FD6A6).
Tokens: Palettes.kt (10 themes, dark primaries) and Theme.swift accents match ds.dc.html. Neutral surfaces match (#0A0C11/#12151C/#1F2430/#ECEEF3/#9AA3B2).

## Counts
- Boards: 27 (23 screen boards + 4 index/rule boards: Main, ds, todo, d68/Main).
- Columns/states on screen boards: 74 (p140-p155: 41, d68: 33).
- Class totals (74 states): 実装あり 14, tagged (#n) 40 (about 17 of these also carry untagged extras), untagged-only difference 9, 未実装の案 8, 案A／B 3. Pairs that differ in code: 60 of 74 (rest match or are unbuilt). Row 34 (tab bar) is a cross-board row, not a state.

Class legend: A = 実装あり (board says so / matches code; residual size/colour gaps are listed but already inside a shape PBI);
T(#n) = difference with an owning issue/PR; U = untagged difference (no owner); P = 未実装の案; AB = 案A／B.
Spec column: S = item exists in doc/spec (kmp-replacement.md / user-stories.md); "no" = not in spec; "open" = spec lists as undecided.
Shape PBIs: #316 agenda, #317 alarms, #318 timer, #319 settings, #320 ring, #321 perm, #322 ask, #323 tabs, #324 troubleshoot (Android); iOS siblings #339 #341 #343 #345 #347 #349 #351 #353; Android SBIs #338 #340 #342 #344 #346 #348 #350 #352 #354. They own: surface/switch/chip/seg sizes, padding, radius, hero rings, background/surface/text colour variables. They do NOT own: wording, order, headings, type sizes, status colours, board-HTML spacing (said so in #316/#319 bodies).
Device: "emu" = Android needs emulator/device to confirm look (code fix is cloud-possible); "Mac" = iOS needs Mac.

## Table (board / state -> Android | iOS -> differences -> owner -> class -> spec -> device)

### d68 home (R2 予定画面), 420px columns
| # | Board / state | Android | iOS | Differences (board vs code) | Owner | Class | Spec | Dev |
|---|---|---|---|---|---|---|---|---|
| 1 | home 1 ふだん | AgendaScreen.kt:AgendaScreen/NextAlarmBanner/AgendaList/EventRow | AgendaView.swift:AgendaView/AgendaNextAlarmCard/DayCard/EventRow | And: date H1 30sp (AgendaScreen.kt:161) vs 27/600; hero time 36sp (:385) vs 44/300; eye 12sp ls1sp (:370) vs 11/.14em; no double rings (:354-404); status line always muted (:398) vs green/red dot; "N分前" chip at row right (:591-606) vs under title; row title bodyLarge 16 (:552) vs 15/500; time col 52 vs 50; cards r22 (:446) vs r20; 今日/明日 as filled chip (:483-497) vs plain accent/muted text, date not Outfit. iOS: bg systemGroupedBackground (AgendaView.swift:88) vs --bg; day header shows only "今日"/"明日" with no date (:153-158) and fixed indigo (:418+); time "09:00" no "~10:00" (:512-519); chip has no "+1"; hero time 36 (:289+) | rings/size/colour vars: #316 #338 #339; chip place #227 #330 #331; rest untagged | T(#316,#227,#330) + U | S (F-2) | emu / Mac |
| 2 | home 2 許可が足りない・鳴らせなかった | AgendaScreen.kt:170-183 StatusBanner :408 | AgendaView.swift:34-46, StatusBanner :324 | And: banner uses errorContainer #93000A/onErrorContainer (:413,423) vs --ngs/--ng text, min-h 56; action "原因を見る" ok. iOS: orange triangle + orange tint, action "見る" in indigo (:42), goes to MissedAlarmsView not troubleshooting | none (new) | U | S (S10) | emu / Mac |
| 3 | home 3 予定がない | AgendaScreen.kt:265-289 | AgendaView.swift:200-216 | And: plain 40dp DateRange icon, title 14sp (:276) vs .ico.muc 60px circle with outer ring and 16px title; copy matches. iOS: SF icon, 15pt title; copy matches | none (new) | U | S (S11) | emu / Mac |
| 4 | home 4 予定の詳細 | AgendaScreen.kt:EventDetailSheet 632-789 (ModalBottomSheet) | EventDetailSheet.swift (system sheet, detents) | And: title 30sp Bold (:662) vs 25/600; Material SegmentedButtons (:715-759) vs .seg; extras FilterChip vs .c; weather card surfaceContainerLow r16 (:806) vs raised r18; two-space date line vs "　". iOS: labels "アラーム/通知/OFF" (:84-90) vs アラーム/通知のみ/鳴らさない; no end time (dateStr :193); minutes as 3x2 grid not seg; extras "+5" vs "+5分前" (:131); location row below chips vs above weather; indigo hard-coded (:77,107) | wording #224 #325 #326; seg/chip shape #316; rest untagged | T(#224,#316) + U | S (R3, §9.5) | emu / Mac |

### d68 views
| 5 | views 1 3日 | AgendaViews.kt:ThreeDayView 196-264 | AgendaViewsIOS.swift:ThreeDayColumnsView | And: day head 22sp (:223) vs 20/500; block text bodySmall 12 vs 13/500; alpha .15 vs .16. iOS: not read in detail (same shape, unchecked) | none (new) | U | S (§9.5) | emu / Mac |
| 6 | views 2 月 | AgendaViews.kt:MonthView 65-192 | AgendaViewsIOS.swift:MonthGridView | And: cell aspectRatio .72 (~70dp, :108) vs 40px; weekday labelSmall 11 vs 12/500; Sun #FF8F8F / Sat #7D88FF (:80-81) vs --now #FF6B6B / --gg #8AB4F8; out-of-month alpha .35 vs .55; day circle 34/17sp vs 30/16 | none (new; #316 lists the column but no value) | U | S (月/週ビュー) | emu / Mac |
| 7 | views 3 時間軸 | AgendaViews.kt:DayTimelineView 268-394 | AgendaViewsIOS.swift:DayTimelineViewIOS | And: block r6 pad 8x2 (:347,350) vs r10 pad 10x5; text 12 vs 13/500; hour label w44 left (:323) vs w36 right-aligned; line alpha .4 vs solid --div; now line/label/dot OK | none (new) | U | S | emu / Mac |

### d68 ring (R1/②)
| 8 | ring 1 鳴動画面 | engine/RingingActivity.kt:RingingScreen 325-532 (+ RingingSlideSpec.kt) | RingingView.swift | And: ring 290/230 (:391-415) vs 240/inset24; time 112sp (:435 area) vs 92/200; title 32sp (:437) vs 25/600; snooze h80 22sp (RingingSlideSpec, :472) vs h64 r32 19/600; track h80 thumb 64 pad 8 label 20 (:606) vs 72/60/6/17; "スヌーズ時間を選ぶ ▾" text glyph vs chevron icon; accent/date-pill/copy OK. iOS: rings 224/196 (RingingView.swift:62-63), time 112 (:65), snooze h80 (:113) | sizes #320 #346 #347; fonts untagged | T(#320,#346) + U | S (R1, F-4) | emu / Mac |
| 9 | ring 2 スヌーズ時間を開いた時 | RingingActivity.kt:490-505 | RingingView.swift:141-153 | +N分 pills h40 r14 OK; up to 6 OK | - | A | S (F-5) | - |
| 10 | ring 3 ロック画面の通知 | AlarmService.kt buildNotification :525-585 | OffAppStopPlan / AlarmKit | Copy OK ("停止", 「停止」でそのまま…); iOS uses OS alarm UI per board note. Overlay (RingingOverlay.kt:150-184: accent #BDBDFF fixed, no date/rings, time 96) is #229/#336 | #229 #336 | T(#229) | S (P-LOCK) | emu / Mac |

### d68 settings ⑤ (all in one scroll today: SettingsScreen.kt:123-470 / SettingsView.swift)
| 11 | settings 1 設定のトップ | SettingsScreen.kt:RingTimesCard 1608, RingStatusCard 501 (expandable card) | SettingsView.swift:878 / :492 | Hub of 8 named rows with value at right is missing: code is one long list of cards. "鳴る時刻": one amber text line + long copy (:1613-1660) vs two chips 8:50（アプリ）amber / 8:55（Google）blue + "同じ時刻なら1回だけ鳴ります。". "鳴動の状態" is an expand/collapse card, not a row that opens a screen; its inner screen has no board | #211 (hub, headings) #319 #344 #345 | T(#211,#319) | S (R4, S9) | emu / Mac |
| 12 | settings 2 鳴らす時刻 | SettingsScreen.kt:RingTimesCard chips (:42-60 of the defaults card), :293-307 | SettingsView.swift:330 | Separate screen missing; "カレンダーの通知設定を使う" vs "カレンダーの通知に従う" (p142 board still shows old text: boards disagree, d68 newer); chips "5分" vs "5分前"; "全部の予定に足す" + 1日前 chip row missing in this card (lives in EventRules card, :316-360); timeline strip (8:50/8:55/9:00 dots) missing | #207 | T(#207) | S (F-3, S9) | emu / Mac |
| 13 | settings 3 鳴らし方 | SettingsScreen.kt:189-210 | SettingsView.swift (EventRules) | Labels 通知/OFF vs 通知のみ/鳴らさない; no headings "予定の開始"/"「○分前」の通知"; legend rows and the two pointer rows missing | #211 #224 #325 #326 | T(#224,#211) | S (#142) | emu / Mac |
| 14 | settings 4 鳴らす予定 | SettingsScreen.kt:EventRulesCard 889-1092, EventTypeRingSwitches 1665 | SettingsView.swift:300-321 | Invite chips FilterChip (:229-237) wrap fix is PR #363; "タイトルコード / ○n ×m / 編集" (:218-224) vs "タイトルの言葉で決める"; type switches OK; invite heading missing | #204 #363 #203 #211 | T(#204,#203) | S (§7.1) | emu / Mac |
| 15 | settings 5 見た目 | SettingsScreen.kt:AppearanceCard 1426-1538 | SettingsView.swift:~170-215 | Swatch colour = palette.light.primary (:1473) so dark mode shows light-mode colours; default marks INDIGO (:1477,1485); mark is white VerifiedUser 16dp vs dark check + ring (3px card + 5px tx); 38 vs 40; 背景 row ok; week rows OK | #225 #327 #328 | T(#225) | S (#106) | emu / Mac |

### d68 perm / ask / accounts / tabs
| 16-21 | ask 1-6 (カレンダー/通知/正確なアラーム/全画面/まだオフ/準備OK) | PermissionAsker.kt:AskSheetBody 120-155 (one body for all six) | PermissionAsker.swift:79-129, CalendarAlarmApp.swift:163-190 | Copy matches all 6 (strings.xml:78-104, ask_*). Missing: .ico 60px circle with double ring (ok/ng/ac variants); title titleLarge 22 vs 21/600; button r16 (:130) vs pill r26; Android list shows "許可が4つ…" behind: ok. iOS: iPhone-specific copy ok (Permissions.swift:29-39), presentationDetents .medium | #322 #350 #351 | T(#322) x6 | S (S1, #72) | emu / Mac |
| 22-24 | perm 1 全部未許可 / 2 未許可がある / 3 全部許可済み | Permissions.kt:PermissionsScreen 214-379 | Permissions.swift:150-195 | Copy OK. Differences: warn = colorScheme.error #FFB4AB (:263) vs --ng #FF8A80; icon disc 36 vs 40; .top custom bar vs headlineSmall row; floating tab bar still shown here (MainActivity.kt:297) but board has none; summary Card r22 vs r20; button r16 vs 26 | #321 #348 (shape); colour/tab-bar untagged | T(#321) + U | S (S2, #71) | emu / Mac |
| 25 | perm 4 iPhone の権限 | - | Permissions.swift:PermissionsView | Board: 3 rows カレンダー/アラーム/通知 + button "全部許可する"/"すべて許可済み": copy OK; native NavigationStack title vs board's custom .top | #349 | T(#321) | S | Mac |
| 26 | accounts 1 アカウントごと | SettingsScreen.kt:AccountHeader 1766, CalendarRow 744, AddGoogleAccountRow 1832 | SettingsView.swift:600-700 | Header lacks "2つのうち1つで鳴らす" count; icon-only person+/ⓘ/↻ row (:393-425) vs three text rows; account-level "使う" + "このアカウントの予定で鳴らす" both present (design: one switch) | #205 #218 | T(#205,#218) | S (S7, #74) | emu / Mac |
| 27 | accounts 2 アカウントごと止めた時 | AccountHeader | SettingsView.swift:624-634 | Two switches vs one; stopped copy "このアプリでは使っていません…" vs "このアカウントは止めています" + dimmed child rows + footnote | #206 | T(#206) | S | emu / Mac |
| 28 | accounts 3 カレンダーを開いた時 | CalendarRow :744-880 | SettingsView.swift:640-700 | "既定（開始時）/5分/OFF", "開始/通知分", "アラーム/通知/OFF" vs board wording; subtitle "· 終日OFF" vs "終日は 9:00"/"鳴らさない" | #224 | T(#224) | S (#144) | emu / Mac |
| 29 | accounts 4 Google連携 | SettingsScreen.kt:~1010-1050 | n/a (iOS has none, as board says) | Rows "連携中: …/解除", "別のアカウントも連携する/連携", "未連携 (種別はタイトル推測)" OK; board sections "連携している時/していない時" are board-only | - | A | S (§7.1) | - |
| 30 | tabs 1 アラーム | AlarmsScreen.kt:60-261 | AlarmsView.swift:1-165 | And: H1 30 vs 27; repeat label bodySmall 12 (:207) vs 14/500 accent; delete opens AlertDialog (board: icon only); banner/copy/time 44 OK. iOS: "%02d:%02d" (:77) "07:00" vs 7:00; label inline with time, no "毎週" prefix, not accent (:79-81); hero 64pt NextAlarmBanner vs small .nxt pill (:36); edit = Form sheet | shape #317 #340 #341; rest untagged | T(#317) + U | S (F-6, S13) | emu / Mac |
| 31 | tabs 2 アラーム編集 | AlarmEditScreen.kt:81-409 | AlarmsView.swift:AlarmEditView 165-374 | And: M3 TopAppBar (:163) vs .top back + h1 21 + switch; label OutlinedTextField vs .fld 52/r14; weekday FilterChips (:226) vs 36px circles .d; rows ungrouped vs .card.ls; save h56 vs h52/r26. iOS: Form sections, 32px weekday circles, indigo fixed | #317 (card/chip/btn shape); circles/grouping untagged | T(#317) + U | S (F-6/F-7) | emu / Mac |
| 32 | tabs 3 タイマー | TimerScreen.kt:60-330 | TimerView.swift:1-150 | And: clock 260dp (:ClockRing) vs 248; wheel selected 48sp vs 56 and neighbours not shown. iOS: Picker wheel with 9 values (1,3,5…60) (TimerView.swift:60) vs 1-90 | #318 #342 #343 (ring size); wheel untagged | T(#318) + U | S (F-9) | emu / Mac |
| 33 | tabs 4 ストップウォッチ | TimerScreen.kt:StopwatchPane | TimerView.swift:175-221 | Buttons content-width (:Row) vs flex 1.3/1/1 filling; "ラップ 2: 3:10.02" OK; iOS "Lap n" (:216) English vs "ラップ n:", buttons 88x44 fixed, "ラップ/リセット" swap | #318; iOS "Lap" untagged | T(#318) + U | S (F-9) | emu / Mac |
| 34 | d68 tab bar (all boards) | MainActivity.kt:279-345 floating h66 r26 inset 16/bottom 26 | CalendarAlarmApp.swift:225-292 floating h66 r26 | Board: full-width bar h72, hairline top, active = 56x30 accent-soft pill + tx label 600. Also bar present on 権限/トラブルシュート (not in board) | #323 #352 #353 | T(#323) | S (§9.5) | emu / Mac |

### p140-p155 (1st page)
| 35 | p140-1 広告が出た時 | none | none | Not built (no ad code on master although #141 is closed) | #140 (closed) / owner Q72 | P | S F-11 Phase 3, §12 SDK/price undecided | - |
| 36 | p140-2 読み込み中 | none | none | same | Q72 | P | open | - |
| 37 | p140-3 出せない時 | none | none | same (nothing to hide) | Q72 | P | open | - |
| 38 | p142-1 詳細の3択 | AgendaScreen.kt:715 | EventDetailSheet.swift:84 | Android labels OK, Material seg; iOS labels 通知/OFF | #224 #326 | A (Android) / T (iOS) | S | - |
| 39 | p142-2 設定: 既定を2つ | SettingsScreen.kt:189-210 | SettingsView.swift | No headings, 通知/OFF wording | #211 #224 | T(#224) | S | emu/Mac |
| 40 | p142-3 カレンダーごとの上書き | CalendarRow :119-153 | SettingsView.swift:640+ | 開始/通知分, 通知/OFF wording | #224 | T(#224) | S | emu/Mac |
| 41 | p143-1 設定のカード | SettingsScreen.kt:316-360 | SettingsView.swift:345-370 | Copy matches (Android); iOS shorter copy; chips FilterChip+x | #319 | A | S? no (PR #389 specs it) | - |
| 42 | p143-2 追加（Android） | SettingsScreen.kt:ExtraReminderDialog 1861 | - | Copy/errors match; AlertDialog r28 default | - | A | PR #389 | - |
| 43 | p143-3 追加（iPhone） | - | SettingsView.swift:ExtraReminderSheet 925 | Uses Form+Picker menu rows, not wheels; buttons やめる/追加 OK | none (new) | U | PR #389 | Mac |
| 44 | p144-1 カレンダーごとの終日 | CalendarRow :102-115 | SettingsView.swift:659 | "OFF" vs 鳴らさない (#224); live preview card "10月28日 (水) 終日「出張」なら…9:00 に鳴る" missing on both OS | #224; preview none (new) | T(#224) + U | S §7.3 | emu/Mac |
| 45 | p144-2 複数日 案A（初日だけ） | not built (spec §7.3: expands start day only = behaviour already) | same | UI list of days missing; spec §7.3 already picks first-day-only | spec decided | AB (resolved: A) | S §7.3 | - |
| 46 | p144-3 複数日 案B（毎日） | not built | not built | Contradicts spec §7.3 ("毎日鳴らすと…繰り返し扱いにはしない") → drop B | spec | AB (resolved: not B) | S §7.3 says no | - |
| 47 | p145-1 設定: スヌーズ | RingingCard :1296-1330, :1370-1415, Appearance of defaults :152-165 | SettingsView.swift:60-85 | Headings/descriptions missing ("いつものスヌーズ", "鳴動画面で選べるスヌーズ" + sentence); 回数上限 uses chips (:177) vs .seg | #217 | T(#217) | PR #389 | emu/Mac |
| 48 | p145-2 鳴動画面で分数 | RingingActivity.kt:490 | RingingView.swift:141 | Implemented | - | A | PR #389 | - |
| 49 | p145-3 −／＋ 1分ずつ | none | none | Board: unbuilt proposal | owner Q | P | no | - |
| 50 | p146-1 標準の音 | SettingsScreen.kt:1265-1290 | n/a | "アラーム: 標準/選ぶ" OK; buttons FilterChip vs .ghost 40/r20 | - | A | S F-4 | - |
| 51 | p146-2 ファイルを選んだ後 | same :1288 | n/a | OK | - | A | S | - |
| 52 | p146-3 続けて読めないファイル | same :1293 | n/a | Error is plain text (:1293) vs .banner.ng; copy OK | none (new) | U | S | emu |
| 53 | p147-1 今の形（iPhone） | n/a | SettingsView.swift:100-120 | "アラーム音: iPhone の標準/選ぶ/標準に戻す" + footnote OK | - | A | S | - |
| 54 | p147-2 同梱の音 | none | none | Board: unbuilt (no .caf list; PBI #147 open) | owner Q | P | no | Mac |
| 55 | p148-1 最初の状態 | SettingsScreen.kt:1250-1420 | n/a | Copy/options match; M3 Slider vs 6px .slider; vol "端末設定" OK | #319 (shape) | A | PR #389 | - |
| 56 | p148-2 アプリで決めた時 | same | n/a | "音量: 70%" OK | - | A | PR #389 | - |
| 57 | p149-1 DND中も鳴らす（許可あり） | none (diagnostic row only) | n/a | Board: unbuilt toggle; spec §11 row 6 says DND always rings, so "off → don't ring" is not in spec | owner Q (#149 open) | P | no (conflicts §11) | - |
| 58 | p149-2 許可がまだない | none | n/a | same | owner Q | P | no | - |
| 59 | p150-1 設定のカード | NotificationRulesCard.kt:78-145 | n/a (iOS excluded #103) | Rows not tappable, only trash (:115); 12 template chips present | #226 #329 | T(#226) | S §6 (iOS out) | emu |
| 60 | p150-2 ルールを直す | NotificationRulesCard.kt:242-320 (add dialog only) | n/a | edit dialog w/ 保存/削除 missing | #226 #329 | T(#226) | no | emu |
| 61 | p150-3 許可がまだ無い | NotificationRulesCard.kt (title "(要権限)", rules_grant_access) | n/a | Implemented | - | A | S | - |
| 62 | p151-1 設定: 見た目 | SettingsScreen.kt:WeekDisplaySettings 1540 | SettingsView.swift:196-215 | Present; day buttons FilterChip vs 36px circles .d | #319 | A | S (#106) | - |
| 63 | p151-2 両方オン | AgendaScreen.kt:498-505, hero | AgendaView.swift DayCard | 第N週 OK; right-aligned small OK | - | A | S | - |
| 64 | p151-3 両方オフ | AgendaScreen.kt:365 | AgendaView.swift | status line stays: OK (card still tinted) | - | A | S | - |
| 65 | p152-1 鳴動画面の天気 | RingingActivity.kt:441-458, WeatherBackdrop.kt:86 | RingingView.swift:90-99 | Pill OK; clear-day gradient 2-stop #2B4A8C→#0E1633 vs #1B2C47→#101826(38%)→#0A0C11(70%) | none (new) | U | S F-8 | emu |
| 66 | p152-2 設定: 天気 | SettingsScreen.kt:146-196 | SettingsView.swift:86 | Row 1 has icon only (no heading "天気"); heading owner undecided (#211 comment) | undecided | T(#211, pending) | S | emu/Mac |
| 67 | p153-1 上の方 | TroubleshootingScreen.kt:TroubleshootRow | DiagnosticsView.swift | No state chip (OK/要対応/参考/対象外) or "設定を開く" text; OK icon uses accent not --ok; flat rows vs card.ls | #228 #335 (PR #390 tests) | T(#228) | S §6 | emu/Mac |
| 68 | p153-2 下の方 | same + ForceFsiRow | same | same; force-FSI row not in a card | #228 #335 | T(#228) | S | emu |
| 69 | p153-3 入口 | AgendaScreen.kt:177 | AgendaView.swift:42 | Android OK; iOS "見る"→missed list | iOS untagged (row 2) | U | S | Mac |
| 70 | p154-1 今の鳴動画面 | RingingScreen | RingingView | same gaps as row 8 | #320 | T(#320) | S | emu/Mac |
| 71 | p154-2 大きいボタン | none | none | Board: unbuilt proposal | owner Q (#154) | P | no | - |
| 72 | p154-3 今の見た目の設定 | AppearanceCard | SettingsView | as row 15 | #225 | T(#225) | S | emu/Mac |
| 73 | p154-4 足す行 案A／B (Pro) | none | none | Board: unbuilt; A = all free, B = Pro locks (price undecided) | owner Q | AB | open (§12 price/SDK) | - |
| 74 | p155-1 招待の返事と種類 | SettingsScreen.kt:229-248 | SettingsView.swift:300-321 | heading missing; chips clipped before PR #363 | #204 #211 #363 | T(#204) | S §7.1 | emu/Mac |
| 75 | p155-2 予定画面での見え方 | AgendaScreen.kt:560-568 | AgendaView.swift:EventRow | No "招待の返事で対象外" reason line | #227 #315 #333 #334 | T(#315) | S | emu/Mac |

(Rows 16-21 = six ask states, 22-24 = three perm states; row 34 is the shared tab bar, so 75 rows = 74 states + 1.)

### Not in design at all (design gaps)
- Home-screen widget: Android NextAlarmWidget.kt (bg #1B1B1D, accent #9EC6F5, off-token), iOS Widget/CalendarAlarmWidget.swift. Spec F-10 has it. No board.
- Settings > 鳴動の状態 inner screen (permissions, 10s test, recent log): code is an expandable card + RingLogDialog; board only notes it.
- Alarm delete confirm, preset add/delete dialogs, search field, MissedAlarmsView/iOS diagnostics, MailImport/ExtractedEvent/SharedText dialogs (S14): no boards.

## Ranked untagged differences (no owner), by screen, biggest user impact first
1. Cross-screen: Android off-state switch/outline token. Theme.kt:81 outline=#262C38 vs --out #7B8494, so every M3 Switch off-state border/thumb is near-invisible (agenda row switches, perm, troubleshooting, settings). Highest because ON/OFF is the main interaction.
2. Status colours: banner uses errorContainer #93000A (AgendaScreen.kt:413); Permissions.kt:263 uses colorScheme.error #FFB4AB (not #FF8A80); troubleshoot OK icon uses accent not --ok; hero status line never shows green/red dot (AgendaScreen.kt:398). iOS banner is orange.
3. Agenda (iOS): day header loses the date for 今日/明日 and is fixed indigo (AgendaView.swift:153-158, DayCard); banner says "見る" and opens the missed list instead of troubleshooting (:42).
4. Agenda (both): 24h/zero-padded times and missing end time on iOS (AgendaView.swift:512-519, AlarmsView.swift:77); alarm repeat label lacks "毎週" and accent on iOS (AlarmsView.swift:79-81).
5. Type scale (both OS): H1 30 vs 27/600; hero time 36 vs 44; eye 12/1sp vs 11/.14em; detail title 30 Bold vs 25/600; ring time 112 vs 92/200; ring title 32 vs 25/600; row title 16 regular vs 15/500; snooze 22 vs 19; alarm repeat 12 vs 14/500. (#316 says type values unchanged; the board says otherwise.)
6. Agenda (Android): month cell height ~70dp vs 40; Sun/Sat colours vs --now/--gg; timeline block r6/pad vs r10/pad 10x5, hour label alignment; 3-day text 12 vs 13/500; weather card colour/radius in detail sheet.
7. Agenda (Android): 今日/明日 chip vs plain text; empty state icon without ring (.ico.muc) and 14sp title.
8. Navigation: floating tab bar also shown on 権限 and トラブルシューティング (MainActivity.kt:297); board shows none.
9. p144: live preview card ("10月28日 (水) 終日「出張」なら 10月28日 9:00 に鳴る") missing on both OS.
10. Alarm edit (both): weekday FilterChips vs 36px circles; ungrouped rows vs card.ls; iOS 32px circles + fixed indigo; Timer wheel selected 48 vs 56 (Android), iOS wheel only 9 values and "Lap n" in English (TimerView.swift:216).
11. p146-3 error shown as plain text, not banner; p152-1 weather gradient stops differ (WeatherBackdrop.kt:86); iOS extra-reminder sheet is Form+Picker menus, board is wheels.

## Owner questions the spec cannot settle (only P / AB rows)
- p145-3 −／＋ snooze: not in spec; PBI #145 open (PR #389 specs the chip method only).
- p147-2 bundled alarm sounds: not in spec.
- p149 DND toggle: conflicts with spec §11 row 6 (DND always rings).
- p154-2 big buttons, p154-4 Pro (案A／B): not in spec; price/SDK open (§12). p140 ads: F-11 Phase 3, §12 undecided; Q72.
- p144 案A／B: closed by spec §7.3 (first day only = A). Board B should be marked dropped.

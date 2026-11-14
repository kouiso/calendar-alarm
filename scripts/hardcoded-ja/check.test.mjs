// check.mjs の単体テスト。Gradle なしで動く: node --test scripts/hardcoded-ja/
import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { countJapaneseLiterals, countAll, compare, formatError } from "./check.mjs";

const UI = "androidApp/src/main/java/com/calendaralarm/ui/Foo.kt";

// 一時ディレクトリに Kotlin ファイルを置き、countAll で数える
function fixture(files) {
  const root = mkdtempSync(join(tmpdir(), "ja-check-"));
  for (const [rel, body] of Object.entries(files)) {
    const p = join(root, rel);
    mkdirSync(join(p, ".."), { recursive: true });
    writeFileSync(p, body);
  }
  return root;
}

test("日本語の文字列リテラルを足すと baseline 超過で失敗する", () => {
  const baseline = { [UI]: 1 };
  const root = fixture({ [UI]: 'val a = "保存"\nval b = "削除"\n' });
  try {
    const errors = compare(countAll(root), baseline);
    assert.deepEqual(errors, [{ file: UI, was: 1, now: 2 }]);
    const msg = formatError(errors[0]);
    assert.match(msg, /Foo\.kt/);
    assert.match(msg, /1 個から 2 個/);
    assert.match(msg, /strings\.xml/);
  } finally { rmSync(root, { recursive: true }); }
});

test("baseline に無い新規ファイルは 1 個でも失敗する", () => {
  const root = fixture({ [UI]: 'val a = "保存"\n' });
  try {
    assert.deepEqual(compare(countAll(root), {}), [{ file: UI, was: 0, now: 1 }]);
  } finally { rmSync(root, { recursive: true }); }
});

test("日本語を足さなければ通る", () => {
  const root = fixture({ [UI]: 'val a = "save"\nval b = "保存"\n' });
  try {
    assert.deepEqual(compare(countAll(root), { [UI]: 1 }), []);
  } finally { rmSync(root, { recursive: true }); }
});

test("日本語のコメントと KDoc だけを足しても通る", () => {
  const src = [
    "/** 保存ボタン。KDoc の日本語は数えない。 */",
    "// 行コメントの日本語",
    "/* ブロック",
    "   コメント /* 入れ子 */ も日本語 */",
    'val a = "save" // 行末コメント「保存」 "引用"',
    "",
  ].join("\n");
  assert.equal(countJapaneseLiterals(src), 0);
});

test("audit / markMissed / Log の直接引数は数えない", () => {
  const src = [
    'repo.audit("ERROR", "音源 prepare 失敗: $uri")',
    'repo.markMissed(id = x, detail = "鳴動時刻から${n}分経過")',
    'repo.markSkipped(id, "スキップ")',
    'Log.w(TAG, "再生に失敗")',
    'repo.audit("E", "${if (ok) "可" else "拒否"}")',
    "",
  ].join("\n");
  assert.equal(countJapaneseLiterals(src), 0);
});

test("audit 以外の呼び出しの日本語は数える", () => {
  const src = 'repo.audit("ERROR", "失敗")\nText("保存")\nval t = label.ifBlank { "無題" }\n';
  assert.equal(countJapaneseLiterals(src), 2);
});

test("raw 文字列・エスケープ済み引用符・文字リテラル・テンプレートを扱う", () => {
  const src = [
    'val q = "say \\"hi\\" ok"',            // 日本語なし
    "val c = '\"'",                        // 文字 " で文字列が始まったと誤認しない
    'val r = """',
    "  複数行の日本語",
    '"""',
    'val e = "\\u3042"',                   // \\u3042 = あ
    'val t = "${name}さん"',               // テンプレート外側の日本語
    'val n = "${f("日本語")}"',            // テンプレート内の文字列は別に 1 個
    "",
  ].join("\n");
  assert.equal(countJapaneseLiterals(src), 4);
});

test("全角記号・半角カナ・漢字・カタカナ・ひらがなを日本語として数える", () => {
  for (const s of ["「", "、", "！", "ｱ", "ア", "あ", "漢"]) {
    assert.equal(countJapaneseLiterals(`val a = "x${s}y"`), 1, s);
  }
  assert.equal(countJapaneseLiterals('val a = "abc 123 -+"'), 0);
});

test("baseline より減るのは通る", () => {
  const root = fixture({ [UI]: 'val a = "保存"\n' });
  try {
    assert.deepEqual(compare(countAll(root), { [UI]: 5 }), []);
  } finally { rmSync(root, { recursive: true }); }
});

test("許可リストのファイルは数えない (照合キーワード)", () => {
  const tpl = "androidApp/src/main/java/com/calendaralarm/engine/NotificationRuleTemplates.kt";
  const models = "shared/src/commonMain/kotlin/com/calendaralarm/shared/model/Models.kt";
  const root = fixture({
    [tpl]: 'val k = listOf("重要", "緊急")\n',
    [models]: 'hit("誕生日", "birthday")\nval label = "表示"\n',
  });
  try {
    // Models.kt は hit( の引数だけ免除。表示文言らしい 1 個は数える
    assert.deepEqual(countAll(root), { [models]: 1 });
  } finally { rmSync(root, { recursive: true }); }
});

test("androidApp 全体と shared/commonMain を見る (ui 以外も対象)", () => {
  const eng = "androidApp/src/main/java/com/calendaralarm/engine/Bar.kt";
  const sh = "shared/src/commonMain/kotlin/com/calendaralarm/shared/logic/Baz.kt";
  const other = "shared/src/jvmTest/kotlin/X.kt";
  const root = fixture({ [eng]: 'val a = "あ"\n', [sh]: 'val a = "い"\n', [other]: 'val a = "う"\n' });
  try {
    assert.deepEqual(countAll(root), { [eng]: 1, [sh]: 1 });
  } finally { rmSync(root, { recursive: true }); }
});

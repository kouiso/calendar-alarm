#!/usr/bin/env node
// 画面に出す日本語を Kotlin の文字列リテラルへ直接書く増加を止める検査 (#289)。
// 意味は判定しない。構文だけ見て、日本語を含む文字列リテラルをファイルごとに数える。
// コメントと KDoc は数えない (AGENTS.md が日本語コメントを求めるため)。
// 使い方:
//   node scripts/hardcoded-ja/check.mjs            baseline と比べ、増えたファイルがあれば失敗
//   node scripts/hardcoded-ja/check.mjs --update   baseline を今の数で作り直す
// 依存なし (Node 標準のみ)。
import { readFileSync, writeFileSync, readdirSync, statSync } from "node:fs";
import { join, relative, dirname, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

// ひらがな・カタカナ・CJK 統合漢字(+拡張A)・CJK 記号句読点・全角半角形
const JA = /[぀-ヿ㐀-䶿一-鿿　-〿＀-￯]/;

export const SCAN_DIRS = [
  { dir: "androidApp/src/main/java", ext: ".kt" },
  { dir: "shared/src/commonMain", ext: ".kt" },
];

// 常設の許可リスト。数えない。理由は必ず書く。
export const ALLOWLIST = [
  {
    // 通知ルールのクイックテンプレート。anyKeywords などは通知本文との照合キーワードで、
    // 画面に出す文言ではない (表示名は strings.xml の getString で出している)。
    pattern: /^androidApp\/src\/main\/java\/com\/calendaralarm\/.*\/NotificationRuleTemplates\.kt$/,
  },
  {
    // 停止手段の警告文 (StopUiPlan の診断メッセージ)。設定の診断欄に出す開発者向けの文で、
    // 製品の表示文言とは別扱いにする (プロダクト判定 #289)。
    pattern: /^androidApp\/src\/main\/java\/com\/calendaralarm\/engine\/StopUiPlan\.kt$/,
  },
  {
    // 予定タイプの判定 (EventType 推定)。hit("誕生日", ...) の引数は予定タイトルとの照合キーワードで、
    // 画面に出す文言ではない。このファイルでは hit( の直接引数だけを数えない。
    pattern: /^shared\/src\/commonMain\/kotlin\/com\/calendaralarm\/shared\/model\/Models\.kt$/,
    callees: ["hit"],
  },
];

// 診断ログ用の呼び出しの直接引数は数えない (画面に出ない)。
// audit( markMissed( markSkipped( と Log.v/d/i/w/e( 。
// repo.audit( のように前に修飾が付いても、最後の名前で見る。markMissedIfFired も同じ detail 引数の診断文。
const LOG_CALLEE = /(^|\.)(audit|markMissed|markMissedIfFired|markSkipped)$|(^|\.)Log\.[vdiwe]$/;

const isIdent = (c) => c !== undefined && /[A-Za-z0-9_.]/.test(c);

// 直前の識別子 (Log.w のような a.b も含む) を取る。
function calleeBefore(src, i) {
  let j = i - 1;
  while (j >= 0 && /\s/.test(src[j])) j--;
  let k = j;
  while (k >= 0 && isIdent(src[k])) k--;
  return src.slice(k + 1, j + 1);
}

/**
 * Kotlin ソースから、日本語を含む文字列リテラルを集める。
 * 返す値: [{ line, direct: 診断ログ呼び出しの直接引数か }]
 */
export function scanKotlin(src, extraCallees = []) {
  const isDiag = (callee) => LOG_CALLEE.test(callee) || extraCallees.includes(callee.split(".").pop());
  const found = [];
  let pos = 0;
  const lineAt = (p) => src.slice(0, p).split("\n").length;

  // コード部分を読む。stopAtBrace なら対応する } で戻る (文字列テンプレート内)。
  function code(stopAtBrace, inDiag = false) {
    const parens = []; // 開き括弧ごとの呼び出し先 ("" なら普通の括弧)
    let depth = 0;
    while (pos < src.length) {
      const c = src[pos];
      const n = src[pos + 1];
      if (c === "/" && n === "/") {
        while (pos < src.length && src[pos] !== "\n") pos++;
      } else if (c === "/" && n === "*") {
        let nest = 1;
        pos += 2;
        while (pos < src.length && nest > 0) {
          if (src[pos] === "/" && src[pos + 1] === "*") { nest++; pos += 2; }
          else if (src[pos] === "*" && src[pos + 1] === "/") { nest--; pos += 2; }
          else pos++;
        }
      } else if (c === '"') {
        const direct = inDiag || (parens.length > 0 && isDiag(parens[parens.length - 1]));
        str(direct);
      } else if (c === "'") {
        // 文字リテラル。'"' や '\'' を文字列の開始と誤認しない。
        pos++;
        while (pos < src.length && src[pos] !== "'") { if (src[pos] === "\\") pos++; pos++; }
        pos++;
      } else if (c === "(") {
        parens.push(calleeBefore(src, pos));
        pos++;
      } else if (c === ")") {
        parens.pop();
        pos++;
      } else if (c === "{") {
        depth++;
        pos++;
      } else if (c === "}") {
        if (stopAtBrace && depth === 0) { pos++; return; }
        depth--;
        pos++;
      } else pos++;
    }
  }

  // " から始まる文字列 (通常 / raw) を読む。${...} の中は code() で再帰する。
  function str(direct) {
    const start = pos;
    const raw = src.startsWith('"""', pos);
    pos += raw ? 3 : 1;
    let hasJa = false;
    while (pos < src.length) {
      const c = src[pos];
      if (raw) {
        if (src.startsWith('"""', pos)) {
          pos += 3;
          while (src[pos] === '"') pos++; // """" のように末尾に " が続く場合
          break;
        }
      } else {
        if (c === "\\") {
          // \uXXXX で書かれた日本語も拾う
          if (src[pos + 1] === "u") {
            const cp = parseInt(src.slice(pos + 2, pos + 6), 16);
            if (!Number.isNaN(cp) && JA.test(String.fromCharCode(cp))) hasJa = true;
          }
          pos += 2;
          continue;
        }
        if (c === '"') { pos++; break; }
        if (c === "\n") break; // 閉じ忘れで以降を飲み込まない
      }
      if (c === "$" && src[pos + 1] === "{") {
        pos += 2;
        code(true, direct);
        continue;
      }
      if (JA.test(c)) hasJa = true;
      pos++;
    }
    if (hasJa) found.push({ line: lineAt(start), direct });
  }

  code(false);
  return found;
}

export function countJapaneseLiterals(src, extraCallees = []) {
  return scanKotlin(src, extraCallees).filter((l) => !l.direct).length;
}

function walk(dir, ext, out) {
  for (const name of readdirSync(dir).sort()) {
    const p = join(dir, name);
    const st = statSync(p);
    if (st.isDirectory()) walk(p, ext, out);
    else if (name.endsWith(ext)) out.push(p);
  }
}

export function countAll(root) {
  const counts = {};
  for (const { dir, ext } of SCAN_DIRS) {
    const base = join(root, dir);
    let files = [];
    try { walk(base, ext, files); } catch { continue; }
    for (const f of files) {
      const rel = relative(root, f).split(sep).join("/");
      const entries = ALLOWLIST.filter((a) => a.pattern.test(rel));
      if (entries.some((a) => !a.callees)) continue; // callees の無い entry はファイル全体を許可
      const n = countJapaneseLiterals(readFileSync(f, "utf8"), entries.flatMap((a) => a.callees));
      if (n > 0) counts[rel] = n;
    }
  }
  return counts;
}

// baseline より増えたファイルを返す。減るのは許す。
export function compare(current, baseline) {
  const errors = [];
  for (const [file, n] of Object.entries(current)) {
    const was = baseline[file] ?? 0;
    if (n > was) errors.push({ file, was, now: n });
  }
  return errors;
}

export function formatError(e) {
  return `${e.file}: 日本語の文字列リテラルが ${e.was} 個から ${e.now} 個に増えました (+${e.now - e.was})。` +
    "画面に出す文言は strings.xml へ移し、stringResource / getString で参照してください。";
}

function main() {
  const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
  const baselinePath = join(root, "scripts/hardcoded-ja-baseline.json");
  const current = countAll(root);
  if (process.argv.includes("--update")) {
    const sorted = Object.fromEntries(Object.entries(current).sort(([a], [b]) => (a < b ? -1 : 1)));
    writeFileSync(baselinePath, JSON.stringify(sorted, null, 2) + "\n");
    const total = Object.values(sorted).reduce((a, b) => a + b, 0);
    console.log(`baseline を更新しました: ${Object.keys(sorted).length} ファイル / 合計 ${total} 個`);
    return;
  }
  const baseline = JSON.parse(readFileSync(baselinePath, "utf8"));
  const errors = compare(current, baseline);
  if (errors.length > 0) {
    for (const e of errors) console.error(`::error file=${e.file}::${formatError(e)}`);
    console.error("意図して増やすのではなく、strings.xml へ移すのが正しい直し方です。" +
      "既存の文言を減らした場合は node scripts/hardcoded-ja/check.mjs --update で baseline を下げてください。");
    process.exit(1);
  }
  const total = Object.values(current).reduce((a, b) => a + b, 0);
  console.log(`OK: 日本語の文字列リテラルは増えていません (合計 ${total} 個)`);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();

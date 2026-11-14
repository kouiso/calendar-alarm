#!/usr/bin/env node
// 画面に出す日本語を Kotlin の文字列リテラルへ直接書く増加を止める検査 (#289)。
// 意味は判定しない。構文だけ見て、日本語を含む文字列リテラルをファイルごとに数える。
// コメントと KDoc は数えない (AGENTS.md が日本語コメントを求めるため)。
// 使い方:
//   node scripts/hardcoded-ja/check.mjs            baseline と比べ、増えたファイルがあれば失敗
//   node scripts/hardcoded-ja/check.mjs --update   baseline を今の数で作り直す
// PR (GITHUB_BASE_REF あり) では、比べる相手は PR 自身の baseline ではなく base ブランチの baseline。
// PR の中で --update して baseline を上げても通らない。減らす方向の更新だけ通る。
// 依存なし (Node 標準のみ)。
import { readFileSync, writeFileSync, readdirSync, statSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { join, relative, dirname, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

// ひらがな・カタカナ・CJK 統合漢字(+拡張A, +拡張B 以降のサロゲートペア)・CJK 記号句読点・全角半角形
const JA = /[぀-ヿ㐀-䶿一-鿿　-〿＀-￯]|[\u{20000}-\u{323AF}]/u;

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
    // 停止手段の警告文 (StopUiPlan の診断メッセージ)。設定の「鳴動ログ」にそのまま出るが、
    // 診断ログの文面であり、このフェーズの対象外とする (プロダクト判定 #289)。
    pattern: /^androidApp\/src\/main\/java\/com\/calendaralarm\/engine\/StopUiPlan\.kt$/,
  },
  {
    // 予定タイプの判定 (EventType 推定)。hit("誕生日", ...) の引数は予定タイトルとの照合キーワードで、
    // 画面に出す文言ではない。このファイルでは hit( の直接引数だけを数えない。
    pattern: /^shared\/src\/commonMain\/kotlin\/com\/calendaralarm\/shared\/model\/Models\.kt$/,
    callees: ["hit"],
  },
];

// 診断ログ用の呼び出しの直接引数は数えない。設定の「鳴動ログ」には detail がそのまま出るが、
// 診断ログの文面であり、このフェーズの対象外とする (プロダクト判定 #289)。
// audit( markMissed( markSkipped( と Log.v/d/i/w/e( 。
// repo.audit( のように前に修飾が付いても、最後の名前で見る。
// markMissedIfFired / markDndSuppressed も markMissed と同じ detail 引数の診断文。
const LOG_CALLEE = /(^|\.)(audit|markMissed|markMissedIfFired|markSkipped|markDndSuppressed)$|(^|\.)Log\.[vdiwe]$/;

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
      else if (c >= "\ud840" && c <= "\ud88c" && JA.test(src.slice(pos, pos + 2))) hasJa = true; // 拡張B 以降 (サロゲートペア)
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

// 失敗を返す。
//   current   : 今の数
//   prBaseline: この PR の作業ツリーにある baseline
//   base      : 比べる相手。PR では base ブランチの baseline (省略時は prBaseline と同じ)
// 増加 (今の数 > base) と baseline の引き上げ (prBaseline > base) は kind なしの {file, was, now}。
// 減ったのに baseline を下げていない (今の数 < prBaseline) は kind: "stale"。
export function compare(current, prBaseline, base = prBaseline) {
  const errors = [];
  for (const [file, n] of Object.entries(current)) {
    const was = base[file] ?? 0;
    if (n > was) errors.push({ file, was, now: n });
  }
  for (const [file, n] of Object.entries(prBaseline)) {
    const was = base[file] ?? 0;
    if (n > was && !errors.some((e) => e.file === file)) errors.push({ file, was, now: n, kind: "raised" });
  }
  for (const [file, n] of Object.entries(prBaseline)) {
    const now = current[file] ?? 0;
    if (now < n) errors.push({ file, was: n, now, kind: "stale" });
  }
  return errors;
}

function adviceFor(file) {
  if (file.startsWith("shared/")) {
    return "shared には resources が無いので、shared からは種類 (kind / enum) か引数だけを返し、" +
      "文言への変換は各プラットフォーム側 (Android は strings.xml の values と values-en + stringResource / getString) で行ってください。";
  }
  return "画面に出す文言は strings.xml (values と values-en の両方) へ移し、stringResource / getString で参照してください。";
}

export function formatError(e) {
  if (e.kind === "stale") {
    return `${e.file}: 日本語の文字列リテラルが減って ${e.was} 個から ${e.now} 個になりましたが、baseline が下がっていません。` +
      "node scripts/hardcoded-ja/check.mjs --update を実行し、下がった数字をコミットしてください (余白を後で再利用されないため)。";
  }
  if (e.kind === "raised") {
    return `${e.file}: baseline が base ブランチの ${e.was} 個から ${e.now} 個に引き上げられています。baseline は下げる方向にしか変えられません。` +
      adviceFor(e.file);
  }
  return `${e.file}: 日本語の文字列リテラルが ${e.was} 個から ${e.now} 個に増えました (+${e.now - e.was})。` + adviceFor(e.file);
}

// PR のときだけ、base ブランチの baseline を git から読む。PR 以外 (ローカル・push) は null。
// base に baseline ファイルがまだ無いとき (この仕組みを入れる最初の PR) は null で作業ツリーのものを使う。
// base ref が取れないのは失敗 (黙って PR 自身の baseline に落とすと素通りになる)。
function loadBaseBaseline(root, baselineRel) {
  const ref = process.env.GITHUB_BASE_REF;
  if (!ref) return null;
  const git = (...args) => execFileSync("git", args, { cwd: root, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
  try { git("rev-parse", "--verify", "--quiet", `origin/${ref}^{commit}`); }
  catch { console.error(`base ブランチ origin/${ref} が取れません。workflow の checkout に fetch-depth: 0 (または base の fetch) が必要です。`); process.exit(1); }
  try { git("cat-file", "-e", `origin/${ref}:${baselineRel}`); }
  catch { console.log(`base (origin/${ref}) に ${baselineRel} がまだ無いので、この PR の baseline を基準にします。`); return null; }
  return JSON.parse(git("show", `origin/${ref}:${baselineRel}`));
}

function main() {
  const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
  const baselineRel = "scripts/hardcoded-ja-baseline.json";
  const baselinePath = join(root, baselineRel);
  const current = countAll(root);
  if (process.argv.includes("--update")) {
    const sorted = Object.fromEntries(Object.entries(current).sort(([a], [b]) => (a < b ? -1 : 1)));
    writeFileSync(baselinePath, JSON.stringify(sorted, null, 2) + "\n");
    const total = Object.values(sorted).reduce((a, b) => a + b, 0);
    console.log(`baseline を更新しました: ${Object.keys(sorted).length} ファイル / 合計 ${total} 個`);
    return;
  }
  const baseline = JSON.parse(readFileSync(baselinePath, "utf8"));
  const base = loadBaseBaseline(root, baselineRel) ?? baseline;
  const errors = compare(current, baseline, base);
  if (errors.length > 0) {
    for (const e of errors) console.error(`::error file=${e.file}::${formatError(e)}`);
    console.error("増やすのではなく、文言を移すのが正しい直し方です。" +
      "既存の文言を減らした場合は node scripts/hardcoded-ja/check.mjs --update を実行し、下がった baseline をコミットしてください。");
    process.exit(1);
  }
  const total = Object.values(current).reduce((a, b) => a + b, 0);
  console.log(`OK: 日本語の文字列リテラルは増えていません (合計 ${total} 個)`);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();

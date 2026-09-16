import { spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";

const validator = resolve("scripts/validate-docs.mjs");
let mutationCount = 0;

function fixture() {
  const root = mkdtempSync(join(tmpdir(), "openscholar-doc-links-"));
  spawnSync("git", ["init", "-q"], { cwd: root });
  mkdirSync(join(root, "docs"));
  writeFileSync(join(root, "README.md"), [
    "# Introduction",
    "[Same file](#introduction)",
    "[Duplicate heading](#introduction-1)",
    "[Formatted heading](docs/guide.md#read-a-pdf)",
    "[Unicode heading](docs/guide.md#m%C3%BCller--cancer)",
    "[Setext heading](docs/guide.md#setup)",
    "[Explicit anchor](docs/guide.md#custom-anchor)",
    "[Space in filename](<docs/guide with spaces.md#overview>)",
    "[External](https://example.invalid/#not-local)",
    "## Introduction",
    "```md",
    "[Ignored code example](#not-a-heading)",
    "```",
    "<!-- [Ignored comment](missing.md) -->",
    "",
  ].join("\n"));
  writeFileSync(join(root, "docs", "guide.md"), [
    "# Read a `PDF`",
    "## Müller & Cancer",
    "Setup",
    "-----",
    '<a id="custom-anchor"></a>',
    "```md",
    "## Fenced heading",
    "```",
    "",
  ].join("\n"));
  writeFileSync(join(root, "docs", "guide with spaces.md"), "# Overview\n");
  spawnSync("git", ["add", "."], { cwd: root });
  return root;
}

function run(root) {
  return spawnSync(process.execPath, [validator], { cwd: root, encoding: "utf8" });
}

function replace(root, relativeFile, before, after) {
  const target = join(root, relativeFile);
  const content = readFileSync(target, "utf8");
  if (!content.includes(before)) throw new Error(`mutation source missing: ${before}`);
  writeFileSync(target, content.replace(before, after));
}

function expectFailure(name, mutate, expectedDiagnostic) {
  mutationCount += 1;
  const root = fixture();
  try {
    mutate(root);
    const result = run(root);
    if (result.status === 0 || !result.stderr.includes(expectedDiagnostic)) {
      throw new Error(`${name}: expected ${JSON.stringify(expectedDiagnostic)}; `
        + `status=${result.status} stdout=${result.stdout} stderr=${result.stderr}`);
    }
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}

const baseline = fixture();
try {
  const result = run(baseline);
  if (result.status !== 0) throw new Error(`baseline failed: ${result.stdout} ${result.stderr}`);
} finally {
  rmSync(baseline, { recursive: true, force: true });
}

expectFailure("missing same-file anchor", (root) => replace(root, "README.md", "(#introduction)", "(#missing)"), "missing heading anchor #missing");
expectFailure("renamed target heading", (root) => replace(root, "docs/guide.md", "# Read a `PDF`", "# Reading papers"), "missing heading anchor docs/guide.md#read-a-pdf");
expectFailure("fenced heading is not an anchor", (root) => replace(root, "README.md", "(#introduction)", "(docs/guide.md#fenced-heading)"), "missing heading anchor docs/guide.md#fenced-heading");
expectFailure("missing target file", (root) => replace(root, "README.md", "docs/guide.md#setup", "docs/missing.md#setup"), "missing local target docs/missing.md#setup");
expectFailure("malformed fragment encoding", (root) => replace(root, "README.md", "(#introduction)", "(#%ZZ)"), "invalid anchor encoding in #%ZZ");
expectFailure("unterminated HTML comment", (root) => replace(root, "README.md", "<!-- [Ignored comment](missing.md) -->", "<!-- [Ignored comment](missing.md)"), "malformed HTML comment is not closed");
expectFailure("nested comment smuggling", (root) => replace(root, "README.md", "<!-- [Ignored comment](missing.md) -->", "<!<!-- [Ignored comment](missing.md) -->-->"), "unexpected HTML comment marker outside a complete comment");

console.log(`Documentation link mutation suite passed (${mutationCount} mutations).`);

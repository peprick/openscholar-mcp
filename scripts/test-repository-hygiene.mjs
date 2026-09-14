import { spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";

const validator = resolve("scripts/validate-repository-hygiene.mjs");
let mutationCount = 0;

function fixture() {
  const root = mkdtempSync(join(tmpdir(), "openscholar-repository-hygiene-"));
  spawnSync("git", ["init", "-q"], { cwd: root });
  writeFileSync(join(root, "README.md"), "# Fixture\n");
  spawnSync("git", ["add", "README.md"], { cwd: root });
  return root;
}

function run(root) {
  return spawnSync(process.execPath, [validator], { cwd: root, encoding: "utf8" });
}

function expectFailure(name, mutate, expectedDiagnostic) {
  mutationCount += 1;
  const root = fixture();
  try {
    mutate(root);
    const result = run(root);
    if (result.status === 0 || !result.stderr.includes(expectedDiagnostic)) {
      throw new Error(
        `${name}: expected failure containing ${JSON.stringify(expectedDiagnostic)}; `
          + `status=${result.status} stdout=${result.stdout} stderr=${result.stderr}`,
      );
    }
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}

const baseline = fixture();
try {
  const result = run(baseline);
  if (result.status !== 0) {
    throw new Error(`baseline failed: stdout=${result.stdout} stderr=${result.stderr}`);
  }
} finally {
  rmSync(baseline, { recursive: true, force: true });
}

expectFailure(
  "generated output",
  (root) => {
    mkdirSync(join(root, "frontend", ".next"), { recursive: true });
    writeFileSync(join(root, "frontend", ".next", "build.json"), "{}");
    spawnSync("git", ["add", "-f", "frontend/.next/build.json"], { cwd: root });
  },
  "generated or local-only directory is tracked",
);

expectFailure(
  "secret environment file",
  (root) => {
    writeFileSync(join(root, ".env.production"), "SECRET=fixture\n");
    spawnSync("git", ["add", "-f", ".env.production"], { cwd: root });
  },
  "environment file may contain local credentials",
);

expectFailure(
  "private key",
  (root) => {
    writeFileSync(join(root, "test.pem"), "fixture\n");
    spawnSync("git", ["add", "test.pem"], { cwd: root });
  },
  "private-key or keystore file is tracked",
);

expectFailure(
  "symbolic link",
  (root) => {
    symlinkSync("README.md", join(root, "README-link.md"));
    spawnSync("git", ["add", "README-link.md"], { cwd: root });
  },
  "symbolic links are not allowed",
);

console.log(`Repository hygiene mutation suite passed (${mutationCount} mutations).`);

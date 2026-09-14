import { execFileSync } from "node:child_process";
import { lstatSync, statSync } from "node:fs";
import { resolve } from "node:path";

const repositoryRoot = execFileSync("git", ["rev-parse", "--show-toplevel"], {
  encoding: "utf8",
}).trim();

const trackedFiles = execFileSync("git", ["ls-files", "-z"], {
  cwd: repositoryRoot,
  encoding: "utf8",
})
  .split("\0")
  .filter(Boolean);

const forbiddenDirectories = [
  /(^|\/)node_modules\//,
  /(^|\/)target\//,
  /(^|\/)\.next\//,
  /(^|\/)coverage\//,
  /(^|\/)playwright-report\//,
  /(^|\/)test-results\//,
  /(^|\/)graphify-out\//,
  /(^|\/)security-reports\//,
  /(^|\/)public\/pdfjs\//,
];
const secretExtensions = /\.(?:jks|key|keystore|p12|pem|pfx)$/i;
const environmentFile = /(^|\/)\.env(?:\..+)?$/;
const operatingSystemFile = /(^|\/)(?:\.DS_Store|Thumbs\.db|\._[^/]+)$/;
const maximumTrackedBytes = 5 * 1024 * 1024;
const failures = [];

for (const relativeFile of trackedFiles) {
  const absoluteFile = resolve(repositoryRoot, relativeFile);

  if (forbiddenDirectories.some((pattern) => pattern.test(relativeFile))) {
    failures.push(`${relativeFile}: generated or local-only directory is tracked`);
  }
  if (environmentFile.test(relativeFile) && !relativeFile.endsWith(".env.example")) {
    failures.push(`${relativeFile}: environment file may contain local credentials`);
  }
  if (secretExtensions.test(relativeFile)) {
    failures.push(`${relativeFile}: private-key or keystore file is tracked`);
  }
  if (operatingSystemFile.test(relativeFile)) {
    failures.push(`${relativeFile}: operating-system metadata is tracked`);
  }
  if (lstatSync(absoluteFile).isSymbolicLink()) {
    failures.push(`${relativeFile}: symbolic links are not allowed in this repository`);
    continue;
  }
  if (statSync(absoluteFile).size > maximumTrackedBytes) {
    failures.push(`${relativeFile}: tracked file exceeds the 5 MiB repository limit`);
  }
}

if (failures.length > 0) {
  console.error("Repository hygiene validation failed:");
  failures.forEach((failure) => console.error(`- ${failure}`));
  process.exitCode = 1;
} else {
  console.log(`Validated ${trackedFiles.length} tracked files for repository hygiene.`);
}

import { spawnSync } from "node:child_process";
import {
  copyFileSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";

const validator = resolve("scripts/validate-license-metadata.mjs");
let mutationCount = 0;
const fixtureFiles = [
  "LICENSE",
  "LICENSES/Apache-2.0.txt",
  "THIRD_PARTY_NOTICES.md",
  "README.md",
  "CONTRIBUTING.md",
  "backend/pom.xml",
  "backend/Dockerfile",
  "backend/src/test/holdout-tls/Dockerfile.runner",
  "backend/mvnw",
  "backend/mvnw.cmd",
  "backend/.mvn/wrapper/maven-wrapper.properties",
  "deploy/images/blackbox-exporter/Dockerfile",
  "deploy/images/caddy/Dockerfile",
  "frontend/package.json",
  "frontend/Dockerfile",
  "tools/mcp-conformance/package.json",
  "docs/openapi.yaml",
  ".github/workflows/docs.yml",
  ".github/workflows/operations-validation.yml",
];

function fixture() {
  const root = mkdtempSync(join(tmpdir(), "openscholar-license-policy-"));
  for (const relativePath of fixtureFiles) {
    const target = join(root, relativePath);
    mkdirSync(dirname(target), { recursive: true });
    copyFileSync(resolve(relativePath), target);
  }
  return root;
}

function run(root) {
  return spawnSync(process.execPath, [validator, root], { encoding: "utf8" });
}

function replace(root, relativePath, before, after) {
  const path = join(root, relativePath);
  const content = readFileSync(path, "utf8");
  if (!content.includes(before)) {
    throw new Error(`mutation source not found in ${relativePath}: ${JSON.stringify(before)}`);
  }
  writeFileSync(path, content.replace(before, after));
}

function replaceLast(root, relativePath, before, after) {
  const path = join(root, relativePath);
  const content = readFileSync(path, "utf8");
  const index = content.lastIndexOf(before);
  if (index < 0) {
    throw new Error(`mutation source not found in ${relativePath}: ${JSON.stringify(before)}`);
  }
  writeFileSync(
    path,
    `${content.slice(0, index)}${after}${content.slice(index + before.length)}`,
  );
}

function expectMutationFailure(name, mutate, expectedDiagnostic) {
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
    throw new Error(`baseline policy failed: stdout=${result.stdout} stderr=${result.stderr}`);
  }
} finally {
  rmSync(baseline, { recursive: true, force: true });
}

expectMutationFailure(
  "Maven license-name drift",
  (root) => replace(
    root,
    "backend/pom.xml",
    "<name>All rights reserved</name>",
    "<name>MIT</name>",
  ),
  "Maven licenses block",
);
expectMutationFailure(
  "commented-out Maven license metadata",
  (root) => {
    const path = join(root, "backend/pom.xml");
    const content = readFileSync(path, "utf8");
    const start = content.indexOf("\t<licenses>");
    const endMarker = "\n\t</licenses>";
    const end = content.indexOf(endMarker, start) + endMarker.length;
    if (start < 0 || end < endMarker.length) {
      throw new Error("Maven licenses block not found for comment mutation");
    }
    writeFileSync(path, `${content.slice(0, start)}<!--\n${content.slice(start, end)}\n-->${content.slice(end)}`);
  },
  "Maven licenses block",
);
expectMutationFailure(
  "npm license drift",
  (root) => replace(
    root,
    "frontend/package.json",
    '"license": "UNLICENSED"',
    '"license": "MIT"',
  ),
  "license must be UNLICENSED",
);
expectMutationFailure(
  "aggregate runtime-image license claim",
  (root) => {
    const path = join(root, "backend/Dockerfile");
    writeFileSync(
      path,
      `${readFileSync(path, "utf8")}\nLABEL org.opencontainers.image.licenses="LicenseRef-Proprietary"\n`,
    );
  },
  "aggregate OCI license labels are prohibited",
);
expectMutationFailure(
  "OpenAPI mutually-exclusive URL drift",
  (root) => replace(
    root,
    "docs/openapi.yaml",
    "    identifier: LicenseRef-Proprietary\n",
    "    identifier: LicenseRef-Proprietary\n    url: https://example.invalid/LICENSE\n",
  ),
  "info.license must contain only",
);
expectMutationFailure(
  "OpenAPI license relocated outside info",
  (root) => {
    const path = join(root, "docs/openapi.yaml");
    const content = readFileSync(path, "utf8");
    const decoy = "x-license-policy:\n  license:\n    name: All rights reserved\n    identifier: LicenseRef-Proprietary\n";
    const changedInfo = content.replace(
      "  license:\n    name: All rights reserved\n    identifier: LicenseRef-Proprietary\n",
      "  license:\n    name: MIT\n    identifier: MIT\n",
    );
    writeFileSync(path, `${decoy}${changedInfo}`);
  },
  "info.license must contain only",
);
expectMutationFailure(
  "comment-hidden contribution terms",
  (root) => replace(
    root,
    "CONTRIBUTING.md",
    "accepted only after the repository owner confirms the contribution terms in writing",
    "<!-- accepted only after the repository owner confirms the contribution terms in writing -->",
  ),
  "standalone paragraph",
);
expectMutationFailure(
  "fence-hidden contribution terms",
  (root) => replace(
    root,
    "CONTRIBUTING.md",
    "The repository does not currently publish a contributor licence agreement or an open-source licence. Do not submit code, documentation, or assets until the repository owner has confirmed the applicable contribution terms in writing; unsolicited contributions will not be merged. Reporting a bug or discussing a proposal does not transfer ownership of either party's material.",
    "```text\nThe repository does not currently publish a contributor licence agreement or an open-source licence. Do not submit code, documentation, or assets until the repository owner has confirmed the applicable contribution terms in writing; unsolicited contributions will not be merged. Reporting a bug or discussing a proposal does not transfer ownership of either party's material.\n```",
  ),
  "standalone paragraph",
);
expectMutationFailure(
  "comment-hidden README badge",
  (root) => replace(
    root,
    "README.md",
    "[![License: all rights reserved](https://img.shields.io/badge/license-all%20rights%20reserved-6b7280.svg)](LICENSE)",
    "<!--[![License: all rights reserved](https://img.shields.io/badge/license-all%20rights%20reserved-6b7280.svg)](LICENSE)-->",
  ),
  "standalone line",
);
expectMutationFailure(
  "HTML-hidden contribution terms",
  (root) => replace(
    root,
    "CONTRIBUTING.md",
    "accepted only after the repository owner confirms the contribution terms in writing",
    "<span hidden>accepted only after the repository owner confirms the contribution terms in writing</span>",
  ),
  "raw HTML is prohibited",
);
expectMutationFailure(
  "inline-code README badge",
  (root) => replace(
    root,
    "README.md",
    "[![License: all rights reserved](https://img.shields.io/badge/license-all%20rights%20reserved-6b7280.svg)](LICENSE)",
    "`[![License: all rights reserved](https://img.shields.io/badge/license-all%20rights%20reserved-6b7280.svg)](LICENSE)`",
  ),
  "standalone line",
);
expectMutationFailure(
  "link-title-only contribution terms",
  (root) => replace(
    root,
    "CONTRIBUTING.md",
    "The repository does not currently publish a contributor licence agreement or an open-source licence. Do not submit code, documentation, or assets until the repository owner has confirmed the applicable contribution terms in writing; unsolicited contributions will not be merged. Reporting a bug or discussing a proposal does not transfer ownership of either party's material.",
    "[Contribution policy](README.md \"The repository does not currently publish a contributor licence agreement or an open-source licence. Do not submit code, documentation, or assets until the repository owner has confirmed the applicable contribution terms in writing; unsolicited contributions will not be merged. Reporting a bug or discussing a proposal does not transfer ownership of either party's material.\")",
  ),
  "standalone paragraph",
);
expectMutationFailure(
  "duplicate contradictory README license section",
  (root) => {
    const path = join(root, "README.md");
    writeFileSync(path, `${readFileSync(path, "utf8")}\n## License\n\nThis project is MIT licensed.\n`);
  },
  "expected exactly one",
);
expectMutationFailure(
  "contradictory README claim under another heading",
  (root) => {
    const path = join(root, "README.md");
    writeFileSync(
      path,
      `${readFileSync(path, "utf8")}\n## Availability\n\nThis project is MIT licensed.\n`,
    );
  },
  "conflicts with the reviewed all-rights-reserved policy",
);
expectMutationFailure(
  "compound SPDX expression in README",
  (root) => {
    const path = join(root, "README.md");
    writeFileSync(
      path,
      `${readFileSync(path, "utf8")}\nSPDX-License-Identifier: LicenseRef-Proprietary OR MIT\n`,
    );
  },
  "conflicts with the reviewed all-rights-reserved policy",
);
expectMutationFailure(
  "contradictory project claim in another document",
  (root) => {
    const directory = join(root, "docs");
    mkdirSync(directory, { recursive: true });
    writeFileSync(join(directory, "LICENSING.md"), "OpenScholar is MIT licensed.\n");
  },
  "project license claim that conflicts",
);
expectMutationFailure(
  "alternate contradictory project wording",
  (root) => {
    const directory = join(root, "docs");
    mkdirSync(directory, { recursive: true });
    writeFileSync(
      join(directory, "LICENSING.md"),
      "OpenScholar is licensed under the MIT License.\n",
    );
  },
  "project license claim that conflicts",
);
expectMutationFailure(
  "emphasis-hidden contradictory project claim",
  (root) => {
    const directory = join(root, "docs");
    mkdirSync(directory, { recursive: true });
    writeFileSync(join(directory, "LICENSING.md"), "OpenScholar is **MIT licensed**.\n");
  },
  "project license claim that conflicts",
);
expectMutationFailure(
  "README usage instructions lose their permission scope",
  (root) => replace(
    root,
    "README.md",
    "The setup, operation, and development instructions below are for copyright holders and people who have received prior written permission. Public source availability does not authorize running, hosting, modifying, or redistributing OpenScholar; see [LICENSE](LICENSE).",
    "Anyone may use the setup, operation, and development instructions below.",
  ),
  "standalone paragraph",
);
expectMutationFailure(
  "Maven SCM drift",
  (root) => replace(
    root,
    "backend/pom.xml",
    "<url>https://github.com/peprick/openscholar-mcp</url>\n\t\t<tag>HEAD</tag>",
    "<url>https://example.invalid/fork</url>\n\t\t<tag>HEAD</tag>",
  ),
  "Maven SCM block",
);
expectMutationFailure(
  "Maven repository distribution claim",
  (root) => replace(
    root,
    "backend/pom.xml",
    "\t\t\t<comments>LicenseRef-Proprietary</comments>",
    "\t\t\t<distribution>repo</distribution>\n\t\t\t<comments>LicenseRef-Proprietary</comments>",
  ),
  "Maven licenses block",
);
expectMutationFailure(
  "npm package made publishable",
  (root) => replace(root, "frontend/package.json", '"private": true', '"private": false'),
  "package must remain private",
);
expectMutationFailure(
  "npm repository-directory drift",
  (root) => replace(
    root,
    "frontend/package.json",
    '"directory": "frontend"',
    '"directory": "unrelated"',
  ),
  "repository directory",
);
expectMutationFailure(
  "npm homepage drift",
  (root) => replace(
    root,
    "frontend/package.json",
    '"homepage": "https://github.com/peprick/openscholar-mcp#readme"',
    '"homepage": "https://example.invalid"',
  ),
  "homepage does not identify",
);
expectMutationFailure(
  "unreviewed package manifest",
  (root) => {
    const directory = join(root, "packages/new-tool");
    mkdirSync(directory, { recursive: true });
    writeFileSync(join(directory, "package.json"), '{"private":true,"license":"UNLICENSED"}\n');
  },
  "reviewed package-manifest inventory",
);
expectMutationFailure(
  "unreviewed package manifest under a build directory",
  (root) => {
    const directory = join(root, "tools/build");
    mkdirSync(directory, { recursive: true });
    writeFileSync(join(directory, "package.json"), '{"private":true,"license":"UNLICENSED"}\n');
  },
  "reviewed package-manifest inventory",
);
expectMutationFailure(
  "unreviewed Dockerfile",
  (root) => {
    const directory = join(root, "services/new-service");
    mkdirSync(directory, { recursive: true });
    writeFileSync(join(directory, "Dockerfile"), "FROM scratch\n");
  },
  "reviewed Dockerfile inventory",
);
expectMutationFailure(
  "conformance-tool license drift",
  (root) => replace(
    root,
    "tools/mcp-conformance/package.json",
    '"license": "UNLICENSED"',
    '"license": "MIT"',
  ),
  "license must be UNLICENSED",
);
expectMutationFailure(
  "frontend aggregate runtime-image license claim",
  (root) => {
    const path = join(root, "frontend/Dockerfile");
    writeFileSync(
      path,
      `${readFileSync(path, "utf8")}\nLABEL org.opencontainers.image.licenses="LicenseRef-Proprietary"\n`,
    );
  },
  "aggregate OCI license labels are prohibited",
);
expectMutationFailure(
  "retained Apache license drift",
  (root) => replace(
    root,
    "LICENSES/Apache-2.0.txt",
    "Apache License",
    "Altered License",
  ),
  "LICENSES/Apache-2.0.txt: SHA-256 must be",
);
expectMutationFailure(
  "third-party notice drift",
  (root) => replace(
    root,
    "THIRD_PARTY_NOTICES.md",
    "Apache Maven Wrapper 3.3.4",
    "Apache Maven Wrapper unknown",
  ),
  "THIRD_PARTY_NOTICES.md: SHA-256 must be",
);
expectMutationFailure(
  "Unix Maven Wrapper drift",
  (root) => replace(root, "backend/mvnw", "version 3.3.4", "version 3.3.5"),
  "backend/mvnw: SHA-256 must be",
);
expectMutationFailure(
  "Windows Maven Wrapper drift",
  (root) => replace(root, "backend/mvnw.cmd", "version 3.3.4", "version 3.3.5"),
  "backend/mvnw.cmd: SHA-256 must be",
);
expectMutationFailure(
  "Maven Wrapper properties drift",
  (root) => replace(
    root,
    "backend/.mvn/wrapper/maven-wrapper.properties",
    "wrapperVersion=3.3.4",
    "wrapperVersion=3.3.5",
  ),
  "maven-wrapper.properties: SHA-256 must be",
);
expectMutationFailure(
  "Maven Wrapper change bypasses documentation workflow",
  (root) => replace(
    root,
    ".github/workflows/docs.yml",
    '      - "backend/mvnw.cmd"',
    '      - "backend/mvnw.cmd.disabled"',
  ),
  "paths must contain",
);
expectMutationFailure(
  "new package manifest bypasses documentation workflow",
  (root) => replace(
    root,
    ".github/workflows/docs.yml",
    '      - "**/package.json"',
    '      - "frontend/package.json"',
  ),
  "paths must contain",
);
expectMutationFailure(
  "suffix Dockerfile bypasses documentation workflow",
  (root) => replace(
    root,
    ".github/workflows/docs.yml",
    '      - "**/*.Dockerfile"',
    '      - "deploy/images/*.Dockerfile"',
  ),
  "paths must contain",
);
expectMutationFailure(
  "Markdown changes bypass documentation workflow",
  (root) => replace(
    root,
    ".github/workflows/docs.yml",
    '      - "**/*.md"',
    '      - "README.md"',
  ),
  "paths must contain",
);
expectMutationFailure(
  "workflow watch moved between events",
  (root) => {
    const watched = '      - "**/*.Dockerfile"';
    replaceLast(
      root,
      ".github/workflows/docs.yml",
      watched,
      '      - "deploy/images/*.Dockerfile"',
    );
    const path = join(root, ".github/workflows/docs.yml");
    const content = readFileSync(path, "utf8");
    writeFileSync(path, content.replace(watched, `${watched}\n${watched}`));
  },
  "paths must contain",
);
expectMutationFailure(
  "Windows Maven Wrapper bypasses operations workflow",
  (root) => replace(
    root,
    ".github/workflows/operations-validation.yml",
    '      - "backend/mvnw.cmd"',
    '      - "backend/mvnw.cmd.disabled"',
  ),
  "paths must contain",
);
expectMutationFailure(
  "root notice drift",
  (root) => replace(root, "LICENSE", "All rights reserved.", "Some rights reserved."),
  "SHA-256 must be",
);

console.log(`License metadata mutation tests passed: ${mutationCount} conflicting, hidden, or drifting states were rejected.`);

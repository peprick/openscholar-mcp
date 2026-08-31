import { createHash } from "node:crypto";
import { readdirSync, readFileSync } from "node:fs";
import { resolve } from "node:path";

const failures = [];
const expectedLicenseSha256 = "28dd1d021810c97375c66c2d12ea23f5e5affa5ed62a0fcc8a4f46a392b7baa4";
const expectedApacheLicenseSha256 = "c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4";
const expectedThirdPartyNoticesSha256 = "40ad01449b84907903c3896129aeb8aa645ff0431de59cd467dba219ec366c06";
const expectedWrapperSha256 = new Map([
  ["backend/mvnw", "bd2191702cc6b31d25c2c055b55981a1bb3d58948bb342279c35dc86e3e6fdbc"],
  ["backend/mvnw.cmd", "4a361e1374a3e5ad6d03e18e9adc0cf181ac5058ac6203b76f0ba3b456b56481"],
  ["backend/.mvn/wrapper/maven-wrapper.properties", "9e702bcd7e68ba1cace0a3382a7082e6e1e1b321688a7a3fbf4d5ae7eb0af533"],
]);
const repositoryRoot = resolve(process.argv[2] ?? ".");

function read(path) {
  return readFileSync(resolve(repositoryRoot, path), "utf8");
}

function effectiveMarkdown(path) {
  const withoutComments = read(path).replace(/<!--[\s\S]*?-->/g, "\n");
  const visible = [];
  let fence = null;
  for (const line of withoutComments.split("\n")) {
    const marker = line.match(/^ {0,3}(`{3,}|~{3,})/u)?.[1];
    if (fence === null && marker !== undefined) {
      fence = { character: marker[0], length: marker.length };
      continue;
    }
    if (fence !== null) {
      const closing = line.match(/^ {0,3}(`+|~+)\s*$/u)?.[1];
      if (closing !== undefined
          && closing[0] === fence.character
          && closing.length >= fence.length) {
        fence = null;
      }
      continue;
    }
    visible.push(line);
  }
  return visible.join("\n");
}

function normalizedVisibleMarkdown(path) {
  return effectiveMarkdown(path)
    .replace(/!\[([^\]]*)\]\([^\n)]*\)/gu, "$1")
    .replace(/\[([^\]]+)\]\([^\n)]*\)/gu, "$1")
    .replace(/[*_~]/gu, "");
}

function requireStandaloneLine(path, expected, count = 1) {
  const content = effectiveMarkdown(path);
  const actual = content.split("\n").filter((line) => line === expected).length;
  if (actual !== count) {
    failures.push(`${path}: expected ${count} standalone line(s) matching ${JSON.stringify(expected)}, found ${actual}`);
  }
}

function requireStandaloneParagraph(path, expected, count = 1) {
  const content = effectiveMarkdown(path);
  const actual = content
    .split(/\n[ \t]*\n/u)
    .map((paragraph) => paragraph.trim())
    .filter((paragraph) => paragraph === expected)
    .length;
  if (actual !== count) {
    failures.push(`${path}: expected ${count} standalone paragraph(s) matching reviewed text, found ${actual}`);
  }
}

function requireNoRawHtml(path) {
  const content = effectiveMarkdown(path);
  if (/<\/?[A-Za-z][A-Za-z0-9-]*(?:\s[^>\n]*)?\/?>/u.test(content)) {
    failures.push(
      `${path}: raw HTML is prohibited outside comments and fenced examples in reviewed licensing surfaces`,
    );
  }
}

function requireNoConflictingLicenseClaims(path) {
  const content = normalizedVisibleMarkdown(path);
  const conflictingClaims = [
    /\b(?:this (?:project|repository)|OpenScholar)\s+is\s+(?:an?\s+)?open[- ]source\b/iu,
    /\b(?:this (?:project|repository)|OpenScholar)\s+is\s+(?:MIT|Apache(?:-2\.0)?|BSD|ISC|GPL|LGPL|AGPL|MPL)[ -]licensed\b/iu,
    /\b(?:this (?:project|repository)|OpenScholar)\s+(?:is\s+)?(?:licensed|released|distributed|available)\s+under\s+(?:the\s+)?(?:MIT|Apache(?: License)?(?:,? Version 2\.0|-2\.0)?|BSD|ISC|GPL|LGPL|AGPL|MPL)\b/iu,
    /\b(?:licensed|released|distributed|available)\s+under\s+(?:the\s+)?(?:MIT|Apache(?: License)?(?:,? Version 2\.0|-2\.0)?|BSD|ISC|GPL|LGPL|AGPL|MPL)\b/iu,
    /SPDX-License-Identifier:/iu,
  ];
  if (conflictingClaims.some((pattern) => pattern.test(content))) {
    failures.push(`${path}: contains a license claim that conflicts with the reviewed all-rights-reserved policy`);
  }
}

function requireNoConflictingProjectLicenseClaims(path) {
  const content = normalizedVisibleMarkdown(path);
  const conflictingClaims = [
    /\b(?:this (?:project|repository)|OpenScholar)\s+is\s+(?:an?\s+)?open[- ]source\b/iu,
    /\b(?:this (?:project|repository)|OpenScholar)\s+is\s+(?:MIT|Apache(?:-2\.0)?|BSD|ISC|GPL|LGPL|AGPL|MPL)[ -]licensed\b/iu,
    /\b(?:this (?:project|repository)|OpenScholar)\s+(?:is\s+)?(?:licensed|released|distributed|available)\s+under\s+(?:the\s+)?(?:MIT|Apache(?: License)?(?:,? Version 2\.0|-2\.0)?|BSD|ISC|GPL|LGPL|AGPL|MPL)\b/iu,
  ];
  if (conflictingClaims.some((pattern) => pattern.test(content))) {
    failures.push(`${path}: contains a project license claim that conflicts with the reviewed all-rights-reserved policy`);
  }
}

function workflowEventPaths(path, event) {
  const lines = read(path).replaceAll("\r\n", "\n").split("\n");
  const eventStart = lines.findIndex((line) => line === `  ${event}:`);
  if (eventStart < 0) {
    return [];
  }
  let eventEnd = lines.length;
  for (let index = eventStart + 1; index < lines.length; index += 1) {
    if (/^  [A-Za-z_][A-Za-z0-9_-]*:/u.test(lines[index])) {
      eventEnd = index;
      break;
    }
  }
  const pathsStart = lines.findIndex(
    (line, index) => index > eventStart && index < eventEnd && line === "    paths:",
  );
  if (pathsStart < 0) {
    return [];
  }
  const entries = [];
  for (let index = pathsStart + 1; index < eventEnd; index += 1) {
    const match = lines[index].match(/^      - "([^"]+)"$/u);
    if (match !== null) {
      entries.push(match[1]);
    } else if (lines[index] !== "" && !lines[index].startsWith("      ")) {
      break;
    }
  }
  return entries;
}

function requireWorkflowPath(path, expected) {
  for (const event of ["push", "pull_request"]) {
    const actual = workflowEventPaths(path, event)
      .filter((entry) => entry === expected).length;
    if (actual !== 1) {
      failures.push(`${path}: ${event}.paths must contain ${JSON.stringify(expected)} exactly once, found ${actual}`);
    }
  }
}

function discoverRepositoryFiles(matches) {
  const ignoredDirectories = new Set([
    ".git",
    "backend/target",
    "frontend/.next",
    "frontend/coverage",
    "frontend/node_modules",
    "frontend/playwright-report",
    "frontend/test-results",
    "tools/mcp-conformance/node_modules",
  ]);
  const discovered = [];
  function visit(relativeDirectory) {
    const directory = resolve(repositoryRoot, relativeDirectory || ".");
    const entries = readdirSync(directory, { withFileTypes: true })
      .sort((left, right) => left.name.localeCompare(right.name));
    for (const entry of entries) {
      const relativePath = relativeDirectory === ""
        ? entry.name
        : `${relativeDirectory}/${entry.name}`;
      if (entry.isDirectory()) {
        if (!ignoredDirectories.has(relativePath)) {
          visit(relativePath);
        }
      } else if (matches(entry.name, relativePath)) {
        if (!entry.isFile()) {
          failures.push(`${relativePath}: reviewed metadata surfaces must be regular files`);
        }
        discovered.push(relativePath);
      }
    }
  }
  visit("");
  return discovered;
}

function requireExactInventory(label, actual, expected) {
  const actualSorted = [...actual].sort();
  const expectedSorted = [...expected].sort();
  if (JSON.stringify(actualSorted) !== JSON.stringify(expectedSorted)) {
    failures.push(`${label}: expected ${JSON.stringify(expectedSorted)}, found ${JSON.stringify(actualSorted)}`);
  }
}

function markdownSection(path, heading) {
  const content = effectiveMarkdown(path);
  const lines = content.split("\n");
  const escapedHeading = heading.replace(/[.*+?^${}()|[\]\\]/gu, "\\$&");
  const reviewedHeading = new RegExp(`^##[ \\t]+${escapedHeading}[ \\t]*#*[ \\t]*$`, "u");
  const starts = lines
    .map((line, index) => reviewedHeading.test(line) ? index : -1)
    .filter((index) => index >= 0);
  if (starts.length !== 1) {
    failures.push(`${path}: expected exactly one ${JSON.stringify(`## ${heading}`)} section, found ${starts.length}`);
    return "";
  }
  const bodyStart = starts[0] + 1;
  let bodyEnd = lines.length;
  for (let index = bodyStart; index < lines.length; index += 1) {
    if (/^##(?:[ \\t]|$)/u.test(lines[index])) {
      bodyEnd = index;
      break;
    }
  }
  return lines.slice(bodyStart, bodyEnd).join("\n").trim();
}

function directChildXmlBlocks(xml, parentName, childName) {
  const effectiveXml = xml.replace(/<!--[\s\S]*?-->/g, "");
  const tokens = /<\/?([A-Za-z_][\w:.-]*)(?:\s[^<>]*?)?\/?>/g;
  const stack = [];
  const blocks = [];
  let match;
  while ((match = tokens.exec(effectiveXml)) !== null) {
    const token = match[0];
    const name = match[1];
    if (token.startsWith("</")) {
      const opened = stack.pop();
      if (opened?.name === name && opened.target) {
        blocks.push(effectiveXml.slice(opened.start, match.index + token.length));
      }
      continue;
    }
    if (token.endsWith("/>")) {
      continue;
    }
    stack.push({
      name,
      start: match.index,
      target: name === childName && stack.at(-1)?.name === parentName,
    });
  }
  return blocks;
}

function directYamlChildBlock(yaml, parentName, childName) {
  const lines = yaml.replaceAll("\r\n", "\n").split("\n");
  const parentMarkers = lines
    .map((line, index) => line === `${parentName}:` ? index : -1)
    .filter((index) => index >= 0);
  if (parentMarkers.length !== 1) {
    return [];
  }
  const parentStart = parentMarkers[0];
  let parentEnd = lines.length;
  for (let index = parentStart + 1; index < lines.length; index += 1) {
    if (lines[index] !== "" && !lines[index].startsWith(" ") && !lines[index].startsWith("#")) {
      parentEnd = index;
      break;
    }
  }
  const childMarker = `  ${childName}:`;
  const childMarkers = [];
  for (let index = parentStart + 1; index < parentEnd; index += 1) {
    if (lines[index] === childMarker) {
      childMarkers.push(index);
    }
  }
  if (childMarkers.length !== 1) {
    return [];
  }
  const childStart = childMarkers[0];
  let childEnd = parentEnd;
  for (let index = childStart + 1; index < parentEnd; index += 1) {
    const line = lines[index];
    if (line !== "" && !line.startsWith("    ") && !line.trimStart().startsWith("#")) {
      childEnd = index;
      break;
    }
  }
  return lines.slice(childStart, childEnd);
}

function requirePrivateUnlicensedPackage(path, expectedDirectory) {
  const manifest = JSON.parse(read(path));
  if (manifest.private !== true) {
    failures.push(`${path}: package must remain private`);
  }
  if (manifest.license !== "UNLICENSED") {
    failures.push(`${path}: license must be UNLICENSED`);
  }
  if (manifest.repository?.type !== "git") {
    failures.push(`${path}: repository type must be git`);
  }
  if (manifest.repository?.url !== "git+https://github.com/peprick/openscholar-mcp.git") {
    failures.push(`${path}: repository URL does not identify the canonical repository`);
  }
  if (manifest.repository?.directory !== expectedDirectory) {
    failures.push(`${path}: repository directory must be ${expectedDirectory}`);
  }
  if (manifest.homepage !== "https://github.com/peprick/openscholar-mcp#readme") {
    failures.push(`${path}: homepage does not identify the canonical repository`);
  }
  if (manifest.bugs?.url !== "https://github.com/peprick/openscholar-mcp/issues") {
    failures.push(`${path}: bugs URL does not identify the canonical issue tracker`);
  }
}

function requireFileSha256(path, expected) {
  const actual = createHash("sha256").update(read(path)).digest("hex");
  if (actual !== expected) {
    failures.push(`${path}: SHA-256 must be ${expected}, found ${actual}`);
  }
}

requireFileSha256("LICENSE", expectedLicenseSha256);
requireFileSha256("LICENSES/Apache-2.0.txt", expectedApacheLicenseSha256);
requireFileSha256("THIRD_PARTY_NOTICES.md", expectedThirdPartyNoticesSha256);
for (const [path, expectedSha256] of expectedWrapperSha256) {
  requireFileSha256(path, expectedSha256);
}

requireStandaloneLine(
  "README.md",
  "[![License: all rights reserved](https://img.shields.io/badge/license-all%20rights%20reserved-6b7280.svg)](LICENSE)",
);
requireNoRawHtml("README.md");
requireNoConflictingLicenseClaims("README.md");
const expectedReadmeLicense = "OpenScholar is source-visible and **all rights reserved**. No open-source licence or permission to use, modify, host, or redistribute the project is granted. See [LICENSE](LICENSE) for the controlling notice and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for directly retained third-party material. Other third-party components remain under their respective licences.";
if (markdownSection("README.md", "License") !== expectedReadmeLicense) {
  failures.push("README.md: License section does not match the reviewed all-rights-reserved statement");
}
const expectedReadmeAuthorizedUse = "The setup, operation, and development instructions below are for copyright holders and people who have received prior written permission. Public source availability does not authorize running, hosting, modifying, or redistributing OpenScholar; see [LICENSE](LICENSE).";
requireStandaloneParagraph("README.md", expectedReadmeAuthorizedUse);
const expectedContributionOpening = "Because the project is source-visible and all rights reserved, code, documentation, and asset contributions are accepted only after the repository owner confirms the contribution terms in writing. Opening an issue does not grant permission to prepare or submit a patch.";
const expectedContributionClosing = "The repository does not currently publish a contributor licence agreement or an open-source licence. Do not submit code, documentation, or assets until the repository owner has confirmed the applicable contribution terms in writing; unsolicited contributions will not be merged. Reporting a bug or discussing a proposal does not transfer ownership of either party's material.";
requireStandaloneParagraph("CONTRIBUTING.md", expectedContributionOpening);
requireStandaloneParagraph("CONTRIBUTING.md", expectedContributionClosing);
requireNoRawHtml("CONTRIBUTING.md");
requireNoConflictingLicenseClaims("CONTRIBUTING.md");
for (const markdownPath of discoverRepositoryFiles((name) => name.endsWith(".md"))) {
  requireNoConflictingProjectLicenseClaims(markdownPath);
}

requireExactInventory(
  "reviewed package-manifest inventory",
  discoverRepositoryFiles((name) => name === "package.json"),
  ["frontend/package.json", "tools/mcp-conformance/package.json"],
);
requireExactInventory(
  "reviewed Dockerfile inventory",
  discoverRepositoryFiles((name) => name === "Dockerfile"
    || name.startsWith("Dockerfile.")
    || name.endsWith(".Dockerfile")),
  [
    "backend/Dockerfile",
    "backend/src/test/holdout-tls/Dockerfile.runner",
    "deploy/images/blackbox-exporter/Dockerfile",
    "deploy/images/caddy/Dockerfile",
    "frontend/Dockerfile",
  ],
);

requirePrivateUnlicensedPackage("frontend/package.json", "frontend");
requirePrivateUnlicensedPackage("tools/mcp-conformance/package.json", "tools/mcp-conformance");

const expectedPomLicenses = `<licenses>
\t\t<license>
\t\t\t<name>All rights reserved</name>
\t\t\t<url>https://github.com/peprick/openscholar-mcp/blob/main/LICENSE</url>
\t\t\t<comments>LicenseRef-Proprietary</comments>
\t\t</license>
\t</licenses>`;
const pomLicenseBlocks = directChildXmlBlocks(
  read("backend/pom.xml"),
  "project",
  "licenses",
);
if (pomLicenseBlocks.length !== 1 || pomLicenseBlocks[0] !== expectedPomLicenses) {
  failures.push("backend/pom.xml: Maven licenses block does not match the reviewed proprietary metadata");
}
const expectedPomScm = `<scm>
		<connection>scm:git:https://github.com/peprick/openscholar-mcp.git</connection>
		<developerConnection>scm:git:ssh://git@github.com/peprick/openscholar-mcp.git</developerConnection>
		<url>https://github.com/peprick/openscholar-mcp</url>
		<tag>HEAD</tag>
	</scm>`;
const pomScmBlocks = directChildXmlBlocks(read("backend/pom.xml"), "project", "scm");
if (pomScmBlocks.length !== 1 || pomScmBlocks[0] !== expectedPomScm) {
  failures.push("backend/pom.xml: Maven SCM block does not match the canonical repository metadata");
}

for (const path of ["backend/Dockerfile", "frontend/Dockerfile"]) {
  const dockerfile = read(path);
  if (dockerfile.includes("org.opencontainers.image.licenses")) {
    failures.push(
      `${path}: aggregate OCI license labels are prohibited because runtime contents retain component-specific licenses`,
    );
  }
}

for (const wrapperPath of expectedWrapperSha256.keys()) {
  requireWorkflowPath(".github/workflows/docs.yml", wrapperPath);
}
requireWorkflowPath(
  ".github/workflows/operations-validation.yml", "backend/mvnw.cmd");
for (const watchedPattern of [
  "**/*.md",
  "**/Dockerfile*",
  "**/*.Dockerfile",
  "**/package.json",
]) {
  requireWorkflowPath(".github/workflows/docs.yml", watchedPattern);
}

const openapi = read("docs/openapi.yaml");
const openapiLicense = directYamlChildBlock(openapi, "info", "license");
if (openapiLicense.join("\n") !== "  license:\n    name: All rights reserved\n    identifier: LicenseRef-Proprietary") {
  failures.push("docs/openapi.yaml: info.license must contain only the reviewed name and identifier");
}

if (failures.length > 0) {
  for (const failure of failures) {
    console.error(`license-metadata-validation: ${failure}`);
  }
  process.exitCode = 1;
} else {
  console.log("License metadata validation passed: required repository-owned metadata matches the reviewed policy.");
}

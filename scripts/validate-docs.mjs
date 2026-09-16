import { execFileSync } from "node:child_process";
import { existsSync, readFileSync, statSync } from "node:fs";
import { dirname, extname, relative, resolve } from "node:path";

const repositoryRoot = execFileSync("git", ["rev-parse", "--show-toplevel"], {
  encoding: "utf8",
}).trim();

const markdownFiles = execFileSync(
  "git",
  ["ls-files", "--cached", "--others", "--exclude-standard", "*.md"],
  {
    cwd: repositoryRoot,
    encoding: "utf8",
  },
)
  .split(/\r?\n/)
  .filter(Boolean)
  .filter((file) => existsSync(resolve(repositoryRoot, file)));

const failures = [];
let checkedLinks = 0;
let checkedAnchors = 0;
const anchorsByFile = new Map();

function withoutHtmlComments(markdown, relativeFile) {
  let output = "";
  let cursor = 0;
  let depth = 0;
  let unclosedReported = false;
  while (cursor < markdown.length) {
    const opening = markdown.indexOf("<!--", cursor);
    const closing = markdown.indexOf("-->", cursor);
    if (depth === 0) {
      if (opening < 0) {
        output += markdown.slice(cursor);
        break;
      }
      output += markdown.slice(cursor, opening);
      depth = 1;
      cursor = opening + 4;
      continue;
    }
    if (opening >= 0 && (closing < 0 || opening < closing)) {
      depth += 1;
      cursor = opening + 4;
      continue;
    }
    if (closing < 0) {
      failures.push(`${relativeFile}: malformed HTML comment is not closed`);
      unclosedReported = true;
      break;
    }
    depth -= 1;
    cursor = closing + 3;
    if (depth === 0) output += "\n";
  }
  if (depth > 0 && !unclosedReported) {
    failures.push(`${relativeFile}: malformed HTML comment is not closed`);
  }
  return output;
}

function outsideCodeFences(markdown) {
  let fence = null;
  return markdown
    .split(/\r?\n/)
    .map((line) => {
      const marker = line.match(/^\s{0,3}(`{3,}|~{3,})/);
      if (marker) {
        if (fence === null) {
          fence = marker[1];
        } else if (marker[1][0] === fence[0] && marker[1].length >= fence.length) {
          fence = null;
        }
        return "";
      }
      return fence === null ? line : "";
    })
    .join("\n");
}

function headingAnchors(absoluteFile) {
  if (anchorsByFile.has(absoluteFile)) return anchorsByFile.get(absoluteFile);
  const markdown = withoutHtmlComments(
    outsideCodeFences(readFileSync(absoluteFile, "utf8")),
    relative(repositoryRoot, absoluteFile),
  );
  const anchors = new Set();
  const lines = markdown.split("\n");
  const counts = new Map();
  for (let index = 0; index < lines.length; index += 1) {
    const atx = lines[index].match(/^\s{0,3}#{1,6}\s+(.+?)(?:\s+#+\s*)?$/);
    const setext = index + 1 < lines.length && /^\s{0,3}(?:=+|-+)\s*$/.test(lines[index + 1]);
    const heading = atx?.[1] ?? (setext && lines[index].trim() ? lines[index].trim() : null);
    if (heading === null) continue;
    const slug = heading
      .replace(/<[^>]+>/g, "")
      .replace(/!?\[([^\]]*)]\([^)]*\)/g, "$1")
      .replace(/\\([^\w\s])/g, "$1")
      .toLowerCase()
      .replace(/[^\p{L}\p{N}_\-\s]/gu, "")
      .replace(/\s/g, "-");
    let candidate = slug;
    let suffix = counts.get(slug) ?? 0;
    while (anchors.has(candidate)) candidate = `${slug}-${++suffix}`;
    counts.set(slug, suffix);
    anchors.add(candidate);
    if (setext && !atx) index += 1;
  }
  for (const match of markdown.matchAll(/\b(?:id|name)=["']([^"']+)["']/g)) {
    anchors.add(match[1]);
  }
  anchorsByFile.set(absoluteFile, anchors);
  return anchors;
}

for (const relativeFile of markdownFiles) {
  const absoluteFile = resolve(repositoryRoot, relativeFile);
  const markdown = withoutHtmlComments(
    outsideCodeFences(readFileSync(absoluteFile, "utf8")),
    relativeFile,
  );
  if (markdown.includes("<!--") || markdown.includes("-->")) {
    failures.push(`${relativeFile}: unexpected HTML comment marker outside a complete comment`);
  }
  const links = markdown.matchAll(/!?\[[^\]]*]\(([^)\n]+)\)/g);

  for (const match of links) {
    let destination = match[1].trim();
    if (destination.startsWith("<")) {
      const closingBracket = destination.indexOf(">");
      destination =
        closingBracket === -1
          ? destination
          : destination.slice(1, closingBracket);
    } else {
      destination = destination.split(/\s+/, 1)[0];
    }

    destination = destination.replace(/\\([\\ ()])/g, "$1");
    if (
      destination === "" ||
      /^[a-z][a-z\d+.-]*:/i.test(destination)
    ) {
      continue;
    }

    const hashIndex = destination.indexOf("#");
    const pathPart = (hashIndex < 0 ? destination : destination.slice(0, hashIndex)).split("?", 1)[0];
    const fragment = hashIndex < 0 ? "" : destination.slice(hashIndex + 1);
    let decodedPath;
    try {
      decodedPath = decodeURIComponent(pathPart);
    } catch {
      failures.push(relativeFile + ": invalid URL encoding in " + destination);
      continue;
    }

    checkedLinks += 1;
    const target = decodedPath === "" ? absoluteFile : resolve(dirname(absoluteFile), decodedPath);
    if (!existsSync(target)) {
      failures.push(relativeFile + ": missing local target " + destination);
      continue;
    }

    if (destination.endsWith("/") && !statSync(target).isDirectory()) {
      failures.push(relativeFile + ": expected a directory at " + destination);
    }
    if (fragment !== "" && extname(target).toLowerCase() === ".md" && statSync(target).isFile()) {
      let decodedFragment;
      try {
        decodedFragment = decodeURIComponent(fragment);
      } catch {
        failures.push(relativeFile + ": invalid anchor encoding in " + destination);
        continue;
      }
      checkedAnchors += 1;
      if (!headingAnchors(target).has(decodedFragment)) {
        failures.push(relativeFile + ": missing heading anchor " + destination);
      }
    }
  }
}

if (failures.length > 0) {
  console.error("Documentation validation failed:");
  for (const failure of failures) {
    console.error("- " + failure);
  }
  process.exitCode = 1;
} else {
  console.log(
    "Validated " +
      checkedLinks +
      " local links (" + checkedAnchors + " heading anchors) across " +
      markdownFiles.length +
      " Markdown files.",
  );
}

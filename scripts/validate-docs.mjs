import { execFileSync } from "node:child_process";
import { existsSync, readFileSync, statSync } from "node:fs";
import { dirname, resolve } from "node:path";

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

for (const relativeFile of markdownFiles) {
  const absoluteFile = resolve(repositoryRoot, relativeFile);
  const markdown = readFileSync(absoluteFile, "utf8").replace(
    /^(?:\x60{3}|~{3})[\s\S]*?^(?:\x60{3}|~{3})\s*$/gm,
    "",
  );
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
      destination.startsWith("#") ||
      /^(?:https?:|mailto:|tel:)/i.test(destination)
    ) {
      continue;
    }

    const pathPart = destination.split("#", 1)[0].split("?", 1)[0];
    let decodedPath;
    try {
      decodedPath = decodeURIComponent(pathPart);
    } catch {
      failures.push(relativeFile + ": invalid URL encoding in " + destination);
      continue;
    }

    checkedLinks += 1;
    const target = resolve(dirname(absoluteFile), decodedPath);
    if (!existsSync(target)) {
      failures.push(relativeFile + ": missing local target " + destination);
      continue;
    }

    if (destination.endsWith("/") && !statSync(target).isDirectory()) {
      failures.push(relativeFile + ": expected a directory at " + destination);
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
      " local links across " +
      markdownFiles.length +
      " Markdown files.",
  );
}

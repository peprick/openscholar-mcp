import { cp, copyFile, mkdir, readFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const projectRoot = fileURLToPath(new URL("..", import.meta.url));
const distributionRoot = path.join(projectRoot, "node_modules", "pdfjs-dist");
const packageMetadata = JSON.parse(
  await readFile(path.join(distributionRoot, "package.json"), "utf8"),
);
const loaderSource = await readFile(
  path.join(projectRoot, "src", "features", "reader", "pdfjs-loader.ts"),
  "utf8",
);
const configuredVersion = loaderSource.match(
  /export const PDFJS_VERSION = "([^"]+)";/,
)?.[1];

if (configuredVersion !== packageMetadata.version) {
  throw new Error(
    `PDF.js loader version ${configuredVersion ?? "<missing>"} does not match installed version ${packageMetadata.version}.`,
  );
}

const targetRoot = path.join(
  projectRoot,
  "public",
  "pdfjs",
  packageMetadata.version,
);

await mkdir(targetRoot, { recursive: true });
await copyFile(
  path.join(distributionRoot, "legacy", "build", "pdf.worker.min.mjs"),
  path.join(targetRoot, "pdf.worker.min.mjs"),
);
await copyFile(
  path.join(distributionRoot, "LICENSE"),
  path.join(targetRoot, "LICENSE"),
);

for (const directory of ["cmaps", "iccs", "standard_fonts", "wasm"]) {
  await cp(path.join(distributionRoot, directory), path.join(targetRoot, directory), {
    force: true,
    recursive: true,
  });
}

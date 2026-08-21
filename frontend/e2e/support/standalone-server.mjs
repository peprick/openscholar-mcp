import { cpSync, existsSync, mkdirSync } from "node:fs";
import { spawn } from "node:child_process";
import { join } from "node:path";

const projectRoot = process.cwd();
const standaloneRoot = join(projectRoot, ".next", "standalone");
const serverEntry = join(standaloneRoot, "server.js");
if (!existsSync(serverEntry)) {
  throw new Error("Build the frontend before starting the standalone E2E server.");
}

mkdirSync(join(standaloneRoot, ".next"), { recursive: true });
cpSync(join(projectRoot, ".next", "static"), join(standaloneRoot, ".next", "static"), {
  recursive: true,
});
cpSync(join(projectRoot, "public"), join(standaloneRoot, "public"), {
  recursive: true,
});

const child = spawn(process.execPath, ["server.js"], {
  cwd: standaloneRoot,
  env: {
    ...process.env,
    HOSTNAME: process.env.PLAYWRIGHT_APP_HOST ?? "127.0.0.1",
    PORT: process.env.PLAYWRIGHT_APP_PORT ?? "3100",
  },
  stdio: "inherit",
});

function stop(signal) {
  if (!child.killed) child.kill(signal);
}

process.once("SIGINT", () => stop("SIGINT"));
process.once("SIGTERM", () => stop("SIGTERM"));
child.once("error", (error) => {
  throw error;
});
child.once("exit", (code, signal) => {
  if (signal !== null) {
    process.kill(process.pid, signal);
    return;
  }
  process.exitCode = code ?? 1;
});

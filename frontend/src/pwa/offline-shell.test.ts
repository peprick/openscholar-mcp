import { readFileSync } from "node:fs";
import { resolve } from "node:path";

import { describe, expect, it } from "vitest";

describe("static offline shell", () => {
  it("is self-contained and contains no account or document integration", () => {
    const html = readFileSync(
      resolve(process.cwd(), "public/offline.html"),
      "utf8",
    );

    expect(html).toContain("OpenScholar can’t be reached.");
    expect(html).toContain("No paper documents are stored");
    expect(html).not.toMatch(/<script\b/iu);
    expect(html).not.toMatch(/api\/auth|cookie|localStorage|sessionStorage/iu);
    expect(html).not.toMatch(/\.pdf\b|\.docx?\b/iu);
  });
});

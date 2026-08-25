import { readFileSync } from "node:fs";
import { resolve } from "node:path";

import { describe, expect, it } from "vitest";

describe("static offline shell", () => {
  it("is account-neutral and loads only the audited offline-pack runtime", () => {
    const html = readFileSync(
      resolve(process.cwd(), "public/offline.html"),
      "utf8",
    );

    expect(html).toContain("Read your saved research metadata.");
    expect(html).toContain("No paper documents are stored");
    expect(html.match(/<script\b/giu)).toHaveLength(1);
    expect(html).toMatch(/<script defer src="\/offline-pack\.js"><\/script>/iu);
    expect(html).toContain('data-offline-reader-revision="2026-08-24-r3"');
    expect(html).not.toMatch(/api\/auth|cookie|localStorage|sessionStorage/iu);
    expect(html).not.toMatch(/\.pdf\b|\.docx?\b/iu);
    expect(html).toContain("Open encrypted offline collection");
    expect(html).not.toContain("offline-pack-title\">Thesis");
  });
});

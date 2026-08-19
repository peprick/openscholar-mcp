import { describe, expect, it } from "vitest";

import { citationFilename } from "@/features/library/library-client";

describe("citationFilename", () => {
  it("prefers RFC 5987, keeps a strict basename, and forces the requested extension", () => {
    expect(
      citationFilename(
        "attachment; filename=legacy.exe; filename*=UTF-8''research-set.pdf",
        "bibtex",
      ),
    ).toBe("research-set.bib");
    expect(
      citationFilename('attachment; filename="reading-list.bib"', "csl-json"),
    ).toBe("reading-list.json");
  });

  it.each([
    "attachment; filename*=UTF-8''..%2Fsecret.bib",
    "attachment; filename*=UTF-8''report%0Ahidden.bib",
    "attachment; filename*=UTF-8''report%E2%80%AEfdp.exe",
    'attachment; filename=".hidden.bib"',
    "attachment; filename*=UTF-8''bad%ZZname.bib",
  ])("falls back for a hostile or invalid filename: %s", (header) => {
    expect(citationFilename(header, "bibtex")).toBe(
      "openscholar-library.bib",
    );
  });
});

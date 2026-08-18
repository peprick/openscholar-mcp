import { describe, expect, it } from "vitest";

import { identifierHref } from "@/shared/formatting/display";

describe("identifierHref", () => {
  it("encodes DOI delimiters without flattening path separators", () => {
    expect(identifierHref("DOI", "10.1000/a?b#c/d")).toBe(
      "https://doi.org/10.1000/a%3Fb%23c/d",
    );
  });

  it("preserves the slash in legacy arXiv identifiers", () => {
    expect(identifierHref("ARXIV", "hep-th/9901001")).toBe(
      "https://arxiv.org/abs/hep-th/9901001",
    );
  });
});

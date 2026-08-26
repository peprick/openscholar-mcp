import { describe, expect, it } from "vitest";

import {
  identifierHref,
  providerDisplayName,
} from "@/shared/formatting/display";

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

describe("providerDisplayName", () => {
  it("uses the provider's public product name", () => {
    expect(providerDisplayName("OPENALEX")).toBe("OpenAlex");
    expect(providerDisplayName("DATACITE")).toBe("DataCite");
    expect(providerDisplayName("DOAJ")).toBe("DOAJ");
    expect(providerDisplayName("CORE")).toBe("CORE");
    expect(providerDisplayName("EUROPE_PMC")).toBe("Europe PMC");
  });

  it("humanizes an unknown future provider safely", () => {
    expect(providerDisplayName("FUTURE_INDEX")).toBe("Future Index");
  });
});

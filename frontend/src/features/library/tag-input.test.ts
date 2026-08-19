import { describe, expect, it } from "vitest";

import { formatTagInput, parseTagInput } from "@/features/library/tag-input";

describe("tag input", () => {
  it("round-trips an empty tag list", () => {
    const parsed = parseTagInput("");
    expect(parsed.success).toBe(true);
    if (parsed.success) expect(parsed.data).toEqual([]);
    expect(formatTagInput([])).toBe("");
  });

  it("splits, trims, collapses, and lowercases comma-separated tags", () => {
    const parsed = parseTagInput(" Methods, key   Result ");
    expect(parsed.success).toBe(true);
    if (parsed.success) {
      expect(parsed.data).toEqual(["methods", "key result"]);
    }
  });
});

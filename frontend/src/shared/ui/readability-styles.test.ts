import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { describe, expect, it } from "vitest";

const styles = readFileSync(resolve(process.cwd(), "src/app/globals.css"), "utf8");

function luminance(hex: string): number {
  const channels = hex.match(/[\da-f]{2}/gi)!.map((value) => {
    const channel = Number.parseInt(value, 16) / 255;
    return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4;
  });
  return channels[0]! * 0.2126 + channels[1]! * 0.7152 + channels[2]! * 0.0722;
}

describe("readable UI styles", () => {
  it("uses a consistent placeholder color with readable surface contrast", () => {
    expect(styles).toMatch(/input::placeholder\s*\{\s*color: var\(--ink-faint\);\s*opacity: 1;/);
    const placeholder = styles.match(/--ink-faint:\s*(#[\da-f]{6})/i)![1]!;
    for (const token of ["surface", "paper", "surface-muted"]) {
      const background = styles.match(new RegExp(`--${token}:\\s*(#[\\da-f]{6})`, "i"))![1]!;
      expect((luminance(background) + 0.05) / (luminance(placeholder) + 0.05)).toBeGreaterThanOrEqual(4.5);
    }
  });

  it("wraps long headings and keeps collapsed save controls hidden", () => {
    expect(styles).toMatch(/h1,\s*h2,\s*h3\s*\{\s*overflow-wrap: anywhere;/);
    expect(styles).toMatch(/\.savePaperForm\[hidden\]\s*\{\s*display: none;/);
  });
});

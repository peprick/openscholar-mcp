import { apiProblemSchema } from "@/shared/api/schemas";

export async function responseErrorMessage(
  response: Response,
  fallback: string,
): Promise<string> {
  try {
    const parsed = apiProblemSchema.safeParse(await response.json());
    return parsed.success ? parsed.data.detail : fallback;
  } catch {
    return fallback;
  }
}

export function citationFilename(
  contentDisposition: string | null,
  format: "bibtex" | "csl-json",
): string {
  const extension = format === "bibtex" ? ".bib" : ".json";
  const fallback = `openscholar-library${extension}`;
  const extended = contentDisposition?.match(
    /(?:^|;)\s*filename\*=UTF-8''([^;\r\n]*)/i,
  )?.[1];
  const basic = contentDisposition?.match(
    /(?:^|;)\s*filename=(?:\"([^\"\r\n]*)\"|([^;\s\r\n]*))/i,
  );
  const encoded = extended ?? basic?.[1] ?? basic?.[2];
  if (encoded === undefined || encoded === "") return fallback;

  try {
    const decoded = decodeURIComponent(encoded);
    const lastDot = decoded.lastIndexOf(".");
    const basename = lastDot > 0 ? decoded.slice(0, lastDot) : decoded;
    if (/^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$/.test(basename)) {
      return `${basename}${extension}`;
    }
  } catch {
    // Fall through to a deterministic filename when RFC 5987 decoding fails.
  }
  return fallback;
}

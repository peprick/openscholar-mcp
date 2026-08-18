const integerFormatter = new Intl.NumberFormat("en", {
  maximumFractionDigits: 0,
});

const instantFormatter = new Intl.DateTimeFormat("en", {
  dateStyle: "medium",
  timeStyle: "short",
  timeZone: "UTC",
});

const publicationDateFormatter = new Intl.DateTimeFormat("en", {
  day: "numeric",
  month: "short",
  year: "numeric",
  timeZone: "UTC",
});

export function humanizeEnum(value: string): string {
  return value
    .toLowerCase()
    .split("_")
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(" ");
}

export function formatInteger(value: number | null): string {
  return value === null ? "Not available" : integerFormatter.format(value);
}

export function formatInstant(value: string | null): string {
  return value === null ? "Not checked" : `${instantFormatter.format(new Date(value))} UTC`;
}

export function formatPublicationDate(
  date: string | null,
  year: number | null,
): string {
  if (date !== null) {
    return publicationDateFormatter.format(new Date(`${date}T00:00:00Z`));
  }
  return year === null ? "Publication date unavailable" : String(year);
}

export function formatPercent(value: number): string {
  return `${Math.round(value * 100)}%`;
}

export function authorSummary(authors: Array<{ name: string }>): string {
  if (authors.length === 0) {
    return "Authors unavailable";
  }
  if (authors.length <= 3) {
    return authors.map((author) => author.name).join(", ");
  }
  return `${authors
    .slice(0, 3)
    .map((author) => author.name)
    .join(", ")} +${authors.length - 3} more`;
}

function encodeIdentifierPath(value: string): string {
  return value
    .split("/")
    .map((segment) => encodeURIComponent(segment))
    .join("/");
}

export function identifierHref(type: string, value: string): string | null {
  switch (type) {
    case "DOI":
      return `https://doi.org/${encodeIdentifierPath(value)}`;
    case "ARXIV":
      return `https://arxiv.org/abs/${encodeIdentifierPath(value)}`;
    case "OPENALEX":
      return `https://openalex.org/${encodeURIComponent(value)}`;
    case "PMID":
      return `https://pubmed.ncbi.nlm.nih.gov/${encodeURIComponent(value)}/`;
    case "PMCID":
      return `https://www.ncbi.nlm.nih.gov/pmc/articles/${encodeURIComponent(value)}/`;
    default:
      return null;
  }
}

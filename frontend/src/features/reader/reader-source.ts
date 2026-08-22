import type {
  PaperAccessLocation,
  PaperAccessResponse,
} from "@/shared/api/schemas";

export type ReaderSource = {
  paperId: string;
  locationId: string;
  pdfUrl: string;
  landingPageUrl: string | null;
  hostDomain: string;
  source: string;
  versionType: PaperAccessLocation["versionType"];
  license: string | null;
  verifiedAt: string;
};

export type VerifiedPdfLocation = PaperAccessLocation & {
  pdfUrl: string;
  verifiedAt: string;
};

export function isReadablePdfAccessStatus(
  status: PaperAccessLocation["accessStatus"],
): boolean {
  return (
    status === "OPEN_PDF" ||
    status === "REPOSITORY_COPY" ||
    status === "PREPRINT"
  );
}

function secureReaderUrl(value: string): URL | null {
  try {
    const url = new URL(value);
    if (
      url.protocol !== "https:" ||
      url.port !== "" ||
      url.username !== "" ||
      url.password !== "" ||
      url.hash !== ""
    ) {
      return null;
    }
    return url;
  } catch {
    return null;
  }
}

function safeLandingPageUrl(value: string | null): string | null {
  if (value === null) {
    return null;
  }
  try {
    const url = new URL(value);
    return (url.protocol === "https:" || url.protocol === "http:") &&
      url.username === "" &&
      url.password === ""
      ? value
      : null;
  } catch {
    return null;
  }
}

export function selectVerifiedPdfLocation(
  access: PaperAccessResponse,
  expectedPaperId: string,
  locationId: string,
): VerifiedPdfLocation | null {
  if (access.paperId !== expectedPaperId) {
    return null;
  }

  const location = access.locations.find((candidate) => candidate.id === locationId);
  if (
    location === undefined ||
    location.verificationStatus !== "VERIFIED" ||
    location.verifiedAt === null ||
    !isReadablePdfAccessStatus(location.accessStatus) ||
    location.pdfUrl === null ||
    secureReaderUrl(location.pdfUrl) === null
  ) {
    return null;
  }
  return location as VerifiedPdfLocation;
}

export function selectReaderSource(
  access: PaperAccessResponse,
  expectedPaperId: string,
  locationId: string,
  now: Date,
): ReaderSource | null {
  const location = selectVerifiedPdfLocation(
    access,
    expectedPaperId,
    locationId,
  );
  const freshUntil = Date.parse(access.freshUntil ?? "");
  if (
    location === null ||
    access.cacheDisposition === "STALE_FALLBACK" ||
    !Number.isFinite(freshUntil) ||
    freshUntil <= now.getTime()
  ) {
    return null;
  }

  const pdfUrl = secureReaderUrl(location.pdfUrl);
  if (pdfUrl === null) {
    return null;
  }

  return {
    paperId: expectedPaperId,
    locationId,
    pdfUrl: pdfUrl.toString(),
    landingPageUrl: safeLandingPageUrl(location.landingPageUrl),
    hostDomain: pdfUrl.hostname,
    source: location.source,
    versionType: location.versionType,
    license: location.license,
    verifiedAt: location.verifiedAt,
  };
}

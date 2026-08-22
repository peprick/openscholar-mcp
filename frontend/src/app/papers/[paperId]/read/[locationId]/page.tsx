import type { Metadata, Route } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { z } from "zod";

import { PdfReader } from "@/features/reader/pdf-reader";
import {
  selectReaderSource,
  selectVerifiedPdfLocation,
} from "@/features/reader/reader-source";
import {
  BackendApiError,
  getPaperAccess,
  getPaperDetails,
} from "@/shared/api/server";
import { ExternalLink } from "@/shared/ui/external-link";

export const metadata: Metadata = {
  title: "Read verified paper",
  robots: { follow: false, index: false },
};

type ReaderPageProps = {
  params: Promise<{ paperId: string; locationId: string }>;
};

export default async function ReaderPage({
  params,
}: ReaderPageProps): Promise<React.JSX.Element> {
  const { locationId, paperId } = await params;
  const uuid = z.string().uuid();
  if (!uuid.safeParse(paperId).success || !uuid.safeParse(locationId).success) {
    notFound();
  }

  let paper;
  let access;
  try {
    [paper, access] = await Promise.all([
      getPaperDetails(paperId),
      getPaperAccess(paperId),
    ]);
  } catch (error) {
    if (error instanceof BackendApiError && error.status === 404) {
      notFound();
    }
    throw error;
  }

  const verifiedLocation = selectVerifiedPdfLocation(
    access,
    paperId,
    locationId,
  );
  if (verifiedLocation === null) {
    notFound();
  }

  const source = selectReaderSource(
    access,
    paperId,
    locationId,
    new Date(),
  );
  const paperRoute = `/papers/${paperId}` as Route;

  return (
    <main className="shell page readerPage" id="main-content">
      <div className="pageToolbar">
        <Link className="backLink" href={paperRoute}>
          <span aria-hidden="true">←</span> Paper details
        </Link>
      </div>

      {source === null ? (
        <section className="readerUnavailable" aria-labelledby="reader-unavailable-heading">
          <span className="eyebrow">Access check needed</span>
          <h1 id="reader-unavailable-heading">This reader link has expired.</h1>
          <p>
            OpenScholar will not automatically load a stale document URL. Return
            to the paper to check access again, or open the last verified source
            directly.
          </p>
          <div className="buttonGroup">
            <Link className="button button--primary" href={paperRoute}>
              Check access on paper page
            </Link>
            <ExternalLink
              className="button button--ghost"
              href={verifiedLocation.pdfUrl}
            >
              Open PDF in a new tab
            </ExternalLink>
          </div>
        </section>
      ) : (
        <PdfReader source={source} title={paper.title} />
      )}
    </main>
  );
}

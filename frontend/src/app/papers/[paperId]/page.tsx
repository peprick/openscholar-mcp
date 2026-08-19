import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { z } from "zod";

import { AccessPanel } from "@/features/access/access-panel";
import { CitationActions } from "@/features/citations/citation-actions";
import { SavePaperPanel } from "@/features/library/save-paper-panel";
import { PaperDetails } from "@/features/papers/paper-details";
import {
  BackendApiError,
  getPaperAccess,
  getPaperDetails,
  getRelatedPapers,
} from "@/shared/api/server";
import type { RelatedPapersResponse } from "@/shared/api/schemas";

export const metadata: Metadata = {
  title: "Paper details",
};

type PaperPageProps = {
  params: Promise<{ paperId: string }>;
};

export default async function PaperPage({
  params,
}: PaperPageProps): Promise<React.JSX.Element> {
  const { paperId } = await params;
  if (!z.string().uuid().safeParse(paperId).success) {
    notFound();
  }

  let paper;
  let access;
  let related;
  let relatedUnavailable;
  try {
    const relatedRequest = getRelatedPapers(paperId, 5).then(
      (response) => ({ response, unavailable: false }),
      () => ({
        response: {
          sourcePaperId: paperId,
          results: [],
        } satisfies RelatedPapersResponse,
        unavailable: true,
      }),
    );
    const [paperResponse, accessResponse, relatedState] = await Promise.all([
      getPaperDetails(paperId),
      getPaperAccess(paperId),
      relatedRequest,
    ]);
    paper = paperResponse;
    access = accessResponse;
    related = relatedState.response;
    relatedUnavailable = relatedState.unavailable;
  } catch (error) {
    if (error instanceof BackendApiError && error.status === 404) {
      notFound();
    }
    throw error;
  }

  return (
    <main className="shell page" id="main-content">
      <div className="pageToolbar">
        <Link className="backLink" href="/">
          <span aria-hidden="true">←</span> Search research
        </Link>
        <code className="recordId">Paper {paper.paperId.slice(0, 8)}</code>
      </div>
      <PaperDetails
        paper={paper}
        related={related}
        relatedUnavailable={relatedUnavailable}
      />
      <SavePaperPanel paperId={paperId} />
      <AccessPanel
        initialAccess={access}
        initialNow={new Date().toISOString()}
        paperId={paperId}
      />
      <CitationActions paperId={paperId} />
    </main>
  );
}

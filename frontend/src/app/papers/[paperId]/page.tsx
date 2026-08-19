import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { z } from "zod";

import { AccessPanel } from "@/features/access/access-panel";
import { CitationActions } from "@/features/citations/citation-actions";
import { PaperDetails } from "@/features/papers/paper-details";
import {
  BackendApiError,
  getPaperAccess,
  getPaperDetails,
} from "@/shared/api/server";

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

  return (
    <main className="shell page" id="main-content">
      <div className="pageToolbar">
        <Link className="backLink" href="/">
          <span aria-hidden="true">←</span> Search research
        </Link>
        <code className="recordId">Paper {paper.paperId.slice(0, 8)}</code>
      </div>
      <PaperDetails paper={paper} />
      <AccessPanel
        initialAccess={access}
        initialNow={new Date().toISOString()}
        paperId={paperId}
      />
      <CitationActions paperId={paperId} />
    </main>
  );
}

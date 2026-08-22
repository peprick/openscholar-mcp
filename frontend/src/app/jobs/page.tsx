import type { Metadata, Route } from "next";
import Link from "next/link";
import { z } from "zod";

import { RefreshJobDashboard } from "@/features/jobs/refresh-job-dashboard";
import { getResearchRefreshJobs } from "@/shared/api/server";

export const metadata: Metadata = {
  title: "Refresh jobs",
};

type RefreshJobsPageProps = {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
};

function first(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}

export default async function RefreshJobsPage({
  searchParams,
}: RefreshJobsPageProps): Promise<React.JSX.Element> {
  const parameters = await searchParams;
  const requestedPage = z.coerce.number().int().nonnegative().safeParse(
    first(parameters.page) ?? 0,
  );
  const page = requestedPage.success ? requestedPage.data : 0;
  const jobs = await getResearchRefreshJobs(page, 20);

  return (
    <main className="shell page jobsPage" id="main-content">
      {!requestedPage.success ? (
        <div className="warningPanel" role="alert">
          The requested jobs page was invalid, so the first page is shown.
        </div>
      ) : null}
      <section className="jobsIntro" aria-labelledby="jobs-heading">
        <span className="eyebrow">Durable background work</span>
        <h1 id="jobs-heading">Refresh jobs</h1>
        <p>
          Monitor metadata and legal-access refreshes. Failed work can be retried
          explicitly; active work is deduplicated by target.
        </p>
      </section>
      <RefreshJobDashboard initialPage={jobs} />
      {jobs.totalPages > 1 ? (
        <nav aria-label="Refresh job pages" className="pagination jobsPagination">
          {jobs.page > 0 ? (
            <Link href={`/jobs?page=${jobs.page - 1}` as Route}>Previous</Link>
          ) : (
            <span aria-disabled="true">Previous</span>
          )}
          <span>
            Page {jobs.page + 1} of {jobs.totalPages}
          </span>
          {jobs.page + 1 < jobs.totalPages ? (
            <Link href={`/jobs?page=${jobs.page + 1}` as Route}>Next</Link>
          ) : (
            <span aria-disabled="true">Next</span>
          )}
        </nav>
      ) : null}
    </main>
  );
}

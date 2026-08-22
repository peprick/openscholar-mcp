"use client";

import { useState } from "react";

import { responseErrorMessage } from "@/features/library/library-client";
import {
  researchRefreshJobSchema,
  type ResearchRefreshJob,
  type ResearchRefreshJobPage,
} from "@/shared/api/jobs-schemas";
import { humanizeEnum } from "@/shared/formatting/display";
import { Badge } from "@/shared/ui/badge";

type RefreshJobDashboardProps = {
  initialPage: ResearchRefreshJobPage;
};

function timestamp(value: string | null): string {
  if (value === null) return "Not yet";
  return new Intl.DateTimeFormat("en", {
    dateStyle: "medium",
    timeStyle: "short",
    timeZone: "UTC",
  }).format(new Date(value));
}

function jobDescription(job: ResearchRefreshJob): string {
  return job.jobType === "SEARCH_METADATA"
    ? "Search metadata"
    : "Legal-access evidence";
}

export function RefreshJobDashboard({
  initialPage,
}: RefreshJobDashboardProps): React.JSX.Element {
  const [jobs, setJobs] = useState(initialPage.items);
  const [retrying, setRetrying] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  async function retry(jobId: string): Promise<void> {
    setRetrying(jobId);
    setMessage(null);
    try {
      const response = await fetch(`/api/refresh-jobs/${jobId}/retry`, {
        method: "POST",
      });
      if (!response.ok) {
        setMessage(
          await responseErrorMessage(response, "The refresh job could not be retried."),
        );
        return;
      }
      const parsed = researchRefreshJobSchema.safeParse(await response.json());
      if (!parsed.success) {
        setMessage("The server returned an unexpected refresh-job response.");
        return;
      }
      setJobs((current) =>
        current.map((job) => (job.id === parsed.data.id ? parsed.data : job)),
      );
      setMessage("The failed refresh was queued again.");
    } catch {
      setMessage("OpenScholar could not reach the refresh service.");
    } finally {
      setRetrying(null);
    }
  }

  return (
    <section className="jobsDashboard" aria-labelledby="job-list-heading">
      <div className="librarySectionHeader">
        <div>
          <span className="eyebrow">Latest activity</span>
          <h2 id="job-list-heading">Job history</h2>
        </div>
        <Badge>{initialPage.totalElements} total</Badge>
      </div>
      <p aria-live="polite" className="jobsMessage" role="status">
        {message}
      </p>
      {jobs.length === 0 ? (
        <div className="emptyState">
          <h3>No refresh jobs yet</h3>
          <p>
            Jobs appear here after a manual refresh is queued or scheduled stale
            targets are enabled.
          </p>
        </div>
      ) : (
        <div className="jobsTableFrame">
          <table className="jobsTable">
            <caption className="srOnly">
              Durable metadata and legal-access refresh jobs
            </caption>
            <thead>
              <tr>
                <th scope="col">Work</th>
                <th scope="col">Status</th>
                <th scope="col">Attempts</th>
                <th scope="col">Updated (UTC)</th>
                <th scope="col">Outcome</th>
                <th scope="col"><span className="srOnly">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              {jobs.map((job) => (
                <tr key={job.id}>
                  <td>
                    <strong>{jobDescription(job)}</strong>
                    <span className="jobTarget">Target {job.targetId}</span>
                  </td>
                  <td>
                    <span className={`jobStatus jobStatus--${job.status.toLowerCase()}`}>
                      {humanizeEnum(job.status)}
                    </span>
                    <span className="jobTrigger">{humanizeEnum(job.trigger)}</span>
                  </td>
                  <td>
                    {job.attemptCount} / {job.maxAttempts}
                  </td>
                  <td>{timestamp(job.updatedAt)}</td>
                  <td>
                    {job.lastErrorDetail === null ? (
                      <span className="jobOutcomeMuted">
                        {job.completedAt === null
                          ? `Available ${timestamp(job.availableAt)}`
                          : `Completed ${timestamp(job.completedAt)}`}
                      </span>
                    ) : (
                      <span className="jobError">
                        <strong>{job.lastErrorCode}</strong>
                        {job.lastErrorDetail}
                      </span>
                    )}
                  </td>
                  <td>
                    {job.status === "FAILED" ? (
                      <button
                        className="button button--secondary jobsRetry"
                        disabled={retrying === job.id}
                        onClick={() => void retry(job.id)}
                        type="button"
                      >
                        {retrying === job.id ? "Queuing…" : "Retry"}
                      </button>
                    ) : null}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

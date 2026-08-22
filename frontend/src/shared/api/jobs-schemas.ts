import { z } from "zod";

export const researchRefreshJobTypeSchema = z.enum([
  "PAPER_ACCESS",
  "SEARCH_METADATA",
]);

export const researchRefreshJobStatusSchema = z.enum([
  "QUEUED",
  "RUNNING",
  "SUCCEEDED",
  "FAILED",
]);

const nullableInstantSchema = z.string().datetime({ offset: true }).nullable();
const instantSchema = z.string().datetime({ offset: true });

export const researchRefreshJobSchema = z
  .object({
    id: z.string().uuid(),
    jobType: researchRefreshJobTypeSchema,
    targetId: z.string().uuid(),
    trigger: z.enum(["MANUAL", "SCHEDULED", "RETRY"]),
    status: researchRefreshJobStatusSchema,
    attemptCount: z.number().int().nonnegative(),
    maxAttempts: z.number().int().min(1).max(10),
    availableAt: instantSchema,
    leasedUntil: nullableInstantSchema,
    lastErrorCode: z.string().nullable(),
    lastErrorDetail: z.string().nullable(),
    createdAt: instantSchema,
    startedAt: nullableInstantSchema,
    completedAt: nullableInstantSchema,
    updatedAt: instantSchema,
  })
  .strict()
  .superRefine((job, context) => {
    if ((job.lastErrorCode === null) !== (job.lastErrorDetail === null)) {
      context.addIssue({
        code: "custom",
        message: "Refresh job error code and detail must be present together.",
        path: ["lastErrorDetail"],
      });
    }
    if (job.attemptCount > job.maxAttempts) {
      context.addIssue({
        code: "custom",
        message: "Refresh job attempt count exceeds its maximum.",
        path: ["attemptCount"],
      });
    }
  });

export const researchRefreshJobPageSchema = z
  .object({
    items: z.array(researchRefreshJobSchema),
    page: z.number().int().nonnegative(),
    size: z.number().int().min(1).max(100),
    totalElements: z.number().int().nonnegative(),
    totalPages: z.number().int().nonnegative(),
  })
  .strict();

export type ResearchRefreshJob = z.infer<typeof researchRefreshJobSchema>;
export type ResearchRefreshJobPage = z.infer<
  typeof researchRefreshJobPageSchema
>;

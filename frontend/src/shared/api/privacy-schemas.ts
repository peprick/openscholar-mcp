import { z } from "zod";

export const DELETE_PERSONAL_DATA_CONFIRMATION = "DELETE_MY_DATA" as const;

export const deletePersonalDataRequestSchema = z
  .object({
    confirmation: z.literal(DELETE_PERSONAL_DATA_CONFIRMATION),
  })
  .strict();

export type DeletePersonalDataRequest = z.infer<
  typeof deletePersonalDataRequestSchema
>;

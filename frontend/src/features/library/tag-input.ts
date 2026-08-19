import { tagsSchema } from "@/shared/api/library-schemas";

export function parseTagInput(value: string): ReturnType<typeof tagsSchema.safeParse> {
  const tags = value === "" ? [] : value.split(",");
  return tagsSchema.safeParse(tags);
}

export function formatTagInput(tags: string[]): string {
  return tags.join(", ");
}

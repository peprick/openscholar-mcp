import type { SearchResponse } from "@/shared/api/schemas";
import { humanizeEnum } from "@/shared/formatting/display";
import { Badge } from "@/shared/ui/badge";

type CacheDisposition = SearchResponse["cacheDisposition"];

const cacheDescriptions: Record<CacheDisposition, string> = {
  EXACT_HIT: "Loaded from a fresh saved snapshot.",
  MISS_FETCHED: "Fetched from configured scholarly providers and saved.",
  STALE_REFRESHED: "An older snapshot was refreshed from providers.",
  FORCED_REFRESH: "Provider results were explicitly refreshed.",
  STALE_FALLBACK: "Providers failed, so an older saved snapshot is shown.",
};

export function CacheBadge({
  disposition,
}: {
  disposition: CacheDisposition;
}): React.JSX.Element {
  return (
    <span title={cacheDescriptions[disposition]}>
      <Badge tone={disposition === "STALE_FALLBACK" ? "warning" : "info"}>
        {humanizeEnum(disposition)}
      </Badge>
    </span>
  );
}

import type { Metadata } from "next";
import Link from "next/link";

import { BackendStatus } from "@/features/search/backend-status";
import { SearchForm } from "@/features/search/search-form";

export const metadata: Metadata = {
  title: "Discover open research",
};

const exampleTopics = [
  "large language models in education",
  "climate resilient agriculture",
  "multi-agent reinforcement learning",
];

type HomePageProps = {
  searchParams: Promise<{ q?: string | string[] }>;
};

export default async function HomePage({
  searchParams,
}: HomePageProps): Promise<React.JSX.Element> {
  const query = (await searchParams).q;
  const initialQuery = typeof query === "string" ? query : "";

  return (
    <main id="main-content">
      <section className="hero">
        <div className="shell heroInner">
          <div className="heroStatus">
            <span className="eyebrow">Open research workspace</span>
            <BackendStatus />
          </div>
          <h1>
            Find the paper.
            <br />
            <em>Trace the evidence.</em>
          </h1>
          <p className="heroLead">
            Search scholarly indexes, revisit cached results, and open only legal
            versions that OpenScholar has independently checked.
          </p>
          <SearchForm initialQuery={initialQuery} />
          <div className="topicSuggestions" aria-label="Example research topics">
            <span>Try a topic</span>
            {exampleTopics.map((topic) => (
              <Link href={`/?q=${encodeURIComponent(topic)}`} key={topic}>
                {topic}
              </Link>
            ))}
          </div>
        </div>
      </section>

      <section className="shell trustSection" aria-labelledby="trust-heading">
        <div className="sectionIntro">
          <span className="eyebrow">A research trail you can inspect</span>
          <h2 id="trust-heading">Useful results without hiding their origin.</h2>
        </div>
        <div className="valueGrid">
          <article>
            <span className="valueNumber">01</span>
            <h3>Canonical records</h3>
            <p>
              Duplicate provider records become one stable paper with ordered
              credited authors and identifiers.
            </p>
          </article>
          <article>
            <span className="valueNumber">02</span>
            <h3>Visible provenance</h3>
            <p>
              Every detail page exposes its source records, retrieval dates, and
              canonical authorship source.
            </p>
          </article>
          <article>
            <span className="valueNumber">03</span>
            <h3>Verified access</h3>
            <p>
              Provider-reported claims stay distinct from links independently
              checked through Unpaywall or arXiv.
            </p>
          </article>
        </div>
      </section>
    </main>
  );
}

import type { Metadata } from "next";
import Link from "next/link";

import { BackendStatus } from "@/features/search/backend-status";
import { IdentifierLookup } from "@/features/search/identifier-lookup";
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
            Search research papers, save what matters, and find free full-text
            versions from trusted scholarly sources.
          </p>
          <SearchForm initialQuery={initialQuery} />
          <IdentifierLookup />
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
          <span className="eyebrow">Research you can trust</span>
          <h2 id="trust-heading">Useful results with their sources in view.</h2>
        </div>
        <div className="valueGrid">
          <article>
            <span className="valueNumber">01</span>
            <h3>Clear paper records</h3>
            <p>
              Duplicate listings are combined so each paper is easier to understand
              and save.
            </p>
          </article>
          <article>
            <span className="valueNumber">02</span>
            <h3>Sources you can see</h3>
            <p>
              Paper pages show which research databases supplied the information.
            </p>
          </article>
          <article>
            <span className="valueNumber">03</span>
            <h3>Links checked before opening</h3>
            <p>
              OpenScholar checks free links from sources such as Unpaywall and arXiv
              before showing them.
            </p>
          </article>
        </div>
      </section>
    </main>
  );
}

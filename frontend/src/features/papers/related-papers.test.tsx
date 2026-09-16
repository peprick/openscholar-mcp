import { cleanup, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";

import { RelatedPapers } from "@/features/papers/related-papers";
import { relatedPapersResponseFixture, testIds } from "@/test/fixtures";

afterEach(cleanup);

describe("RelatedPapers", () => {
  it("renders ranked local matches as accessible paper links", () => {
    render(<RelatedPapers related={relatedPapersResponseFixture()} />);

    const section = screen.getByRole("region", { name: "Related papers" });
    expect(
      within(section).getByText(
        "Suggestions based on similarities in title, abstract, and publication venue.",
      ),
    ).toBeVisible();
    expect(
      within(section).getByRole("link", {
        name: "Message passing networks for molecular discovery",
      }),
    ).toHaveAttribute("href", `/papers/${testIds.relatedPaper}`);
    expect(within(section).getByText("Grace Scholar")).toBeVisible();
    expect(within(section).getByText("18 citations")).toBeVisible();
    expect(within(section).getByText("Similar title or abstract")).toBeVisible();
    expect(within(section).queryByText(/score/i)).not.toBeInTheDocument();
    expect(within(section).queryByText("Postgres Full Text")).not.toBeInTheDocument();
  });

  it("explains when no related papers have been found", () => {
    render(
      <RelatedPapers
        related={relatedPapersResponseFixture({ results: [] })}
      />,
    );

    const section = screen.getByRole("region", { name: "Related papers" });
    expect(
      within(section).getByText(
        "OpenScholar has not found related papers yet.",
      ),
    ).toBeVisible();
    expect(within(section).queryByRole("list")).not.toBeInTheDocument();
  });

  it("keeps canonical metadata useful when related discovery is unavailable", () => {
    render(
      <RelatedPapers
        related={relatedPapersResponseFixture({ results: [] })}
        unavailable
      />,
    );

    const section = screen.getByRole("region", { name: "Related papers" });
    expect(within(section).getByRole("status")).toHaveTextContent(
      "Related papers are temporarily unavailable.",
    );
    expect(
      within(section).queryByText(
        "OpenScholar has not found related papers yet.",
      ),
    ).not.toBeInTheDocument();
  });

  it.each([
    [1, "1 citation"],
    [null, "Citation count unavailable"],
  ])("formats a %s citation count for readers", (citationCount, label) => {
    const related = relatedPapersResponseFixture();
    related.results[0]!.citationCount = citationCount;
    render(<RelatedPapers related={related} />);
    expect(screen.getByText(label)).toBeVisible();
    expect(screen.queryByText("Not available citations")).not.toBeInTheDocument();
  });
});

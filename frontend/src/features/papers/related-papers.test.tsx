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
        "Ranked from title, abstract, and venue matches among papers already stored in OpenScholar.",
      ),
    ).toBeVisible();
    expect(within(section).getByLabelText("Rank 1")).toHaveTextContent("01");
    expect(
      within(section).getByRole("link", {
        name: "Message passing networks for molecular discovery",
      }),
    ).toHaveAttribute("href", `/papers/${testIds.relatedPaper}`);
    expect(within(section).getByText("Grace Scholar")).toBeVisible();
    expect(within(section).getByText("18 citations")).toBeVisible();
    expect(
      within(section).getByText("Postgres Full Text · score 0.420"),
    ).toBeVisible();
  });

  it("explains when the local catalog has no related matches", () => {
    render(
      <RelatedPapers
        related={relatedPapersResponseFixture({ results: [] })}
      />,
    );

    const section = screen.getByRole("region", { name: "Related papers" });
    expect(
      within(section).getByText(
        "No related papers are available in the local catalog yet.",
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
        "No related papers are available in the local catalog yet.",
      ),
    ).not.toBeInTheDocument();
  });
});

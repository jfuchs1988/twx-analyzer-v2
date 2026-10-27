import { describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import FindingStatusModal from "./FindingStatusModal";
import { api, type Finding } from "../api";
import { renderWithProviders } from "../test/test-utils";

vi.mock("../api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api")>();
  return { ...actual, api: { ...actual.api, setFindingStatus: vi.fn() } };
});

describe("FindingStatusModal", () => {
  it("accepts a finding with a comment and reports the saved count", async () => {
    const onSaved = vi.fn();
    const finding: Finding = {
      id: 42,
      ruleId: "R-JS-001",
      severity: "MAJOR",
      needsReview: false,
      objectName: "Process A",
      message: "Synthetic finding",
      status: "OPEN",
    };
    vi.mocked(api.setFindingStatus).mockResolvedValue({ ...finding, status: "ACCEPTED" });
    const user = userEvent.setup();
    renderWithProviders(<FindingStatusModal finding={finding} onClose={vi.fn()} onSaved={onSaved} />);

    await user.click(screen.getByRole("radio", { name: "accepted" }));
    expect(screen.getByText(/Accepted findings no longer count/)).toBeInTheDocument();
    await user.type(screen.getByLabelText("Comment (optional)"), "Approved for release");
    await user.click(screen.getByRole("button", { name: "Save" }));

    expect(api.setFindingStatus).toHaveBeenCalledWith(42, "ACCEPTED", "Approved for release");
    expect(onSaved).toHaveBeenCalledWith(1);
  });
});

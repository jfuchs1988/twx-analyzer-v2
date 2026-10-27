import { describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import SnapshotsPage from "./SnapshotsPage";
import { api } from "@/shared/api";
import { renderWithProviders } from "@/test/test-utils";

vi.mock("@/shared/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/shared/api")>();
  return { ...actual, api: { ...actual.api, upload: vi.fn() } };
});

vi.mock("@/features/import/BpmImportModal", () => ({ default: () => null }));
vi.mock("@/shared/components/SnapshotList", () => ({ default: () => <div /> }));

describe("SnapshotsPage upload", () => {
  it("queues an accepted TWX file and shows the queued notice", async () => {
    vi.mocked(api.upload).mockResolvedValue({
      ok: true,
      status: 202,
      json: async () => ({ taskId: 7 }),
    });
    const user = userEvent.setup();
    const { container } = renderWithProviders(<SnapshotsPage onOpen={vi.fn()} />);
    const input = container.querySelector<HTMLInputElement>('input[type="file"]');
    expect(input).not.toBeNull();
    const file = new File(["synthetic TWX fixture"], "sample.twx", { type: "application/zip" });

    await user.upload(input!, file);

    expect(api.upload).toHaveBeenCalledWith(file);
    expect(await screen.findByText(/1 file queued/)).toBeInTheDocument();
  });
});

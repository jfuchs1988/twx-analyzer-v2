import { beforeEach, describe, expect, it, vi } from "vitest";
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import SettingsPage from "./SettingsPage";
import { api } from "@/shared/api";
import { renderWithProviders } from "@/test/test-utils";

vi.mock("@/shared/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/shared/api")>();
  return {
    ...actual,
    api: {
      ...actual.api,
      bpmSettings: vi.fn(),
      bpmSaveSettings: vi.fn(),
    },
  };
});

describe("SettingsPage", () => {
  beforeEach(() => {
    vi.mocked(api.bpmSettings).mockResolvedValue({
      baseUrl: "https://bpm.example.test/BPM",
      username: "service-user",
      passwordSet: false,
      caFile: "",
    });
    vi.mocked(api.bpmSaveSettings).mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({
        baseUrl: "https://bpm.example.test/BPM",
        username: "service-user",
        passwordSet: true,
        caFile: "",
      }),
    } as Response);
  });

  it("saves BPM settings and clears the password field after success", async () => {
    const user = userEvent.setup();
    renderWithProviders(<SettingsPage />);

    const password = await screen.findByLabelText("Password");
    await user.clear(screen.getByLabelText("Base URL"));
    await user.type(screen.getByLabelText("Base URL"), "https://bpm.example.test/BPM");
    await user.clear(screen.getByLabelText("Username"));
    await user.type(screen.getByLabelText("Username"), "service-user");
    await user.type(password, "secret-value");
    await user.click(screen.getByRole("button", { name: /^Save$/ }));

    expect(api.bpmSaveSettings).toHaveBeenCalledWith({
      baseUrl: "https://bpm.example.test/BPM",
      username: "service-user",
      password: "secret-value",
      caFile: "",
    });
    expect(await screen.findByText("Saved.")).toBeInTheDocument();
    expect(password).toHaveValue("");
  });
});

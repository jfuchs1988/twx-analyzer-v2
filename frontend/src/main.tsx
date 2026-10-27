import React from "react";
import ReactDOM from "react-dom/client";
import { DesignProvider } from "@/shared/design";
import { LangProvider } from "@/shared/i18n";
import "@mantine/core/styles.css";
import "@mantine/dropzone/styles.css";
import App from "@/app/App";

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <DesignProvider>
      <LangProvider>
        <App />
      </LangProvider>
    </DesignProvider>
  </React.StrictMode>
);

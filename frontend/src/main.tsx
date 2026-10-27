import React from "react";
import ReactDOM from "react-dom/client";
import { DesignProvider } from "./design";
import { LangProvider } from "./i18n";
import "@mantine/core/styles.css";
import "@mantine/dropzone/styles.css";
import App from "./App";

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <DesignProvider>
      <LangProvider>
        <App />
      </LangProvider>
    </DesignProvider>
  </React.StrictMode>
);

# Frontend Third-Party Services

This document explains the rationale behind each of the major third-party libraries and tools used in the **DocuMind** frontend.

## Core Framework & Build Tools

*   **React (v19)**
    *   **Why it's used:** React is the foundational library for the frontend. It allows us to build complex, interactive user interfaces using reusable components, which is essential for a dynamic chat and document viewing application.
*   **Vite**
    *   **Why it's used:** Used as the build tool and development server. It provides incredibly fast hot-module replacement (HMR) during development and highly optimized static assets for production, significantly outperforming older tools like Create React App.
*   **TailwindCSS**
    *   **Why it's used:** A utility-first CSS framework that allows developers to style components directly within the JSX files. It speeds up the styling process, ensures a consistent design system, and keeps the CSS bundle size small by purging unused styles.

## Networking & Routing

*   **Axios**
    *   **Why it's used:** A robust, promise-based HTTP client used to communicate with the Spring Boot backend. It simplifies making API requests, handling JSON data, and setting up interceptors for things like JWT authentication tokens.
*   **React Router**
    *   **Why it's used:** Handles client-side routing, allowing users to navigate between different views (like the chat interface and settings) without triggering a full page reload, resulting in a smoother, app-like experience.

## Document Viewing & Rendering

*   **React PDF (`react-pdf`)**
    *   **Why it's used:** This library is crucial for the "Source Document Viewer" feature. It renders PDF documents directly inside the browser using Mozilla's PDF.js under the hood. It allows us to draw custom highlight overlays (`boundingBoxes`) exactly where the LLM found its answers.
*   **Mermaid**
    *   **Why it's used:** Integrated to render dynamic flowcharts and diagrams directly in the chat interface when the AI generates them.
*   **KaTeX**
    *   **Why it's used:** Used to render complex mathematical formulas and equations quickly and beautifully within the chat responses.

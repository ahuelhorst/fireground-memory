import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';

/** Entry point: mounts the app into #root. */
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);

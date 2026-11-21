import React from 'react';
import { createRoot } from 'react-dom/client';
import { RouterProvider, createBrowserRouter } from 'react-router-dom';
import App from './App.jsx';
import { AuthProvider } from './auth.jsx';
import { ThemeProvider } from './theme.jsx';
import './styles.css';

// A data router is needed for useBlocker (unsaved-changes guard). One catch-all route renders the app,
// which keeps its own <Routes> in App.jsx. ThemeProvider sits inside AuthProvider because it reads the user.
const router = createBrowserRouter([
  {
    path: '*',
    element: (
      <AuthProvider>
        <ThemeProvider>
          <App />
        </ThemeProvider>
      </AuthProvider>
    ),
  },
]);

createRoot(document.getElementById('root')).render(<RouterProvider router={router} />);

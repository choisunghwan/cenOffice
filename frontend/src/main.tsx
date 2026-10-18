import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import './hub/skill-registration.css'
import './workspace/appearance.css'
import App from './App'
import { useOffice } from './store'

if (import.meta.env.DEV) (window as unknown as { __office: typeof useOffice }).__office = useOffice

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)


import { createRoot } from 'react-dom/client'
import './index.css'
import App from './App'
import { AuthProvider } from './auth/AuthProvider'

createRoot(document.getElementById('app')!).render(
  <AuthProvider>
    <App />
  </AuthProvider>,
)

import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './app/App'
import './styles/tokens.css'
import './styles/global.css'
import './styles/shell.css'
import './styles/states.css'
import './styles/business-data.css'
import './styles/chat.css'
import './styles/image-creation.css'
import './styles/video-creation.css'
import './styles/assets.css'
import './styles/articles.css'
import './styles/points-billing.css'

const rootElement = document.getElementById('root')

if (!rootElement) {
  throw new Error('Application root element was not found.')
}

createRoot(rootElement).render(
  <StrictMode>
    <App />
  </StrictMode>,
)

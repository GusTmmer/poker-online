import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { HomePage } from './pages/HomePage'
import { TablePage } from './pages/TablePage'
import { ErrorBoundary } from './components/ErrorBoundary'

export function App() {
  return (
    <ErrorBoundary>
      <BrowserRouter>
        <Routes>
          <Route path="/" element={<HomePage />} />
          <Route path="/table/:tableId" element={<TablePage />} />
        </Routes>
      </BrowserRouter>
    </ErrorBoundary>
  )
}

export default App

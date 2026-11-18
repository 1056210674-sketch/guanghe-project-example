import { QueryClientProvider } from '@tanstack/react-query'
import { App as AntApp, ConfigProvider } from 'antd'
import { useState } from 'react'
import { BrowserRouter } from 'react-router-dom'
import { AppRoutes } from '../routes/AppRoutes'
import { createQueryClient } from './queryClient'
import { appTheme } from './theme'

export function App() {
  const [queryClient] = useState(createQueryClient)

  return (
    <ConfigProvider theme={appTheme}>
      <AntApp>
        <QueryClientProvider client={queryClient}>
          <BrowserRouter>
            <AppRoutes />
          </BrowserRouter>
        </QueryClientProvider>
      </AntApp>
    </ConfigProvider>
  )
}

import { QueryClientProvider } from '@tanstack/react-query'
import { App as AntdApp, ConfigProvider } from 'antd'
import type { ReactElement } from 'react'
import { MemoryRouter } from 'react-router-dom'
import { render } from '@testing-library/react'
import { createQueryClient } from '../app/queryClient'
import { appTheme } from '../app/theme'
import { AppRoutes } from '../routes/AppRoutes'

export function renderApp(initialEntries: string[] = ['/login'], routeObserver?: ReactElement) {
  const queryClient = createQueryClient()

  return {
    queryClient,
    ...render(
      <ConfigProvider theme={appTheme}>
        <AntdApp>
          <QueryClientProvider client={queryClient}>
            <MemoryRouter initialEntries={initialEntries}>
              <AppRoutes />
              {routeObserver}
            </MemoryRouter>
          </QueryClientProvider>
        </AntdApp>
      </ConfigProvider>,
    ),
  }
}

export function renderWithRouter(
  element: ReactElement,
  initialEntries: string[] = ['/'],
) {
  return render(
    <MemoryRouter initialEntries={initialEntries}>{element}</MemoryRouter>,
  )
}

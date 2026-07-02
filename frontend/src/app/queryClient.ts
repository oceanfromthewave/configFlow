import { QueryClient } from '@tanstack/react-query'

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        // Local backend: failures are (almost always) "backend not running",
        // retrying rarely helps and delays the error state.
        retry: 1,
        refetchOnWindowFocus: false,
        staleTime: 5_000,
      },
    },
  })
}

let csrfBootstrap: Promise<void> | null = null

function readCookie(name: string): string | undefined {
  const prefix = `${name}=`
  for (const part of document.cookie.split(';')) {
    const value = part.trim()
    if (value.startsWith(prefix)) {
      return decodeURIComponent(value.slice(prefix.length))
    }
  }
  return undefined
}

async function ensureCsrf(): Promise<void> {
  if (!csrfBootstrap) {
    csrfBootstrap = fetch('/api/auth/csrf', {
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    }).then((response) => {
      if (!response.ok) {
        throw new Error(`CSRF bootstrap failed: HTTP ${response.status}`)
      }
    }).catch((error) => {
      csrfBootstrap = null
      throw error
    })
  }
  await csrfBootstrap
}

export async function apiFetch(
  input: RequestInfo | URL,
  init: RequestInit = {}
): Promise<Response> {
  const method = (init.method || 'GET').toUpperCase()
  const headers = new Headers(init.headers)
  const isMutation = !['GET', 'HEAD', 'OPTIONS'].includes(method)

  if (isMutation) {
    await ensureCsrf()
    const token = readCookie('XSRF-TOKEN')
    if (token) {
      headers.set('X-XSRF-TOKEN', token)
    }
  }

  const response = await fetch(input, {
    ...init,
    headers,
    credentials: 'same-origin',
  })

  if (response.status === 401 && !String(input).endsWith('/api/auth/me')) {
    window.dispatchEvent(new Event('auth:unauthorized'))
  }
  return response
}

export function resetCsrfBootstrap() {
  csrfBootstrap = null
}

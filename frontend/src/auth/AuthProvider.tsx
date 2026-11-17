import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { apiFetch, resetCsrfBootstrap } from '../lib/apiClient'
import type { ApiResult, AuthUser } from '../types'

interface AuthContextValue {
  user: AuthUser | null
  loading: boolean
  login: (username: string, password: string) => Promise<void>
  register: (username: string, password: string, displayName: string) => Promise<void>
  logout: () => Promise<void>
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null)
  const [loading, setLoading] = useState(true)

  const loadCurrentUser = useCallback(async () => {
    try {
      const response = await apiFetch('/api/auth/me', {
        headers: { Accept: 'application/json' },
      })
      if (!response.ok) {
        setUser(null)
        return
      }
      const body: ApiResult<AuthUser> = await response.json()
      setUser(body.code === 200 ? body.data : null)
    } catch {
      setUser(null)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void loadCurrentUser()
    const onUnauthorized = () => setUser(null)
    window.addEventListener('auth:unauthorized', onUnauthorized)
    return () => window.removeEventListener('auth:unauthorized', onUnauthorized)
  }, [loadCurrentUser])

  const login = useCallback(async (username: string, password: string) => {
    const response = await apiFetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    })
    const body: ApiResult<AuthUser> = await response.json()
    if (!response.ok || body.code !== 200) {
      throw new Error(body.message || '登录失败')
    }
    resetCsrfBootstrap()
    setUser(body.data)
  }, [])

  const register = useCallback(async (
    username: string,
    password: string,
    displayName: string
  ) => {
    const response = await apiFetch('/api/auth/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password, displayName }),
    })
    const body: ApiResult<AuthUser> = await response.json()
    if (!response.ok || body.code !== 200) {
      throw new Error(body.message || '注册失败')
    }
    resetCsrfBootstrap()
    setUser(body.data)
  }, [])

  const logout = useCallback(async () => {
    try {
      await apiFetch('/api/auth/logout', { method: 'POST' })
    } finally {
      resetCsrfBootstrap()
      setUser(null)
    }
  }, [])

  const value = useMemo(
    () => ({ user, loading, login, register, logout }),
    [user, loading, login, register, logout]
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const value = useContext(AuthContext)
  if (!value) {
    throw new Error('useAuth must be used inside AuthProvider')
  }
  return value
}

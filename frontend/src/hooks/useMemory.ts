import { useCallback, useEffect, useRef, useState } from 'react'
import { apiFetch } from '../lib/apiClient'
import type { ApiResult, PageResult, UserMemory } from '../types'

export function useMemory() {
  const [memories, setMemories] = useState<UserMemory[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const mountedRef = useRef(true)

  useEffect(() => {
    return () => {
      mountedRef.current = false
    }
  }, [])

  const load = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const response = await apiFetch('/api/memory')
      const body: ApiResult<PageResult<UserMemory>> = await response.json()
      if (!response.ok || body.code !== 200) {
        throw new Error(body.message || `HTTP ${response.status}`)
      }
      if (mountedRef.current) {
        setMemories(body.data.data)
      }
    } catch (err) {
      if (mountedRef.current) {
        setError(err instanceof Error ? err.message : '读取记忆失败')
      }
    } finally {
      if (mountedRef.current) {
        setLoading(false)
      }
    }
  }, [])

  const remove = useCallback(async (id: string) => {
    const response = await apiFetch(`/api/memory/${id}`, { method: 'DELETE' })
    if (!response.ok) {
      const body: ApiResult<unknown> = await response.json()
      throw new Error(body.message || `HTTP ${response.status}`)
    }
    setMemories((previous) => previous.filter((memory) => memory.id !== id))
  }, [])

  const clear = useCallback(async () => {
    const response = await apiFetch('/api/memory', { method: 'DELETE' })
    if (!response.ok) {
      const body: ApiResult<unknown> = await response.json()
      throw new Error(body.message || `HTTP ${response.status}`)
    }
    setMemories([])
  }, [])

  return { memories, loading, error, load, remove, clear }
}

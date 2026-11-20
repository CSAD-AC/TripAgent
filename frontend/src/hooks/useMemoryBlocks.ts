import { useCallback, useEffect, useRef, useState } from 'react'
import { apiFetch } from '../lib/apiClient'
import type {
  ApiResult,
  MemoryBlock,
  MemoryBlockRevision,
  MemoryBlockType,
  MemoryPrompt,
} from '../types'

async function readResult<T>(response: Response): Promise<T> {
  const body: ApiResult<T> = await response.json()
  if (!response.ok || body.code !== 200) {
    throw new Error(body.message || `HTTP ${response.status}`)
  }
  return body.data
}

export function useMemoryBlocks() {
  const [prompt, setPrompt] = useState<MemoryPrompt | null>(null)
  const [blocks, setBlocks] = useState<MemoryBlock[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const mountedRef = useRef(true)

  useEffect(() => {
    return () => {
      mountedRef.current = false
    }
  }, [])

  const reload = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const [promptResponse, blocksResponse] = await Promise.all([
        apiFetch('/api/memory/prompt'),
        apiFetch('/api/memory/blocks'),
      ])
      const [nextPrompt, nextBlocks] = await Promise.all([
        readResult<MemoryPrompt>(promptResponse),
        readResult<MemoryBlock[]>(blocksResponse),
      ])
      if (mountedRef.current) {
        setPrompt(nextPrompt)
        setBlocks(nextBlocks)
      }
    } catch (err) {
      if (mountedRef.current) {
        setError(err instanceof Error ? err.message : '读取长期记忆失败')
      }
    } finally {
      if (mountedRef.current) {
        setLoading(false)
      }
    }
  }, [])

  const savePrompt = useCallback(async (content: string) => {
    const response = await apiFetch('/api/memory/prompt', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content }),
    })
    const nextPrompt = await readResult<MemoryPrompt>(response)
    setPrompt(nextPrompt)
  }, [])

  const saveBlock = useCallback(async (type: MemoryBlockType, content: string) => {
    const response = await apiFetch(`/api/memory/blocks/${type}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ content }),
    })
    const nextBlock = await readResult<MemoryBlock>(response)
    setBlocks((current) => current.map((block) => block.type === type ? nextBlock : block))
  }, [])

  const promptRevisions = useCallback(async () => {
    const response = await apiFetch('/api/memory/prompt/revisions')
    return readResult<MemoryBlockRevision[]>(response)
  }, [])

  const blockRevisions = useCallback(async (type: MemoryBlockType) => {
    const response = await apiFetch(`/api/memory/blocks/${type}/revisions`)
    return readResult<MemoryBlockRevision[]>(response)
  }, [])

  const rollbackPrompt = useCallback(async (revisionId: string) => {
    const response = await apiFetch(`/api/memory/prompt/rollback/${revisionId}`, {
      method: 'POST',
    })
    const nextPrompt = await readResult<MemoryPrompt>(response)
    setPrompt(nextPrompt)
  }, [])

  const rollbackBlock = useCallback(async (type: MemoryBlockType, revisionId: string) => {
    const response = await apiFetch(`/api/memory/blocks/${type}/rollback/${revisionId}`, {
      method: 'POST',
    })
    const nextBlock = await readResult<MemoryBlock>(response)
    setBlocks((current) => current.map((block) => block.type === type ? nextBlock : block))
  }, [])

  return {
    prompt,
    blocks,
    loading,
    error,
    reload,
    savePrompt,
    saveBlock,
    promptRevisions,
    blockRevisions,
    rollbackPrompt,
    rollbackBlock,
  }
}

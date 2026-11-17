import { useState } from 'react'
import { Compass, LoaderCircle, LockKeyhole, UserRound } from 'lucide-react'
import { useAuth } from './AuthProvider'
import { cn } from '../lib/utils'

type AuthMode = 'login' | 'register'

export function LoginPage() {
  const { login, register } = useAuth()
  const [mode, setMode] = useState<AuthMode>('login')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    setSubmitting(true)
    setError('')
    try {
      if (mode === 'login') {
        await login(username, password)
      } else {
        await register(username, password, displayName)
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : '操作失败')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <main className="min-h-screen bg-warm-white dark:bg-[#1a1a1a] flex items-center justify-center p-5">
      <div className="w-full max-w-sm">
        <div className="flex items-center gap-3 mb-8">
          <div className="w-11 h-11 rounded-xl bg-terracotta-500 flex items-center justify-center shadow-sm">
            <Compass className="w-5 h-5 text-white" />
          </div>
          <div>
            <h1 className="font-display text-2xl font-semibold text-charcoal-900 dark:text-warm-white">
              TravelPal
            </h1>
            <p className="text-sm text-charcoal-400 dark:text-charcoal-500">
              你的旅途规划助手
            </p>
          </div>
        </div>

        <div className="border border-warm-white-200 dark:border-charcoal-700 bg-white dark:bg-charcoal-900 p-6 rounded-lg">
          <div className="grid grid-cols-2 gap-1 p-1 rounded-md bg-warm-white dark:bg-charcoal-800 mb-5">
            {(['login', 'register'] as const).map((item) => (
              <button
                key={item}
                type="button"
                onClick={() => {
                  setMode(item)
                  setError('')
                }}
                className={cn(
                  'h-9 rounded text-sm font-medium transition-colors',
                  mode === item
                    ? 'bg-white dark:bg-charcoal-700 text-terracotta-600 dark:text-terracotta-400 shadow-sm'
                    : 'text-charcoal-500 dark:text-charcoal-400'
                )}
              >
                {item === 'login' ? '登录' : '注册'}
              </button>
            ))}
          </div>

          <form className="space-y-4" onSubmit={submit}>
            {mode === 'register' && (
              <label className="block">
                <span className="text-xs font-medium text-charcoal-500 dark:text-charcoal-400">
                  显示名称
                </span>
                <div className="mt-1.5 flex items-center gap-2 h-10 px-3 rounded-md border border-warm-white-200 dark:border-charcoal-700 bg-white dark:bg-charcoal-800">
                  <UserRound className="w-4 h-4 text-charcoal-400" />
                  <input
                    value={displayName}
                    onChange={(event) => setDisplayName(event.target.value)}
                    maxLength={50}
                    required
                    className="flex-1 min-w-0 bg-transparent text-sm outline-none text-charcoal-900 dark:text-warm-white"
                    placeholder="你希望如何称呼"
                  />
                </div>
              </label>
            )}

            <label className="block">
              <span className="text-xs font-medium text-charcoal-500 dark:text-charcoal-400">
                用户名
              </span>
              <div className="mt-1.5 flex items-center gap-2 h-10 px-3 rounded-md border border-warm-white-200 dark:border-charcoal-700 bg-white dark:bg-charcoal-800">
                <UserRound className="w-4 h-4 text-charcoal-400" />
                <input
                  value={username}
                  onChange={(event) => setUsername(event.target.value)}
                  minLength={3}
                  maxLength={32}
                  autoComplete="username"
                  required
                  className="flex-1 min-w-0 bg-transparent text-sm outline-none text-charcoal-900 dark:text-warm-white"
                  placeholder="3-32 位字母、数字或下划线"
                />
              </div>
            </label>

            <label className="block">
              <span className="text-xs font-medium text-charcoal-500 dark:text-charcoal-400">
                密码
              </span>
              <div className="mt-1.5 flex items-center gap-2 h-10 px-3 rounded-md border border-warm-white-200 dark:border-charcoal-700 bg-white dark:bg-charcoal-800">
                <LockKeyhole className="w-4 h-4 text-charcoal-400" />
                <input
                  type="password"
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  minLength={mode === 'register' ? 8 : undefined}
                  maxLength={72}
                  autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
                  required
                  className="flex-1 min-w-0 bg-transparent text-sm outline-none text-charcoal-900 dark:text-warm-white"
                  placeholder={mode === 'register' ? '至少 8 位' : '输入密码'}
                />
              </div>
            </label>

            {error && (
              <p role="alert" className="text-sm text-red-600 dark:text-red-400">
                {error}
              </p>
            )}

            <button
              type="submit"
              disabled={submitting}
              className="w-full h-10 rounded-md bg-terracotta-500 text-white text-sm font-medium hover:bg-terracotta-600 disabled:opacity-60 flex items-center justify-center gap-2"
            >
              {submitting && <LoaderCircle className="w-4 h-4 animate-spin" />}
              {mode === 'login' ? '登录' : '创建账号'}
            </button>
          </form>
        </div>
      </div>
    </main>
  )
}

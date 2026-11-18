import { useState } from 'react'

/** Apply a recovered server draft once; refetching it must not replace local edits. */
export function useRecoveredFormState<T>(
  defaults: T,
  recovery?: { key: string; values: T },
) {
  const [form, setForm] = useState(() => ({
    recoveryKey: recovery?.key,
    values: recovery?.values ?? defaults,
  }))

  if (recovery && recovery.key !== form.recoveryKey) {
    setForm({ recoveryKey: recovery.key, values: recovery.values })
  }

  const setField = <K extends keyof T>(key: K, value: T[K]) => {
    setForm((current) => ({
      ...current,
      values: { ...current.values, [key]: value },
    }))
  }

  return { values: form.values, setField }
}

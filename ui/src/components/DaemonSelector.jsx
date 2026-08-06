// ── DaemonSelector ──────────────────────────────────────────────
// Permite al usuario elegir a quÃ© daemon conectarse:
// - "auto" â†’ UDP discovery
// - "local" â†’ localhost:7779
// - "manual" â†’ input de IP:puerto
// Guarda preferencia en localStorage y muestra estado de conexiÃ³n.
// ────────────────────────────────────────────────────────────────

import { createSignal, createEffect, onCleanup } from 'solid-js'

const STORAGE_KEY = 'yola-daemon-pref'

function loadPref() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) return JSON.parse(raw)
  } catch { /* ignore */ }
  return { mode: 'auto', host: '', port: 7779 }
}

function savePref(pref) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(pref))
  } catch { /* ignore */ }
}

export function DaemonSelector(props) {
  const [pref, setPref] = createSignal(loadPref())
  const [status, setStatus] = createSignal('checking') // 'checking' | 'connected' | 'disconnected'
  const [manualHost, setManualHost] = createSignal(pref().host || '')
  const [manualPort, setManualPort] = createSignal(pref().port || 7779)

  let intervalId

  // Ping daemon periÃ³dicamente
  function getDaemonUrl() {
    const p = pref()
    if (p.mode === 'local') return 'http://localhost:7779'
    if (p.mode === 'manual' && p.host) return `http://${p.host}:${p.port || 7779}`
    // auto: el discovery lo manejarÃ­a el cliente @yola/client
    return 'http://localhost:7779' // fallback
  }

  async function checkConnection() {
    try {
      const url = pref().mode === 'auto'
        ? 'http://localhost:7779/health'
        : `${getDaemonUrl()}/health`

      const controller = new AbortController()
      const timeout = setTimeout(() => controller.abort(), 3000)
      const res = await fetch(url, { signal: controller.signal })
      clearTimeout(timeout)

      if (res.ok) {
        setStatus('connected')
      } else {
        setStatus('disconnected')
      }
    } catch {
      setStatus('disconnected')
    }
  }

  createEffect(() => {
    const p = pref()
    savePref(p)
    checkConnection()

    clearInterval(intervalId)
    intervalId = setInterval(checkConnection, 15000)
  })

  onCleanup(() => clearInterval(intervalId))

  function handleModeChange(e) {
    const mode = e.target.value
    setPref({ ...pref(), mode, host: mode === 'manual' ? manualHost() : '', port: mode === 'manual' ? manualPort() : 7779 })
    if (props.onChange) props.onChange({ ...pref(), mode })
  }

  function handleManualApply() {
    setPref({ mode: 'manual', host: manualHost(), port: parseInt(manualPort()) || 7779 })
    if (props.onChange) props.onChange({ mode: 'manual', host: manualHost(), port: parseInt(manualPort()) || 7779 })
  }

  const statusText = () => {
    switch (status()) {
      case 'connected': return 'ðŸŸ¢ Conectado'
      case 'disconnected': return 'ðŸ”´ Sin conexiÃ³n'
      default: return 'â³ Verificando'
    }
  }

  const statusClass = () => {
    switch (status()) {
      case 'connected': return 'daemon-status connected'
      case 'disconnected': return 'daemon-status disconnected'
      default: return 'daemon-status'
    }
  }

  return (
    <div class="daemon-selector">
      <label>Daemon:</label>
      <select value={pref().mode} onChange={handleModeChange}>
        <option value="auto">Auto (UDP)</option>
        <option value="local">Local (localhost:7779)</option>
        <option value="manual">Manual</option>
      </select>
      {pref().mode === 'manual' && (
        <div class="daemon-manual-input">
          <input
            type="text"
            placeholder="IP:puerto"
            value={manualHost()}
            onInput={(e) => setManualHost(e.target.value)}
          />
          <input
            type="number"
            placeholder="7779"
            value={manualPort()}
            onInput={(e) => setManualPort(e.target.value)}
            style="width: 60px"
          />
          <button
            onClick={handleManualApply}
            style="padding: 2px 8px; font-size: 10px; border: 1px solid var(--border-window); border-radius: var(--radius-sm); background: var(--accent); color: #000; cursor: pointer;"
          >
            OK
          </button>
        </div>
      )}
      <span class={statusClass()}>{statusText()}</span>
    </div>
  )
}

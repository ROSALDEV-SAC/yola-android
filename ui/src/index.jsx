// YOLA Android - Entry Point
// Mobile-first: solo Chat + Sesiones. Sin taskbar, sin ventanas.
// Navegacion tipo stack, una app visible a la vez.
// Al abrir -> chat directo con agentId='yola'.

import { render } from 'solid-js/web'
import { createSignal, For, createEffect, onMount } from 'solid-js'
import { DaemonSelector } from './components/DaemonSelector'

const [currentApp, setCurrentApp] = createSignal('chat')
const [daemonUrl, setDaemonUrl] = createSignal('')

// ======================================================================
// Chat View
// ======================================================================

function ChatView() {
  const [messages, setMessages] = createSignal([
    { role: 'assistant', content: 'Hola, soy YOLA. En que puedo ayudarte?' }
  ])
  const [input, setInput] = createSignal('')
  const [loading, setLoading] = createSignal(false)

  let messagesEnd

  function scrollToBottom() {
    messagesEnd?.scrollIntoView({ behavior: 'smooth' })
  }

  createEffect(() => {
    messages()
    scrollToBottom()
  })

  async function sendMessage() {
    const text = input().trim()
    if (!text || loading()) return

    setMessages(prev => [...prev, { role: 'user', content: text }])
    setInput('')
    setLoading(true)

    try {
      const url = daemonUrl() || 'http://localhost:7779'
      const res = await fetch(url + '/api/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message: text, agentId: 'yola', sessionId: 'yola' })
      })

      if (res.ok) {
        const data = await res.json()
        setMessages(prev => [...prev, {
          role: 'assistant',
          content: data.response || data.text || '(sin respuesta)'
        }])
      } else {
        setMessages(prev => [...prev, {
          role: 'assistant',
          content: 'Error: ' + res.status + ' ' + res.statusText
        }])
      }
    } catch (err) {
      setMessages(prev => [...prev, {
        role: 'assistant',
        content: 'Error de conexion: ' + err.message + '. Verifica el daemon.'
      }])
    } finally {
      setLoading(false)
    }
  }

  function handleKeyDown(e) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      sendMessage()
    }
  }

  return (
    <div class="mobile-chat">
      <div class="mobile-chat-header">
        <h2>YOLA</h2>
        <span style="font-size:12px;color:var(--text-muted)">agente: yola</span>
      </div>

      <div class="mobile-chat-messages">
        {messages().length <= 1 && (
          <div class="mobile-chat-empty">
            Escribe un mensaje para comenzar...
          </div>
        )}
        <For each={messages()}>
          {(msg) => (
            <div class={'mobile-chat-message ' + msg.role}>
              {msg.content}
            </div>
          )}
        </For>
        {loading() && (
          <div class="mobile-chat-message assistant" style="opacity:0.6">
            Pensando...
          </div>
        )}
        <div ref={messagesEnd} />
      </div>

      <div class="mobile-chat-input">
        <textarea
          rows="1"
          placeholder="Escribe un mensaje..."
          value={input()}
          onInput={(e) => setInput(e.target.value)}
          onKeyDown={handleKeyDown}
          disabled={loading()}
        />
        <button onClick={sendMessage} disabled={loading() || !input().trim()}>
          &#8593;
        </button>
      </div>
    </div>
  )
}

// ======================================================================
// Sessions View
// ======================================================================

function SessionsView() {
  const [sessions, setSessions] = createSignal([
    { id: 'yola', name: 'YOLA - Principal', date: '2026-08-01', count: 42 },
    { id: 'dev', name: 'Desarrollo', date: '2026-08-05', count: 7 },
    { id: 'debug', name: 'Debug CI/CD', date: '2026-08-04', count: 3 }
  ])
  const [activeId, setActiveId] = createSignal('yola')

  onMount(async () => {
    try {
      const url = daemonUrl() || 'http://localhost:7779'
      const res = await fetch(url + '/api/sessions')
      if (res.ok) {
        const data = await res.json()
        if (Array.isArray(data) && data.length > 0) {
          setSessions(data.map(s => ({
            id: s.id || s.sessionId,
            name: s.name || s.title || s.id || 'Sesion',
            date: s.date || s.createdAt || '',
            count: s.messageCount || s.count || 0
          })))
        }
      }
    } catch {
      // Usa datos demo si no hay conexion
    }
  })

  return (
    <div class="mobile-sessions">
      <div class="mobile-sessions-header">
        <h2>Sesiones</h2>
        <span style="font-size:12px;color:var(--text-muted)">
          {sessions().length} sesiones
        </span>
      </div>

      <div class="mobile-sessions-list">
        {sessions().length === 0 ? (
          <div class="mobile-sessions-empty">
            No hay sesiones aun. Vuelve al chat para crear una.
          </div>
        ) : (
          <For each={sessions()}>
            {(s) => (
              <div
                class={'mobile-session-card' + (activeId() === s.id ? ' active' : '')}
                onClick={() => {
                  setActiveId(s.id)
                  setCurrentApp('chat')
                }}
              >
                <div class="mobile-session-card-icon">&#128172;</div>
                <div class="mobile-session-card-info">
                  <div class="mobile-session-card-name">{s.name}</div>
                  <div class="mobile-session-card-meta">
                    <span>{s.date}</span>
                  </div>
                </div>
                <div class="mobile-session-card-badge">{s.count}</div>
              </div>
            )}
          </For>
        )}
      </div>
    </div>
  )
}

// ======================================================================
// App Shell
// ======================================================================

function App() {
  return (
    <div class="mobile-app">
      <DaemonSelector onChange={(pref) => {
        let url = 'http://localhost:7779'
        if (pref.mode === 'manual' && pref.host) {
          url = 'http://' + pref.host + ':' + (pref.port || 7779)
        }
        setDaemonUrl(url)
      }} />

      <div class="mobile-app-content">
        {currentApp() === 'chat' && <ChatView />}
        {currentApp() === 'sessions' && <SessionsView />}
      </div>

      <nav class="mobile-nav">
        <button
          class={'mobile-nav-btn' + (currentApp() === 'chat' ? ' active' : '')}
          onClick={() => setCurrentApp('chat')}
        >
          <span class="mobile-nav-btn-icon">&#128172;</span>
          <span>Chat</span>
        </button>
        <button
          class={'mobile-nav-btn' + (currentApp() === 'sessions' ? ' active' : '')}
          onClick={() => setCurrentApp('sessions')}
        >
          <span class="mobile-nav-btn-icon">&#128196;</span>
          <span>Sesiones</span>
        </button>
      </nav>
    </div>
  )
}

// ======================================================================
// Bootstrap
// ======================================================================

render(() => <App />, document.getElementById('app'))

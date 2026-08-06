use axum::{
    extract::State,
    http::StatusCode,
    response::Json,
    routing::{get, post},
    Router,
};
use clap::Parser;
use serde::{Deserialize, Serialize};
use std::net::UdpSocket;
use std::sync::Arc;
use std::time::Duration;
use tower_http::cors::CorsLayer;

// ── CLI ──────────────────────────────────────────────────────────

#[derive(Parser)]
#[command(name = "yola-daemon-mobile")]
struct Cli {
    #[arg(long, default_value = "7779")]
    port: u16,
    #[arg(long, default_value = "41335")]
    discovery_port: u16,
    #[arg(long, default_value = "0.0.0.0")]
    host: String,
}

// ── API Types ────────────────────────────────────────────────────

#[derive(Deserialize)]
struct ChatRequest {
    message: String,
    #[serde(default)]
    agent_id: Option<String>,
    #[serde(default)]
    session_id: Option<String>,
}

#[derive(Serialize)]
struct ChatResponse {
    response: String,
    session_id: String,
}

#[derive(Serialize)]
struct SessionInfo {
    id: String,
    title: String,
    created_at: String,
    message_count: u32,
}

#[derive(Serialize)]
struct HealthResponse {
    status: String,
    version: String,
    device: String,
    port: u16,
}

// ── App State ────────────────────────────────────────────────────

struct AppState {
    port: u16,
}

// ── Handlers ─────────────────────────────────────────────────────

async fn health(State(state): State<Arc<AppState>>) -> Json<HealthResponse> {
    Json(HealthResponse {
        status: "ok".into(),
        version: "0.1.3".into(),
        device: "android".into(),
        port: state.port,
    })
}

async fn chat(State(_state): State<Arc<AppState>>, Json(req): Json<ChatRequest>) -> Result<Json<ChatResponse>, StatusCode> {
    let text = req.message.trim();
    if text.is_empty() {
        return Err(StatusCode::BAD_REQUEST);
    }

    // Por ahora, responde con un eco mejorado.
    // Cuando se conecte a un LLM, aquí irá la llamada real.
    let response = format!("[YOLA v0.1.3] Recibido: \"{}\" — El motor de IA estará disponible en una versión futura.", text);

    Ok(Json(ChatResponse {
        response,
        session_id: req.session_id.unwrap_or_else(|| "yola".into()),
    }))
}

async fn list_sessions() -> Json<Vec<SessionInfo>> {
    Json(vec![SessionInfo {
        id: "yola".into(),
        title: "Sesión YOLA".into(),
        created_at: chrono::Utc::now().to_rfc3339(),
        message_count: 1,
    }])
}

// ── Discovery Beacon ─────────────────────────────────────────────

fn get_local_ip() -> Option<String> {
    let socket = UdpSocket::bind("0.0.0.0:0").ok()?;
    socket.connect("8.8.8.8:80").ok()?;
    socket.local_addr().ok().map(|a| a.ip().to_string())
}

fn start_discovery_beacon(port: u16, discovery_port: u16) {
    let socket = UdpSocket::bind(format!("0.0.0.0:{}", discovery_port)).unwrap();
    socket.set_broadcast(true).unwrap();

    tokio::spawn(async move {
        loop {
            if let Some(ip) = get_local_ip() {
                let beacon = serde_json::json!({
                    "type": "YOLA_BEACON",
                    "host": ip,
                    "port": port,
                    "version": "0.1.3",
                    "device": "android"
                });
                let msg = beacon.to_string();
                let _ = socket.send_to(msg.as_bytes(), format!("255.255.255.255:{}", discovery_port));
            }
            tokio::time::sleep(Duration::from_secs(2)).await;
        }
    });
}

// ── Main ─────────────────────────────────────────────────────────

#[tokio::main]
async fn main() {
    env_logger::init();
    let cli = Cli::parse();

    log::info!("YOLA Daemon Mobile v0.1.3");
    log::info!("Bridge HTTP: {}:{}", cli.host, cli.port);
    log::info!("Discovery UDP: {}:{}", cli.host, cli.discovery_port);

    // Iniciar beacon UDP
    start_discovery_beacon(cli.port, cli.discovery_port);

    // Configurar router HTTP
    let state = Arc::new(AppState { port: cli.port });
    let app = Router::new()
        .route("/", get(health))
        .route("/api/health", get(health))
        .route("/api/chat", post(chat))
        .route("/api/sessions", get(list_sessions))
        .layer(CorsLayer::permissive())
        .with_state(state);

    let addr = format!("{}:{}", cli.host, cli.port);
    log::info!("Daemon escuchando en http://{}", addr);

    let listener = tokio::net::TcpListener::bind(&addr).await.unwrap();
    axum::serve(listener, app).await.unwrap();
}

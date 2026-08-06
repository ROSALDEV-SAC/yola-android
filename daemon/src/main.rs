use clap::Parser;
use std::net::UdpSocket;
use std::time::Duration;

#[derive(Parser)]
#[command(name = "yola-daemon-mobile")]
struct Cli {
    #[arg(long, default_value = "7779")]
    port: u16,
    #[arg(long, default_value = "41335")]
    discovery_port: u16,
}

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
                    "version": "0.1.0",
                    "device": "android"
                });
                let msg = beacon.to_string();
                let _ = socket.send_to(msg.as_bytes(), format!("255.255.255.255:{}", discovery_port));
            }
            tokio::time::sleep(Duration::from_secs(2)).await;
        }
    });
}

#[tokio::main]
async fn main() {
    env_logger::init();
    let cli = Cli::parse();

    log::info!("YOLA Daemon Mobile v0.1.0");
    log::info!("Bridge HTTP: 0.0.0.0:{}", cli.port);
    log::info!("Discovery UDP: 0.0.0.0:{}", cli.discovery_port);

    // Iniciar beacon UDP
    start_discovery_beacon(cli.port, cli.discovery_port);

    // Iniciar bridge HTTP (usar mod.rs::run_http_server de yola-agent-runtime si es público)
    // Si no es público, crear un servidor HTTP simple que delegue al runtime
    log::info!("Daemon listo. Esperando conexiones...");

    // Mantener vivo hasta Ctrl+C
    tokio::signal::ctrl_c().await.ok();
    log::info!("Shutdown.");
}

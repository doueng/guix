//! rexmux: a Rex client. See README.md.

#[path = "../../dusk/src/edn.rs"]
#[allow(dead_code)]
mod edn;

mod app;
mod conv;
#[cfg(feature = "gui")]
#[path = "../../dusk/src/fonts.rs"]
#[allow(dead_code)]
mod fonts;
#[cfg(feature = "gui")]
mod gui;
mod keys;
mod link;
mod model;
mod probe;
mod rex;
mod screen;
mod stream;
mod tty;

use std::path::PathBuf;

const USAGE: &str = "usage: rexmux [gui] | tty | probe [--text] | version
  gui    the window (default)
  tty    the same client in this terminal, as plain text
  probe  connect, show the first session's focused pane once, and exit
Servers come from $REXMUX_CONFIG, else ~/.config/rexmux/config.edn
({:servers [{:label \"local\" :address nil}]}); REX_SERVER sets the default.";

/// The servers in the config file, as (label, address).
pub fn servers() -> Vec<(String, Option<String>)> {
    let path = std::env::var_os("REXMUX_CONFIG").map(PathBuf::from).unwrap_or_else(|| {
        let base = std::env::var_os("XDG_CONFIG_HOME").map(PathBuf::from)
            .unwrap_or_else(|| PathBuf::from(std::env::var_os("HOME").unwrap_or_default()).join(".config"));
        base.join("rexmux/config.edn")
    });
    let parsed = std::fs::read_to_string(&path).ok().and_then(|t| edn::parse(&t).ok());
    let list: Vec<(String, Option<String>)> = parsed
        .as_ref()
        .and_then(|c| c.get("servers"))
        .map(edn::Value::items)
        .unwrap_or(&[])
        .iter()
        .map(|s| {
            (conv::str_of(s.get("label")).unwrap_or_else(|| "local".into()), conv::str_of(s.get("address")))
        })
        .collect();
    if list.is_empty() { vec![("local".into(), None)] } else { list }
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let code = match args.first().map(String::as_str) {
        Some("tty") => tty::run(servers()),
        Some("probe") => probe::run(servers(), &args[1..]),
        Some("version") => {
            println!("rexmux {} (Rex API revision {})", env!("CARGO_PKG_VERSION"), rex::API_REVISION);
            0
        }
        Some("-h" | "--help" | "help") => {
            println!("{USAGE}");
            0
        }
        None | Some("gui") => run_gui(),
        Some(other) => {
            eprintln!("rexmux: unknown command {other}\n{USAGE}");
            2
        }
    };
    std::process::exit(code);
}

#[cfg(feature = "gui")]
fn run_gui() -> i32 {
    gui::run(servers())
}

#[cfg(not(feature = "gui"))]
fn run_gui() -> i32 {
    eprintln!("rexmux: built without the gui feature; use rexmux tty");
    2
}

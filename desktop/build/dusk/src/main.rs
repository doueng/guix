mod core;
mod edn;
mod fonts;
mod input;
mod ipc;
mod runtime;
mod services;
mod style;
mod surface;

use std::path::{Path, PathBuf};

use crate::core::{Core, CoreEvent};
use crate::runtime::RuntimeOptions;

const USAGE: &str = "\
usage: dusk [daemon] [--config FILE] [--runtime DIR] [--bb PATH] [--no-notifications]
       dusk msg status|reload|quit|signals|surfaces
       dusk msg eval CODE...
       dusk msg call NAME [EDN-ARGS]
       dusk dmenu [-p PROMPT] < choices";

fn home() -> PathBuf {
    std::env::var_os("HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| "/".into())
}

fn default_config() -> PathBuf {
    let base = std::env::var_os("XDG_CONFIG_HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| home().join(".config"));
    base.join("dusk").join("config.clj")
}

fn default_runtime_dir() -> PathBuf {
    if let Some(dir) = std::env::var_os("DUSK_RUNTIME") {
        return dir.into();
    }
    let exe = std::env::current_exe()
        .ok()
        .and_then(|p| std::fs::canonicalize(p).ok())
        .unwrap_or_default();
    let mut candidates = Vec::new();
    if let Some(bin) = exe.parent() {
        candidates.push(bin.join("../share/dusk/runtime"));
        candidates.push(bin.join("../../runtime"));
    }
    candidates
        .into_iter()
        .find(|c| c.join("dusk/runtime.clj").is_file())
        .unwrap_or_else(|| PathBuf::from("runtime"))
}

struct DaemonArgs {
    runtime: RuntimeOptions,
    notifications: bool,
}

fn parse_daemon_args(args: &[String]) -> Result<DaemonArgs, String> {
    let mut config = default_config();
    let mut runtime_dir = default_runtime_dir();
    let mut bb = std::env::var_os("DUSK_BB")
        .map(PathBuf::from)
        .unwrap_or_else(|| "bb".into());
    let mut notifications = true;
    let mut it = args.iter();
    while let Some(arg) = it.next() {
        let mut value = |name: &str| it.next().cloned().ok_or(format!("{name} needs a value"));
        match arg.as_str() {
            "daemon" | "--daemon" => {}
            "--config" => config = PathBuf::from(value("--config")?),
            "--runtime" => runtime_dir = PathBuf::from(value("--runtime")?),
            "--bb" => bb = PathBuf::from(value("--bb")?),
            "--no-notifications" => notifications = false,
            other => return Err(format!("unknown argument {other:?}")),
        }
    }
    let config = if config.is_relative() {
        std::env::current_dir().unwrap_or_default().join(config)
    } else {
        config
    };
    Ok(DaemonArgs {
        runtime: RuntimeOptions {
            bb,
            runtime_dir,
            config,
        },
        notifications,
    })
}

fn run_daemon(args: DaemonArgs) -> i32 {
    if !args.runtime.config.is_file() {
        eprintln!(
            "dusk: config {} does not exist",
            args.runtime.config.display()
        );
        return 1;
    }
    if !Path::new(&args.runtime.runtime_dir)
        .join("dusk/runtime.clj")
        .is_file()
    {
        eprintln!(
            "dusk: runtime namespaces not found in {} (set DUSK_RUNTIME)",
            args.runtime.runtime_dir.display()
        );
        return 1;
    }
    fonts::configure_fontconfig();
    fonts::configure_vulkan();

    let (tx, rx) = smol::channel::unbounded::<CoreEvent>();
    if let Err(e) = ipc::listen(tx.clone()) {
        eprintln!("dusk: {e}");
        return 1;
    }

    let socket = ipc::socket_path();
    gpui_platform::application().run(move |cx| {
        input::init(cx);
        let notifications = args
            .notifications
            .then(|| services::notifications::start(tx.clone()));
        services::watch::start(args.runtime.config.clone(), tx.clone());
        services::audio::start(tx.clone());
        services::backlight::start(tx.clone());

        let mut core = Core::new(args.runtime.clone(), tx.clone(), notifications);
        core.start_runtime();
        cx.spawn(async move |cx| {
            while let Ok(event) = rx.recv().await {
                cx.update(|cx| core.handle(event, cx));
            }
        })
        .detach();
        cx.on_app_quit(move |_| {
            let _ = std::fs::remove_file(&socket);
            async {}
        })
        .detach();
    });
    let _ = std::fs::remove_file(ipc::socket_path());
    0
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let code = match args.first().map(String::as_str) {
        Some("msg") => ipc::client_msg(&args[1..]),
        Some("dmenu") => ipc::client_dmenu(&args[1..]),
        Some("-h" | "--help" | "help") => {
            println!("{USAGE}");
            0
        }
        _ => match parse_daemon_args(&args) {
            Ok(daemon) => run_daemon(daemon),
            Err(e) => {
                eprintln!("dusk: {e}\n{USAGE}");
                2
            }
        },
    };
    std::process::exit(code);
}

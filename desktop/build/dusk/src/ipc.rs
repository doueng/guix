use std::io::{BufRead, BufReader, Read, Write};
use std::os::unix::net::{UnixListener, UnixStream};
use std::path::PathBuf;
use std::sync::mpsc;

use crate::core::CoreEvent;
use crate::edn::{self, Value};

pub fn socket_path() -> PathBuf {
    let dir = std::env::var_os("XDG_RUNTIME_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(std::env::temp_dir);
    let display = std::env::var("WAYLAND_DISPLAY").unwrap_or_else(|_| "wayland-0".into());
    dir.join(format!("dusk-{display}.sock"))
}

pub fn ok(value: Value) -> Value {
    Value::map([("ok", Value::Bool(true)), ("value", value)])
}

pub fn error(message: impl Into<String>) -> Value {
    Value::map([
        ("ok", Value::Bool(false)),
        ("error", Value::Str(message.into())),
    ])
}

pub fn listen(events: smol::channel::Sender<CoreEvent>) -> anyhow::Result<()> {
    let path = socket_path();
    if path.exists() {
        if UnixStream::connect(&path).is_ok() {
            anyhow::bail!(
                "another dusk instance is already listening on {}",
                path.display()
            );
        }
        let _ = std::fs::remove_file(&path);
    }
    let listener = UnixListener::bind(&path)?;
    std::thread::Builder::new()
        .name("dusk-ipc".into())
        .spawn(move || {
            for stream in listener.incoming().flatten() {
                let events = events.clone();
                let _ = std::thread::Builder::new()
                    .name("dusk-ipc-conn".into())
                    .spawn(move || serve(stream, events));
            }
        })?;
    Ok(())
}

fn serve(stream: UnixStream, events: smol::channel::Sender<CoreEvent>) {
    let mut reader = BufReader::new(&stream);
    let mut line = String::new();
    if reader.read_line(&mut line).is_err() {
        return;
    }
    let reply = match edn::parse(line.trim()) {
        Ok(request) => {
            let (tx, rx) = mpsc::channel();
            if events
                .send_blocking(CoreEvent::Ipc { request, reply: tx })
                .is_err()
            {
                error("daemon is shutting down")
            } else {
                rx.recv().unwrap_or_else(|_| error("request dropped"))
            }
        }
        Err(e) => error(e.to_string()),
    };
    let mut stream = &stream;
    let _ = writeln!(stream, "{reply}");
}

pub fn request(req: &Value) -> anyhow::Result<Value> {
    let path = socket_path();
    let mut stream = UnixStream::connect(&path)
        .map_err(|e| anyhow::anyhow!("dusk is not running ({}: {e})", path.display()))?;
    writeln!(stream, "{req}")?;
    let mut reply = String::new();
    stream.read_to_string(&mut reply)?;
    Ok(edn::parse(reply.trim())?)
}

fn print_value(v: &Value) {
    match v {
        Value::Nil => {}
        Value::Str(s) => println!("{s}"),
        other => println!("{other}"),
    }
}

pub fn client_msg(args: &[String]) -> i32 {
    let Some(cmd) = args.first() else {
        eprintln!("usage: dusk msg status|reload|quit|eval CODE|call NAME [EDN]|signals");
        return 2;
    };
    let req = match cmd.as_str() {
        "status" | "reload" | "quit" | "signals" | "surfaces" => {
            Value::map([("op", Value::kw(cmd))])
        }
        "eval" => Value::map([
            ("op", Value::kw("eval")),
            ("code", Value::Str(args[1..].join(" "))),
        ]),
        "call" => {
            let Some(name) = args.get(1) else {
                eprintln!("usage: dusk msg call NAME [EDN-ARGS]");
                return 2;
            };
            let call_args = match args.get(2).map(|a| edn::parse(a)) {
                None => Value::Nil,
                Some(Ok(v)) => v,
                Some(Err(e)) => {
                    eprintln!("dusk: {e}");
                    return 2;
                }
            };
            Value::map([
                ("op", Value::kw("request")),
                ("name", Value::kw(name.trim_start_matches(':'))),
                ("args", call_args),
            ])
        }
        other => {
            eprintln!("dusk: unknown msg command {other:?}");
            return 2;
        }
    };
    match request(&req) {
        Ok(reply) if reply.get("ok").is_some_and(Value::truthy) => {
            print_value(reply.get("value").unwrap_or(&Value::Nil));
            0
        }
        Ok(reply) => {
            eprintln!(
                "dusk: {}",
                reply
                    .get("error")
                    .and_then(Value::as_str)
                    .unwrap_or("request failed")
            );
            1
        }
        Err(e) => {
            eprintln!("dusk: {e}");
            1
        }
    }
}

/// `dusk dmenu [-p PROMPT]`: choose one stdin line through the shell UI.
/// Prints the choice and exits 0, or exits 1 when cancelled.
pub fn client_dmenu(args: &[String]) -> i32 {
    let mut prompt = String::new();
    let mut it = args.iter();
    while let Some(arg) = it.next() {
        match arg.as_str() {
            "-p" | "--prompt" => prompt = it.next().cloned().unwrap_or_default(),
            // dmenu compatibility flags that do not change behaviour here.
            "-i" | "-b" | "-f" => {}
            "-l" | "-fn" | "-nb" | "-nf" | "-sb" | "-sf" | "-m" | "-w" => {
                it.next();
            }
            other => {
                eprintln!("dusk dmenu: unknown option {other:?}");
                return 2;
            }
        }
    }
    let mut input = String::new();
    if std::io::stdin().read_to_string(&mut input).is_err() {
        return 1;
    }
    let items: Vec<Value> = input
        .lines()
        .filter(|l| !l.trim().is_empty())
        .map(Value::str)
        .collect();
    let req = Value::map([
        ("op", Value::kw("request")),
        ("name", Value::kw("dmenu")),
        (
            "args",
            Value::map([
                ("items", Value::Vector(items)),
                ("prompt", Value::Str(prompt)),
            ]),
        ),
    ]);
    match request(&req) {
        Ok(reply) if reply.get("ok").is_some_and(Value::truthy) => match reply.get("value") {
            Some(Value::Str(choice)) => {
                println!("{choice}");
                0
            }
            _ => 1,
        },
        Ok(reply) => {
            eprintln!(
                "dusk: {}",
                reply
                    .get("error")
                    .and_then(Value::as_str)
                    .unwrap_or("dmenu failed")
            );
            1
        }
        Err(e) => {
            eprintln!("dusk: {e}");
            1
        }
    }
}

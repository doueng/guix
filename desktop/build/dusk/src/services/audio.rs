//! Default sink/source volume as `:volume` and `:microphone` signals.
//!
//! `pw-dump --monitor` is used purely as a change trigger: whenever PipeWire
//! reports a node, device or metadata change we wait for the burst to settle
//! and re-read the defaults with `wpctl`, which already resolves default
//! devices and cubic volumes.

use std::io::{BufRead, BufReader};
use std::process::{Command, Stdio};
use std::sync::mpsc;
use std::time::Duration;

use crate::core::CoreEvent;
use crate::edn::Value;

const SETTLE: Duration = Duration::from_millis(40);
const RELEVANT: [&str; 3] = [
    "\"PipeWire:Interface:Node\"",
    "\"PipeWire:Interface:Device\"",
    "\"PipeWire:Interface:Metadata\"",
];

fn read_volume(target: &str) -> Value {
    let Ok(out) = Command::new("wpctl")
        .args(["get-volume", target])
        .stderr(Stdio::null())
        .output()
    else {
        return Value::Nil;
    };
    if !out.status.success() {
        return Value::Nil;
    }
    let text = String::from_utf8_lossy(&out.stdout);
    // "Volume: 0.40" or "Volume: 0.40 [MUTED]"
    let muted = text.contains("[MUTED]");
    let Some(level) = text
        .split_whitespace()
        .nth(1)
        .and_then(|s| s.parse::<f64>().ok())
    else {
        return Value::Nil;
    };
    Value::map([
        ("percent", Value::Int((level * 100.0).round() as i64)),
        ("muted?", Value::Bool(muted)),
    ])
}

struct Publisher {
    events: smol::channel::Sender<CoreEvent>,
    last: [Value; 2],
}

impl Publisher {
    fn refresh(&mut self) -> bool {
        let current = [
            read_volume("@DEFAULT_AUDIO_SINK@"),
            read_volume("@DEFAULT_AUDIO_SOURCE@"),
        ];
        for (i, name) in ["volume", "microphone"].iter().enumerate() {
            if current[i] != self.last[i] {
                self.last[i] = current[i].clone();
                if self
                    .events
                    .send_blocking(CoreEvent::Signal((*name).into(), current[i].clone()))
                    .is_err()
                {
                    return false;
                }
            }
        }
        true
    }
}

pub fn start(events: smol::channel::Sender<CoreEvent>) {
    std::thread::Builder::new()
        .name("dusk-audio".into())
        .spawn(move || {
            let mut publisher = Publisher {
                events,
                last: [Value::Nil, Value::Nil],
            };
            loop {
                let child = Command::new("pw-dump")
                    .args(["--monitor", "--no-colors"])
                    .stdout(Stdio::piped())
                    .stderr(Stdio::null())
                    .spawn();
                let Ok(mut child) = child else {
                    if !publisher.refresh() {
                        return;
                    }
                    std::thread::sleep(Duration::from_secs(2));
                    continue;
                };
                let stdout = BufReader::new(child.stdout.take().unwrap());
                let (tx, rx) = mpsc::channel::<()>();
                std::thread::spawn(move || {
                    // Clients (e.g. browsers) update their properties many
                    // times a second; only nodes, devices and the default
                    // device metadata can change a volume.
                    for line in stdout.lines() {
                        let Ok(line) = line else { break };
                        if RELEVANT.iter().any(|t| line.contains(t)) && tx.send(()).is_err() {
                            break;
                        }
                    }
                });
                while rx.recv().is_ok() {
                    loop {
                        match rx.recv_timeout(SETTLE) {
                            Ok(()) => continue,
                            Err(mpsc::RecvTimeoutError::Timeout) => break,
                            Err(mpsc::RecvTimeoutError::Disconnected) => break,
                        }
                    }
                    if !publisher.refresh() {
                        let _ = child.kill();
                        return;
                    }
                }
                let _ = child.kill();
                let _ = child.wait();
                std::thread::sleep(Duration::from_secs(2));
            }
        })
        .expect("spawn audio thread");
}

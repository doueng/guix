//! The link to the bb sidecar: a child process speaking one EDN map per line
//! (sidecar/rexmux/proto.clj). This side numbers what it sends from 1 and
//! accepts what it receives only in order: a repeat is dropped and a gap is
//! reported, never hidden (verify/quint/rexmux.qnt, inOrder).

use std::io::{BufRead, BufReader, Write};
use std::path::PathBuf;
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::mpsc::Sender;

use crate::edn::{self, Value};

#[derive(Debug, PartialEq)]
pub enum Verdict {
    Deliver,
    Duplicate,
    Gap(i64, i64),
    Invalid,
}

/// The receiving half's numbering: deliver the next number, drop older ones,
/// and report skipped ones while moving past them.
#[derive(Debug, Default)]
pub struct Receiver {
    pub got: i64,
}

impl Receiver {
    pub fn accept(&mut self, msg: &Value) -> Verdict {
        let Some(n) = msg.get("seq").and_then(Value::as_i64) else {
            return Verdict::Invalid;
        };
        if n == self.got + 1 {
            self.got = n;
            Verdict::Deliver
        } else if n <= self.got {
            Verdict::Duplicate
        } else {
            let gap = Verdict::Gap(self.got + 1, n - 1);
            self.got = n;
            gap
        }
    }
}

/// What the reader thread reports.
pub enum Incoming {
    Msg(Value),
    Down(String),
}

pub struct Link {
    child: Child,
    stdin: ChildStdin,
    sent: i64,
    pub rx: Receiver,
}

pub struct SidecarCommand {
    pub bb: PathBuf,
    pub dir: PathBuf,
}

impl SidecarCommand {
    /// The sidecar beside this binary's sources (REXMUX_SIDECAR_DIR), run
    /// with bb's task runner.
    pub fn from_env() -> SidecarCommand {
        let dir = std::env::var_os("REXMUX_SIDECAR_DIR")
            .map(PathBuf::from)
            .unwrap_or_else(|| PathBuf::from(env!("CARGO_MANIFEST_DIR")));
        let bb = std::env::var_os("REXMUX_BB").map(PathBuf::from).unwrap_or_else(|| "bb".into());
        SidecarCommand { bb, dir }
    }
}

impl Link {
    pub fn spawn(cmd: &SidecarCommand, out: Sender<Incoming>) -> std::io::Result<Link> {
        let mut child = Command::new(&cmd.bb)
            .arg("--config")
            .arg(cmd.dir.join("bb.edn"))
            .arg("sidecar")
            .current_dir(&cmd.dir)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit())
            .spawn()?;
        let stdin = child.stdin.take().expect("piped stdin");
        let stdout = child.stdout.take().expect("piped stdout");
        std::thread::spawn(move || {
            let reader = BufReader::new(stdout);
            for line in reader.lines() {
                match line {
                    Ok(l) if l.trim().is_empty() => {}
                    Ok(l) => match edn::parse(&l) {
                        Ok(v) => {
                            if out.send(Incoming::Msg(v)).is_err() {
                                return;
                            }
                        }
                        Err(e) => eprintln!("rexmux: sidecar sent unreadable EDN: {e}"),
                    },
                    Err(e) => {
                        let _ = out.send(Incoming::Down(e.to_string()));
                        return;
                    }
                }
            }
            let _ = out.send(Incoming::Down("sidecar exited".into()));
        });
        Ok(Link { child, stdin, sent: 0, rx: Receiver::default() })
    }

    /// Send MSG with the next :seq.
    pub fn send(&mut self, msg: Value) -> std::io::Result<()> {
        self.sent += 1;
        let mut entries = match msg {
            Value::Map(e) => e,
            other => vec![(Value::kw("value"), other)],
        };
        entries.push((Value::kw("seq"), Value::Int(self.sent)));
        writeln!(self.stdin, "{}", Value::Map(entries))?;
        self.stdin.flush()
    }

    pub fn kill(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

impl Drop for Link {
    fn drop(&mut self) {
        self.kill();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn m(seq: i64) -> Value {
        edn::parse(&format!("{{:op :pong :seq {seq}}}")).unwrap()
    }

    #[test]
    fn delivers_in_order_drops_repeats_and_reports_gaps() {
        let mut r = Receiver::default();
        assert_eq!(r.accept(&m(1)), Verdict::Deliver);
        assert_eq!(r.accept(&m(1)), Verdict::Duplicate);
        assert_eq!(r.accept(&m(4)), Verdict::Gap(2, 3));
        assert_eq!(r.accept(&m(5)), Verdict::Deliver);
        assert_eq!(r.accept(&edn::parse("{:op :x}").unwrap()), Verdict::Invalid);
    }
}

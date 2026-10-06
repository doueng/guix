use std::io::{BufRead, BufReader, Write};
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::{Arc, Mutex, mpsc};

use crate::core::CoreEvent;
use crate::edn::{self, Value};

#[derive(Clone, Debug)]
pub struct RuntimeOptions {
    pub bb: PathBuf,
    pub runtime_dir: PathBuf,
    pub config: PathBuf,
}

#[derive(Clone, Default)]
pub struct RuntimeLink(Arc<Mutex<Option<mpsc::Sender<String>>>>);

impl RuntimeLink {
    #[cfg(all(test, feature = "ui-tests"))]
    pub(crate) fn test_channel() -> (Self, mpsc::Receiver<String>) {
        let link = Self::default();
        let (tx, rx) = mpsc::channel();
        link.set(Some(tx));
        (link, rx)
    }

    pub fn send(&self, msg: &Value) {
        if let Some(tx) = self.0.lock().unwrap().as_ref() {
            let _ = tx.send(msg.to_string());
        }
    }

    pub fn event(&self, handler: &str, args: Vec<Value>) {
        self.send(&Value::map([
            ("op", Value::kw("event")),
            ("handler", Value::str(handler)),
            ("args", Value::Vector(args)),
        ]));
    }

    fn set(&self, tx: Option<mpsc::Sender<String>>) {
        *self.0.lock().unwrap() = tx;
    }

    pub fn connected(&self) -> bool {
        self.0.lock().unwrap().is_some()
    }
}

pub struct Runtime {
    child: Child,
}

impl Runtime {
    pub fn spawn(
        opts: &RuntimeOptions,
        link: &RuntimeLink,
        events: smol::channel::Sender<CoreEvent>,
    ) -> anyhow::Result<Runtime> {
        let mut classpath = vec![opts.runtime_dir.display().to_string()];
        if let Some(dir) = opts.config.parent() {
            classpath.push(dir.display().to_string());
        }
        let mut command = Command::new(&opts.bb);
        for var in crate::fonts::injected_env() {
            command.env_remove(var);
        }
        let mut child = command
            .arg("--classpath")
            .arg(classpath.join(":"))
            .args(["-m", "dusk.runtime"])
            .env("DUSK_CONFIG", &opts.config)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit())
            .spawn()
            .map_err(|e| anyhow::anyhow!("failed to start {}: {e}", opts.bb.display()))?;

        let mut stdin = child.stdin.take().expect("piped stdin");
        let stdout = child.stdout.take().expect("piped stdout");
        let (tx, rx) = mpsc::channel::<String>();
        link.set(Some(tx));

        std::thread::Builder::new()
            .name("dusk-bb-writer".into())
            .spawn(move || {
                for line in rx {
                    if stdin.write_all(line.as_bytes()).is_err()
                        || stdin.write_all(b"\n").is_err()
                        || stdin.flush().is_err()
                    {
                        break;
                    }
                }
            })?;

        let link_reader = link.clone();
        std::thread::Builder::new()
            .name("dusk-bb-reader".into())
            .spawn(move || {
                let reader = BufReader::new(stdout);
                for line in reader.lines() {
                    let Ok(line) = line else { break };
                    let line = line.trim();
                    if line.is_empty() {
                        continue;
                    }
                    match edn::parse(line) {
                        Ok(v) => {
                            if events.send_blocking(CoreEvent::FromRuntime(v)).is_err() {
                                return;
                            }
                        }
                        Err(e) => eprintln!("dusk: bad message from runtime ({e}): {line}"),
                    }
                }
                link_reader.set(None);
                let _ = events.send_blocking(CoreEvent::RuntimeExited);
            })?;

        Ok(Runtime { child })
    }

    pub fn kill(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }

    pub fn reap(&mut self) -> Option<std::process::ExitStatus> {
        self.child.try_wait().ok().flatten()
    }
}

impl Drop for Runtime {
    fn drop(&mut self) {
        self.kill();
    }
}

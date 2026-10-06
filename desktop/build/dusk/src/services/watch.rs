//! inotify watcher for the config directory. Both the config directory and
//! the directories the config files resolve to are watched, so Stow symlinks
//! into a repository still trigger reloads when the real file is saved.

use std::collections::BTreeSet;
use std::ffi::{CString, OsStr};
use std::os::unix::ffi::OsStrExt;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use crate::core::CoreEvent;

const DEBOUNCE: Duration = Duration::from_millis(60);

fn watch_dirs(config: &Path) -> BTreeSet<PathBuf> {
    let mut dirs = BTreeSet::new();
    let Some(dir) = config.parent() else {
        return dirs;
    };
    dirs.insert(dir.to_path_buf());
    if let Ok(entries) = std::fs::read_dir(dir) {
        for entry in entries.flatten() {
            let path = entry.path();
            if let Ok(real) = std::fs::canonicalize(&path)
                && let Some(parent) = real.parent()
            {
                dirs.insert(parent.to_path_buf());
            }
        }
    }
    if let Ok(real) = std::fs::canonicalize(config)
        && let Some(parent) = real.parent()
    {
        dirs.insert(parent.to_path_buf());
    }
    dirs
}

pub fn start(config: PathBuf, events: smol::channel::Sender<CoreEvent>) {
    std::thread::Builder::new()
        .name("dusk-watch".into())
        .spawn(move || {
            let fd = unsafe { libc::inotify_init1(libc::IN_CLOEXEC) };
            if fd < 0 {
                eprintln!("dusk: inotify unavailable; config hot reload disabled");
                return;
            }
            let mut wds = std::collections::HashMap::new();
            for dir in watch_dirs(&config) {
                let Ok(c) = CString::new(dir.as_os_str().as_bytes()) else {
                    continue;
                };
                let mask =
                    libc::IN_CLOSE_WRITE | libc::IN_MOVED_TO | libc::IN_CREATE | libc::IN_DELETE;
                let wd = unsafe { libc::inotify_add_watch(fd, c.as_ptr(), mask) };
                if wd >= 0 {
                    wds.insert(wd, dir);
                }
            }
            let mut buf = vec![0u8; 16 * 1024];
            let mut pending: BTreeSet<PathBuf> = BTreeSet::new();
            let mut deadline: Option<Instant> = None;
            loop {
                let timeout_ms = deadline
                    .map(|d| d.saturating_duration_since(Instant::now()).as_millis() as i32)
                    .unwrap_or(-1);
                let mut pfd = libc::pollfd {
                    fd,
                    events: libc::POLLIN,
                    revents: 0,
                };
                let ready = unsafe { libc::poll(&mut pfd, 1, timeout_ms) };
                if ready > 0 {
                    let n = unsafe { libc::read(fd, buf.as_mut_ptr().cast(), buf.len()) };
                    let mut offset = 0usize;
                    while n > 0 && offset + std::mem::size_of::<libc::inotify_event>() <= n as usize
                    {
                        // SAFETY: the loop condition keeps a whole event header inside the
                        // bytes read; the u8 buffer gives no alignment, hence read_unaligned.
                        let event = unsafe {
                            std::ptr::read_unaligned(
                                buf.as_ptr().add(offset) as *const libc::inotify_event
                            )
                        };
                        let name_start = offset + std::mem::size_of::<libc::inotify_event>();
                        let name_bytes = &buf[name_start..name_start + event.len as usize];
                        let name_len = name_bytes
                            .iter()
                            .position(|b| *b == 0)
                            .unwrap_or(name_bytes.len());
                        let name = OsStr::from_bytes(&name_bytes[..name_len]);
                        if let Some(dir) = wds.get(&event.wd)
                            && Path::new(name)
                                .extension()
                                .is_some_and(|e| e == "clj" || e == "edn")
                        {
                            pending.insert(dir.join(name));
                            deadline = Some(Instant::now() + DEBOUNCE);
                        }
                        offset = name_start + event.len as usize;
                    }
                } else if ready == 0 && !pending.is_empty() {
                    deadline = None;
                    let files: Vec<PathBuf> = std::mem::take(&mut pending).into_iter().collect();
                    if events
                        .send_blocking(CoreEvent::ConfigChanged(files))
                        .is_err()
                    {
                        return;
                    }
                } else if ready == 0 {
                    deadline = None;
                }
            }
        })
        .expect("spawn watcher thread");
}

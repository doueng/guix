//! Display backlight as the `:brightness` signal. The kernel emits a
//! `change` uevent for every backlight write (brightnessctl, logind, ...),
//! so we listen on the kobject-uevent netlink socket instead of polling.

use std::path::{Path, PathBuf};
use std::time::Duration;

use crate::core::CoreEvent;
use crate::edn::Value;

const KERNEL_UEVENT_GROUP: u32 = 1;

fn find_device() -> Option<PathBuf> {
    let mut devices: Vec<PathBuf> = std::fs::read_dir("/sys/class/backlight")
        .ok()?
        .flatten()
        .map(|e| e.path())
        .collect();
    devices.sort();
    devices.into_iter().next()
}

fn read_number(path: &Path) -> Option<f64> {
    std::fs::read_to_string(path).ok()?.trim().parse().ok()
}

fn read(device: &Path) -> Value {
    let (Some(current), Some(max)) = (
        read_number(&device.join("brightness")),
        read_number(&device.join("max_brightness")),
    ) else {
        return Value::Nil;
    };
    if max <= 0.0 {
        return Value::Nil;
    }
    Value::map([
        (
            "percent",
            Value::Int((current * 100.0 / max).round() as i64),
        ),
        (
            "device",
            Value::str(device.file_name().unwrap_or_default().to_string_lossy()),
        ),
    ])
}

fn open_uevent_socket() -> Option<libc::c_int> {
    unsafe {
        let fd = libc::socket(
            libc::AF_NETLINK,
            libc::SOCK_DGRAM | libc::SOCK_CLOEXEC,
            libc::NETLINK_KOBJECT_UEVENT,
        );
        if fd < 0 {
            return None;
        }
        let mut addr: libc::sockaddr_nl = std::mem::zeroed();
        addr.nl_family = libc::AF_NETLINK as u16;
        addr.nl_groups = KERNEL_UEVENT_GROUP;
        let rc = libc::bind(
            fd,
            &addr as *const libc::sockaddr_nl as *const libc::sockaddr,
            std::mem::size_of::<libc::sockaddr_nl>() as u32,
        );
        if rc < 0 {
            libc::close(fd);
            return None;
        }
        Some(fd)
    }
}

pub fn start(events: smol::channel::Sender<CoreEvent>) {
    let Some(device) = find_device() else {
        return;
    };
    std::thread::Builder::new()
        .name("dusk-backlight".into())
        .spawn(move || {
            let mut last = read(&device);
            if events
                .send_blocking(CoreEvent::Signal("brightness".into(), last.clone()))
                .is_err()
            {
                return;
            }
            let fd = open_uevent_socket();
            let mut buf = vec![0u8; 8192];
            loop {
                match fd {
                    Some(fd) => {
                        // SAFETY: buf is valid for buf.len() bytes.
                        let n = unsafe { libc::recv(fd, buf.as_mut_ptr().cast(), buf.len(), 0) };
                        if n <= 0 {
                            std::thread::sleep(Duration::from_millis(100));
                            continue;
                        }
                        let msg = &buf[..n as usize];
                        let is_backlight = msg
                            .split(|b| *b == 0)
                            .any(|field| field == b"SUBSYSTEM=backlight");
                        if !is_backlight {
                            continue;
                        }
                    }
                    None => std::thread::sleep(Duration::from_millis(500)),
                }
                let current = read(&device);
                if current != last {
                    last = current.clone();
                    if events
                        .send_blocking(CoreEvent::Signal("brightness".into(), current))
                        .is_err()
                    {
                        return;
                    }
                }
            }
        })
        .expect("spawn backlight thread");
}

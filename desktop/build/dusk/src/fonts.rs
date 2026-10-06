//! Font and icon discovery.
//!
//! GPUI's Linux text system (cosmic-text/fontdb) reads `/etc/fonts/fonts.conf`
//! directly, which does not exist on Guix. Before GPUI starts we ask the real
//! fontconfig (`fc-list`) where fonts live and hand fontdb a small generated
//! config listing those directories. Generic families such as `sans-serif`
//! are resolved with `fc-match` because GPUI matches family names literally.

use std::collections::{BTreeSet, HashMap};
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::{Mutex, OnceLock};

use crate::edn::Value;

static INJECTED: Mutex<Vec<&'static str>> = Mutex::new(Vec::new());

pub fn injected_env() -> Vec<&'static str> {
    INJECTED.lock().unwrap().clone()
}

/// Guix has no global Vulkan ICD directory, so point the loader at the
/// drivers of the profile dusk was built against.
pub fn configure_vulkan() {
    if std::env::var_os("VK_DRIVER_FILES").is_some()
        || std::env::var_os("VK_ICD_FILENAMES").is_some()
    {
        return;
    }
    let Some(profile) = option_env!("DUSK_PROFILE") else {
        return;
    };
    let icd = Path::new(profile).join("share/vulkan/icd.d");
    if icd.is_dir() {
        // SAFETY: called from main before GPUI or any service thread exists.
        unsafe { std::env::set_var("VK_DRIVER_FILES", &icd) };
        INJECTED.lock().unwrap().push("VK_DRIVER_FILES");
    }
}

fn runtime_dir() -> PathBuf {
    std::env::var_os("XDG_RUNTIME_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(std::env::temp_dir)
}

pub fn configure_fontconfig() {
    // Resolve the initial fallback before GPUI starts (not during rendering).
    load_family("sans-serif");
    if std::env::var_os("FONTCONFIG_FILE").is_some() || Path::new("/etc/fonts/fonts.conf").exists()
    {
        return;
    }
    let mut dirs = BTreeSet::new();
    if let Ok(out) = Command::new("fc-list")
        .args(["--format", "%{file}\n"])
        .output()
    {
        for line in String::from_utf8_lossy(&out.stdout).lines() {
            if let Some(parent) = Path::new(line).parent() {
                dirs.insert(parent.to_path_buf());
            }
        }
    }
    let data_dirs = std::env::var("XDG_DATA_DIRS").unwrap_or_default();
    for base in data_dirs.split(':').filter(|d| !d.is_empty()) {
        dirs.insert(Path::new(base).join("fonts"));
    }
    if let Some(home) = std::env::var_os("HOME") {
        dirs.insert(Path::new(&home).join(".local/share/fonts"));
    }
    let mut conf = String::from("<?xml version=\"1.0\"?>\n<fontconfig>\n");
    for dir in dirs.iter().filter(|d| d.is_dir()) {
        let escaped = dir
            .display()
            .to_string()
            .replace('&', "&amp;")
            .replace('<', "&lt;");
        conf.push_str(&format!("  <dir>{escaped}</dir>\n"));
    }
    conf.push_str("</fontconfig>\n");
    let path = runtime_dir().join("dusk-fonts.conf");
    if std::fs::write(&path, conf).is_ok() {
        // SAFETY: called from main before GPUI or any service thread exists.
        unsafe { std::env::set_var("FONTCONFIG_FILE", &path) };
        INJECTED.lock().unwrap().push("FONTCONFIG_FILE");
    }
}

fn family_cache() -> &'static Mutex<HashMap<String, String>> {
    static CACHE: OnceLock<Mutex<HashMap<String, String>>> = OnceLock::new();
    CACHE.get_or_init(Default::default)
}

fn generic_family(name: &str) -> bool {
    matches!(
        name,
        "sans-serif" | "sans" | "serif" | "monospace" | "mono" | "system-ui"
    )
}

/// Render-time lookup only. Discovery is done by `prepare` on a worker.
pub fn resolve_family(name: &str) -> String {
    if !generic_family(name) {
        return name.to_string();
    }
    let cache = family_cache().lock().unwrap();
    cache
        .get(name)
        .or_else(|| cache.get("sans-serif"))
        .cloned()
        .unwrap_or_else(|| "DejaVu Sans".to_string())
}

fn load_family(name: &str) {
    if !generic_family(name) || family_cache().lock().unwrap().contains_key(name) {
        return;
    }
    // Never hold the cache lock across I/O: render-time readers must not wait
    // for fc-match, or for a recursive icon search on another worker.
    let resolved = Command::new("fc-match")
        .args(["-f", "%{family[0]}", name])
        .output()
        .ok()
        .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "DejaVu Sans".to_string());
    family_cache()
        .lock()
        .unwrap()
        .insert(name.to_string(), resolved);
}

fn icon_cache() -> &'static Mutex<HashMap<String, Option<PathBuf>>> {
    static CACHE: OnceLock<Mutex<HashMap<String, Option<PathBuf>>>> = OnceLock::new();
    CACHE.get_or_init(Default::default)
}

fn icon_bases() -> Vec<PathBuf> {
    let mut bases = Vec::new();
    if let Some(home) = std::env::var_os("HOME") {
        bases.push(Path::new(&home).join(".local/share/icons"));
    }
    let data_dirs = std::env::var("XDG_DATA_DIRS")
        .unwrap_or_else(|_| "/run/current-system/profile/share:/usr/share".into());
    for base in data_dirs.split(':').filter(|d| !d.is_empty()) {
        bases.push(Path::new(base).join("icons"));
    }
    bases
}

fn find_in(dir: &Path, file: &str, depth: usize) -> Option<PathBuf> {
    let candidate = dir.join(file);
    if candidate.is_file() {
        return Some(candidate);
    }
    if depth == 0 {
        return None;
    }
    let mut subdirs: Vec<PathBuf> = std::fs::read_dir(dir)
        .ok()?
        .flatten()
        .map(|e| e.path())
        .filter(|p| p.is_dir())
        .collect();
    let is_vector_dir = |p: &PathBuf| {
        let s = p.to_string_lossy();
        s.contains("symbolic") || s.contains("scalable")
    };
    subdirs.sort_by_key(|p| !is_vector_dir(p));
    subdirs.iter().find_map(|d| find_in(d, file, depth - 1))
}

pub fn resolve_icon(name: &str) -> Option<PathBuf> {
    if name.starts_with('/') {
        return Some(PathBuf::from(name));
    }
    if let Some(rest) = name.strip_prefix("~/")
        && let Some(home) = std::env::var_os("HOME")
    {
        return Some(Path::new(&home).join(rest));
    }
    icon_cache().lock().unwrap().get(name).cloned().flatten()
}

fn load_icon(name: &str) {
    if name.starts_with('/')
        || name.starts_with("~/")
        || icon_cache().lock().unwrap().contains_key(name)
    {
        return;
    }
    let file = format!("{name}.svg");
    let found = icon_bases().iter().find_map(|base| {
        ["Adwaita", "hicolor"]
            .iter()
            .find_map(|theme| find_in(&base.join(theme), &file, 2))
    });
    icon_cache().lock().unwrap().insert(name.to_string(), found);
}

/// Collect referenced names without touching the filesystem or running commands.
/// Cached names are skipped when this work runs on GPUI's background executor.
#[derive(Clone, Default, PartialEq, Debug)]
pub struct Assets {
    families: BTreeSet<String>,
    icons: BTreeSet<String>,
}

impl Assets {
    pub fn for_tree(tree: &Value) -> Self {
        fn collect(value: &Value, assets: &mut Assets) {
            if let Some(name) = value.get("font-family").and_then(Value::as_name) {
                assets.families.insert(name.to_string());
            }
            if value.get("type").and_then(Value::as_name) == Some("icon")
                && let Some(name) = value
                    .get("props")
                    .and_then(|p| p.get("name"))
                    .and_then(Value::as_name)
            {
                assets.icons.insert(name.to_string());
            }
            match value {
                Value::Map(entries) => {
                    for (_, value) in entries {
                        collect(value, assets);
                    }
                }
                Value::Vector(items) | Value::List(items) | Value::Set(items) => {
                    for value in items {
                        collect(value, assets);
                    }
                }
                _ => {}
            }
        }
        let mut assets = Self::default();
        assets.families.insert("sans-serif".into());
        collect(tree, &mut assets);
        assets
    }

    pub fn prepare(self) {
        for name in self.families {
            load_family(&name);
        }
        for name in self.icons {
            load_icon(&name);
        }
    }
}

pub fn expand_home(path: &str) -> PathBuf {
    match (path.strip_prefix("~/"), std::env::var_os("HOME")) {
        (Some(rest), Some(home)) => Path::new(&home).join(rest),
        _ => PathBuf::from(path),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn collects_fonts_in_hover_styles_and_nested_icons() {
        let tree = crate::edn::parse(
            r#"{:type :layer :props {:font-family "serif"}
            :children [{:type :box :props {:hover {:font-family "mono"}}
                        :children [{:type :icon :props {:name "audio-volume-high-symbolic"}}]}]}"#,
        )
        .unwrap();
        let assets = Assets::for_tree(&tree);
        assert_eq!(
            assets.families,
            BTreeSet::from(["sans-serif".into(), "serif".into(), "mono".into()])
        );
        assert_eq!(
            assets.icons,
            BTreeSet::from(["audio-volume-high-symbolic".into()])
        );
    }

    #[test]
    fn render_lookup_does_not_populate_discovery_cache() {
        // If resolve_icon still searched the filesystem, even a failed lookup
        // would populate its negative cache. The render path must do neither.
        let name = "dusk-test-nonexistent-render-only-icon";
        assert_eq!(resolve_icon(name), None);
        assert!(!icon_cache().lock().unwrap().contains_key(name));
        assert_eq!(
            resolve_family("A Literal Font Family"),
            "A Literal Font Family"
        );
    }
}

//! `rexmux probe`: connect like the window does, show the focused pane of
//! the first session once a frame arrives, and exit. The live tests use it to
//! check the whole path (control, data.connect, snapshot, stream, render)
//! without a display.

use std::sync::mpsc;
use std::time::{Duration, Instant};

use crate::app::{App, AppEvent};
use crate::screen;

pub fn run(servers: Vec<(String, Option<String>)>, args: &[String]) -> i32 {
    let (tx, rx) = mpsc::channel();
    let mut app = App::new(servers, "rexmux-probe", tx);
    app.handle(AppEvent::Resize(100, 27));
    let deadline = Instant::now() + Duration::from_secs(15);
    while Instant::now() < deadline {
        if let Ok(ev) = rx.recv_timeout(Duration::from_millis(50)) {
            app.handle(ev);
        }
        let ready = app.model.focused_block().is_some_and(|b| app.grids.get(&b).is_some_and(|g| !g.text().trim().is_empty()))
            && !app.model.catalogue.is_empty();
        if ready {
            let fb = app.model.focused_block().unwrap();
            if args.iter().any(|a| a == "--text") {
                println!("{}", app.grids[&fb].text());
            } else {
                for l in screen::render(&app.model, &app.grids, 100, 30).text() {
                    println!("{l}");
                }
                println!("commands {}", app.model.catalogue.len());
            }
            return 0;
        }
    }
    eprintln!(
        "rexmux probe: no frame within 15 s (echo: {}; client {:?}; sessions {:?}; view {:?}; streams {:?}; grids {:?}; commands {}; sidecar {})",
        app.model.echo,
        app.model.server().client_id,
        app.model.server().sessions,
        app.model.view().map(|v| (&v.session, v.visible())),
        app.model.streams,
        app.grids.keys().collect::<Vec<_>>(),
        app.model.catalogue.len(),
        app.model.sidecar_up
    );
    1
}

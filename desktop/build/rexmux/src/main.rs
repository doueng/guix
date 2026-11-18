fn main() {
    let mut term = libghostty_vt::terminal::Terminal::new(40, 3).expect("terminal");
    term.vt_write(b"probe");
    println!("ok");
}

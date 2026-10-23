// Standalone release benchmark for the exact EDN wire messages Emacs sends.
#[path = "src/edn.rs"]
mod edn;
use std::{env, fs, hint::black_box, time::Instant};
fn main() {
    println!("file\tparse_ms\tforward_encode_ms\tentries");
    for path in env::args().skip(1) {
        let wire = fs::read_to_string(&path).unwrap();
        let start = Instant::now();
        let value = edn::parse(black_box(&wire)).unwrap();
        let parse_ms = start.elapsed().as_secs_f64() * 1000.0;
        let count = value
            .get("args")
            .unwrap()
            .get("entries")
            .unwrap()
            .items()
            .len();
        assert!(count > 1000, "expected a real command catalogue");
        let start = Instant::now();
        let forwarded = black_box(&value).to_string();
        let encode_ms = start.elapsed().as_secs_f64() * 1000.0;
        assert_eq!(edn::parse(&forwarded).unwrap(), value);
        println!("{path}\t{parse_ms:.6}\t{encode_ms:.6}\t{count}");
    }
}

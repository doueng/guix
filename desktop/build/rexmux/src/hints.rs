//! Tiny fingers: hints over what a pane shows, as Herdr's tiny-fingers
//! plugin had them (desktop/build/herdr/tiny-fingers/finger.bb, Ctrl+b f and
//! Super+Y). URLs, UUIDs, hashes, paths, jj change IDs and [bookmarks] get a
//! label from the home row; typing one copies the text, and a web URL also
//! opens. The patterns and labels are the plugin's, so muscle memory carries
//! over.

use regex::Regex;
use std::sync::OnceLock;

pub const ALPHABET: &str = "asdfqwerzxcvjklmiuopghtybn";

#[derive(Clone, Debug, PartialEq)]
pub struct Target {
    /// Row and column of the first cell of the match on the pane's screen.
    pub row: usize,
    pub col: usize,
    /// The text to copy, with soft wraps joined.
    pub text: String,
    pub hint: String,
}

struct Pattern {
    re: Regex,
    /// Matches may continue across a soft-wrapped line.
    wrap: bool,
    /// Copy the first group instead of the whole match.
    group: bool,
    /// Only a match with no letter or digit on either side counts.
    alone: bool,
}

fn patterns() -> &'static [Pattern] {
    static P: OnceLock<Vec<Pattern>> = OnceLock::new();
    P.get_or_init(|| {
        let p = |re: &str, wrap, group, alone| Pattern { re: Regex::new(re).unwrap(), wrap, group, alone };
        vec![
            p(r#"(?:https?://|git@|git://|ssh://|ftp://|file:///)[^\s()"']+"#, true, false, false),
            p(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", true, false, false),
            p(r"[0-9a-f]{7,128}", true, false, false),
            p(r"(?:[.\w~$@-]+)?(?:/[.\w@-]+)+/?", true, false, false),
            // Jujutsu change IDs use the letters k to z, unlike commit hashes.
            p(r"[k-z]{8,32}", false, false, true),
            p(r"\[([^\[\]\r\n]+)\]", false, true, false),
        ]
    })
}

struct Hit {
    index: usize,
    len: usize,
    row: usize,
    col: usize,
    text: String,
}

/// The text of LINES with rows that fill WIDTH joined to the next one (a soft
/// wrap), and each character's (row, col); None marks an inserted newline.
fn flatten(lines: &[String], width: usize) -> (Vec<char>, Vec<Option<(usize, usize)>>) {
    let mut text = Vec::new();
    let mut pos = Vec::new();
    for (row, line) in lines.iter().enumerate() {
        let join = row > 0 && lines[row - 1].chars().count() >= width;
        if row > 0 && !join {
            text.push('\n');
            pos.push(None);
        }
        for (col, c) in line.chars().enumerate() {
            text.push(c);
            pos.push(Some((row, col)));
        }
    }
    (text, pos)
}

fn hits(lines: &[String], width: usize) -> Vec<Hit> {
    let (flat, flat_pos) = flatten(lines, width);
    let mut out = Vec::new();
    for p in patterns() {
        let sources: Vec<(Vec<char>, Vec<Option<(usize, usize)>>)> = if p.wrap {
            vec![(flat.clone(), flat_pos.clone())]
        } else {
            lines.iter().enumerate().map(|(row, l)| (l.chars().collect(), (0..l.chars().count()).map(|c| Some((row, c))).collect())).collect()
        };
        for (chars, pos) in sources {
            let s: String = chars.iter().collect();
            // Regex offsets are bytes; map them to character indexes.
            let ci = |b: usize| s[..b].chars().count();
            for caps in p.re.captures_iter(&s) {
                let whole = caps.get(0).unwrap();
                let (start, end) = (ci(whole.start()), ci(whole.end()));
                if p.alone {
                    let before = start.checked_sub(1).and_then(|i| chars.get(i)).is_some_and(|c| c.is_ascii_alphanumeric());
                    let after = chars.get(end).is_some_and(|c| c.is_ascii_alphanumeric());
                    if before || after {
                        continue;
                    }
                }
                let copy = if p.group { caps.get(1).unwrap() } else { whole };
                let (Some(Some((row, col))), Some(Some(_))) = (pos.get(start), pos.get(end.saturating_sub(1))) else { continue };
                if start >= end {
                    continue;
                }
                let index = lines[..*row].iter().map(|l| l.chars().count() + 1).sum::<usize>() + col;
                out.push(Hit { index, len: end - start, row: *row, col: *col, text: copy.as_str().replace('\n', "") });
            }
        }
    }
    out
}

/// The hint for target N of a set needing DIGITS letters each.
pub fn hint(mut n: usize, digits: usize) -> String {
    let a: Vec<char> = ALPHABET.chars().collect();
    let mut s = vec![' '; digits];
    for i in (0..digits).rev() {
        s[i] = a[n % a.len()];
        n /= a.len();
    }
    s.into_iter().collect()
}

/// Every target on a screen of LINES whose soft-wrap width is WIDTH, in screen
/// order, a match inside an earlier, longer one dropped, each with its hint.
pub fn targets(lines: &[String], width: usize) -> Vec<Target> {
    let mut hs = hits(lines, width);
    hs.sort_by_key(|h| (h.index, std::cmp::Reverse(h.len)));
    let mut kept: Vec<Hit> = Vec::new();
    for h in hs {
        if !kept.iter().any(|k| k.index <= h.index && h.index < k.index + k.len) {
            kept.push(h);
        }
    }
    let n = kept.len();
    let mut digits = 1;
    let mut capacity = ALPHABET.len();
    while capacity < n {
        digits += 1;
        capacity *= ALPHABET.len();
    }
    kept.into_iter().enumerate().map(|(i, h)| Target { row: h.row, col: h.col, text: h.text, hint: hint(i, digits) }).collect()
}

/// A web URL, which picking also opens in the browser.
pub fn web_url(text: &str) -> bool {
    (text.starts_with("http://") || text.starts_with("https://")) && !text.contains(|c: char| c.is_whitespace() || "'\"()".contains(c))
}

/// What typing INPUT so far picks among TARGETS: Some(target) when exactly one
/// hint equals it, None while more could follow; Err when nothing matches.
pub fn step<'a>(targets: &'a [Target], input: &str) -> Result<Option<&'a Target>, ()> {
    let matching: Vec<&Target> = targets.iter().filter(|t| t.hint.starts_with(input)).collect();
    match matching.as_slice() {
        [] => Err(()),
        [one] if one.hint == input => Ok(Some(one)),
        _ => Ok(None),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn lines(s: &[&str]) -> Vec<String> {
        s.iter().map(|l| l.to_string()).collect()
    }

    #[test]
    fn finds_the_plugins_kinds_in_screen_order() {
        let t = targets(&lines(&[
            "see https://example.com/a?b=1 and /home/u/guix/Makefile",
            "commit 3f2a9c1d change kmnoprst [main] id 123e4567-e89b-12d3-a456-426614174000",
        ]), 200);
        let texts: Vec<&str> = t.iter().map(|t| t.text.as_str()).collect();
        assert_eq!(texts, vec!["https://example.com/a?b=1", "/home/u/guix/Makefile", "3f2a9c1d", "kmnoprst", "main",
                               "123e4567-e89b-12d3-a456-426614174000"]);
        assert_eq!((t[1].row, t[1].col), (0, 34));
        assert_eq!(t[0].hint, "a");
        assert_eq!(t[1].hint, "s");
    }

    #[test]
    fn a_url_continues_across_a_soft_wrap() {
        let t = targets(&lines(&["x https://exa", "mple.com/p"]), 13);
        assert_eq!(t[0].text, "https://example.com/p");
    }

    #[test]
    fn a_change_id_inside_a_word_is_not_one() {
        assert!(targets(&lines(&["Xkmnoprstu"]), 80).is_empty());
    }

    #[test]
    fn many_targets_get_two_letter_hints_and_typing_picks_one() {
        let many: Vec<String> = (0..30).map(|i| format!("/tmp/f{i}")).collect();
        let t = targets(&many, 80);
        assert_eq!(t.len(), 30);
        assert!(t.iter().all(|t| t.hint.len() == 2));
        assert_eq!(step(&t, "a"), Ok(None));
        assert_eq!(step(&t, "as").unwrap().unwrap().text, "/tmp/f1");
        assert_eq!(step(&t, "zz"), Err(()));
    }

    #[test]
    fn web_urls_open_and_others_do_not() {
        assert!(web_url("https://x.org/a"));
        assert!(!web_url("git@github.com:a/b"));
    }
}

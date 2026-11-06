//! Keeps a server's config.yml current across upgrades (Java's `ConfigUpgrade`): adds, with their comments, what the
//! bundled config.yml has and theirs lacks. Whole top-level sections go at the end of the file; keys one level down
//! go at the end of their section, indented like the user's. Nothing they wrote is rewritten or reordered.

use std::collections::{BTreeMap, HashSet};
use std::io;
use std::path::Path;

/// Their section: where it ends (offset after its last indented line), whether it's a scalar, its keys' indent and
/// which keys it has.
pub struct Block {
    pub end: usize,
    pub scalar: bool,
    pub indent: usize,
    pub keys: HashSet<String>,
}

/// Adds what `file` lacks from `bundled`; returns what was added, e.g. "presence" (empty: unchanged).
pub fn add_missing_sections(file: &Path, bundled: &str) -> io::Result<Vec<String>> {
    let current = std::fs::read_to_string(file)?;
    let Some((text, names)) = upgrade(&current, bundled) else { return Ok(Vec::new()) };
    write(file, &text)?;
    Ok(names)
}

/// The upgraded text and what was added, or none if nothing is missing.
pub fn upgrade(current: &str, bundled: &str) -> Option<(String, Vec<String>)> {
    let nl = if current.contains("\r\n") { "\r\n" } else { "\n" };
    let theirs = blocks(current);
    let mut names = Vec::new();
    let mut inserts: BTreeMap<usize, String> = BTreeMap::new();
    let mut appended = String::new();
    for (key, section) in sections(bundled) {
        let Some((_, mine)) = theirs.iter().find(|(k, _)| *k == key) else {
            names.push(key);
            appended.push('\n');
            appended.push_str(&section);
            continue;
        };
        if mine.scalar {
            continue; // "key: value" or "key: {...}": nothing can go under it
        }
        for (child, text) in children(&section) {
            if mine.keys.contains(&child) {
                continue;
            }
            names.push(format!("{key}.{child}"));
            inserts.entry(mine.end).or_default().push_str(&reindent(&text, mine.indent));
        }
    }
    if names.is_empty() {
        return None;
    }
    let mut text = current.to_owned();
    for (at, insert) in inserts.iter().rev() {
        let line_start = *at == 0 || text.as_bytes()[at - 1] == b'\n';
        let insert = format!("{}{}", if line_start { "" } else { nl }, insert.replace('\n', nl));
        text.insert_str(*at, &insert);
    }
    if !appended.is_empty() {
        if !text.is_empty() && !text.ends_with('\n') {
            text.push_str(nl);
        }
        text.push_str(&appended.replace('\n', nl));
    }
    Some((text, names))
}

/// Replaces the file in one step (a crash mid-write must not leave a truncated config), through symbolic links where
/// the platform can resolve them.
fn write(file: &Path, text: &str) -> io::Result<()> {
    let target = std::fs::canonicalize(file).unwrap_or_else(|_| file.to_path_buf());
    let mut tmp = target.clone().into_os_string();
    tmp.push(".tmp");
    std::fs::write(&tmp, text)?;
    std::fs::rename(&tmp, &target)
}

fn top_level_key(line: &str) -> Option<(&str, usize)> {
    let end = line.find(|c: char| !(c.is_ascii_alphanumeric() || c == '_' || c == '-')).unwrap_or(line.len());
    (end > 0 && line[end..].starts_with(':')).then(|| (&line[..end], end + 1))
}

/// `( +)([A-Za-z0-9_-]+):` at the start of the line: (indent, key).
fn child_key(line: &str) -> Option<(usize, &str)> {
    let indent = line.len() - line.trim_start_matches(' ').len();
    if indent == 0 {
        return None;
    }
    top_level_key(&line[indent..]).map(|(key, _)| (indent, key))
}

fn is_blank(line: &str) -> bool {
    line.chars().all(char::is_whitespace)
}

/// Each top-level key's lines, with the column-0 comments just above it, in file order.
pub fn sections(yaml: &str) -> Vec<(String, String)> {
    let mut sections: Vec<(String, String)> = Vec::new();
    let mut pending = String::new(); // comments and blank lines: they belong to the next key
    let mut current: Option<usize> = None;
    for line in yaml.lines() {
        if let Some((key, _)) = top_level_key(line) {
            let text = format!("{}{line}\n", strip(&pending));
            pending.clear();
            let at = match sections.iter().position(|(k, _)| k == key) {
                Some(i) => {
                    sections[i].1 = text;
                    i
                }
                None => {
                    sections.push((key.to_owned(), text));
                    sections.len() - 1
                }
            };
            current = Some(at);
        } else if is_blank(line) || line.starts_with('#') {
            pending.push_str(line);
            pending.push('\n');
        } else if let Some(i) = current {
            sections[i].1.push_str(&pending);
            sections[i].1.push_str(line);
            sections[i].1.push('\n');
            pending.clear();
        }
    }
    sections
}

/// A bundled section's keys one level down (indented 2), each with the indented comments just above it.
pub fn children(section: &str) -> Vec<(String, String)> {
    let mut children: Vec<(String, String)> = Vec::new();
    let mut pending = String::new();
    let mut current: Option<usize> = None;
    for line in section.lines() {
        if is_blank(line) {
            pending.push('\n');
        } else if !line.starts_with(' ') {
            pending.clear(); // the section's own key, or the comments above it
        } else if let Some((2, key)) = child_key(line) {
            let text = format!("{}{line}\n", strip(&pending));
            pending.clear();
            match children.iter().position(|(k, _)| k == key) {
                Some(i) => {
                    children[i].1 = text;
                    current = Some(i);
                }
                None => {
                    children.push((key.to_owned(), text));
                    current = Some(children.len() - 1);
                }
            }
        } else if line.trim_start().starts_with('#') {
            pending.push_str(line);
            pending.push('\n');
        } else if let Some(i) = current {
            children[i].1.push_str(&pending);
            children[i].1.push_str(line);
            children[i].1.push('\n');
            pending.clear();
        }
    }
    children
}

/// The user's top-level sections, read line by line with their byte offsets ('\r' kept out of the matching). The
/// first occurrence of a key wins.
pub fn blocks(yaml: &str) -> Vec<(String, Block)> {
    let mut blocks: Vec<(String, Block)> = Vec::new();
    let mut key: Option<String> = None;
    let mut end = 0;
    let mut indent: Option<usize> = None;
    let mut scalar = false;
    let mut keys = HashSet::new();
    let close = |blocks: &mut Vec<(String, Block)>, key: String, end, scalar, indent: Option<usize>, keys| {
        if !blocks.iter().any(|(k, _)| *k == key) {
            blocks.push((key, Block { end, scalar, indent: indent.unwrap_or(2), keys }));
        }
    };
    let mut pos = 0;
    while pos < yaml.len() {
        let eol = yaml[pos..].find('\n').map(|i| pos + i);
        let next = eol.map_or(yaml.len(), |e| e + 1);
        let line = yaml[pos..eol.unwrap_or(yaml.len())].replace('\r', "");
        if let Some((k, after)) = top_level_key(&line) {
            if let Some(previous) = key.take() {
                close(&mut blocks, previous, end, scalar, indent, std::mem::take(&mut keys));
            }
            key = Some(k.to_owned());
            end = next;
            indent = None;
            let value = line[after..].trim();
            scalar = !value.is_empty() && !value.starts_with('#');
            keys = HashSet::new();
        } else if key.is_some() && line.starts_with(' ') && !is_blank(&line) {
            end = next;
            let rest = line.trim_start();
            if !rest.starts_with('#') {
                let lead = line.len() - rest.len();
                let step = *indent.get_or_insert(lead);
                if let Some((child_indent, child)) = child_key(&line)
                    && lead == step
                    && child_indent == lead
                {
                    keys.insert(child.to_owned());
                }
            }
        }
        pos = next;
    }
    if let Some(previous) = key {
        close(&mut blocks, previous, end, scalar, indent, keys);
    }
    blocks
}

/// Bundled lines are indented by 2 per level: use the user's step instead.
fn reindent(text: &str, step: usize) -> String {
    if step == 2 {
        return text.to_owned();
    }
    let mut out = String::new();
    for line in text.lines() {
        let rest = line.trim_start();
        let lead = line.len() - rest.len();
        out.push_str(&" ".repeat(lead / 2 * step));
        out.push_str(rest);
        out.push('\n');
    }
    out
}

/// Drops the blank lines before a section's comment (they separated it from the previous one).
fn strip(comment: &str) -> &str {
    let mut rest = comment;
    while let Some(nl) = rest.find('\n') {
        if rest[..nl].chars().all(char::is_whitespace) {
            rest = &rest[nl + 1..];
        } else {
            break;
        }
    }
    rest
}

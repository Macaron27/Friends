//! Human-readable "last seen" times and presence lines (Java's `com.friends.api.LastSeen`).

use crate::model::{Millis, PlayerActivity};

const SIZES: [i64; 5] = [60, 60, 24, 30, 12];
const UNITS: [&str; 5] = ["minute", "hour", "day", "month", "year"];
// Emoji for outside the game only; chat in game uses a coloured "●".
const ONLINE: &str = "\u{1F7E2} ";
const OFFLINE: &str = "\u{26AB} ";
const DASH: &str = " \u{2014} ";

/// How long ago `then` was, in its largest whole unit (a month counts 30 days): "just now" under a minute (or for
/// a future `then`), else e.g. "1 minute ago", "17 minutes ago", "3 days ago", "2 years ago".
pub fn ago(then: Millis, now: Millis) -> String {
    // Java's Duration.between(...).getSeconds(): floor division.
    let mut n = now.saturating_sub(then).div_euclid(1000).max(0);
    if n < 60 {
        return "just now".to_owned();
    }
    let mut unit: isize = -1;
    while ((unit + 1) as usize) < UNITS.len() && n >= SIZES[(unit + 1) as usize] {
        unit += 1;
        n /= SIZES[unit as usize];
    }
    let unit = UNITS[unit as usize];
    format!("{n} {unit}{} ago", if n == 1 { "" } else { "s" })
}

/// "Last seen 17 minutes ago", "Last seen just now".
pub fn format(then: Millis, now: Millis) -> String {
    format!("Last seen {}", ago(then, now))
}

/// "🟢 Online", or e.g. "⚫ Last seen 17 minutes ago" ("⚫ Offline" without a last-seen time).
pub fn presence(online: bool, last_seen: Option<Millis>, now: Millis) -> String {
    if online {
        return format!("{ONLINE}Online");
    }
    match last_seen {
        Some(seen) => format!("{OFFLINE}{}", format(seen, now)),
        None => format!("{OFFLINE}Offline"),
    }
}

/// Rich presence like a line of `/f list`: "🟢 Bob — Playing BedWars Solo", "🟢 Bob — Online",
/// "⚫ Bob — Last seen 3 days ago".
pub fn line(
    name: &str,
    online: bool,
    activity: Option<&PlayerActivity>,
    last_seen: Option<Millis>,
    now: Millis,
) -> String {
    if online {
        let what = activity.map_or_else(|| "Online".to_owned(), PlayerActivity::describe);
        return format!("{ONLINE}{name}{DASH}{what}");
    }
    let when = last_seen.map_or_else(|| "Offline".to_owned(), |seen| format(seen, now));
    format!("{OFFLINE}{name}{DASH}{when}")
}

//! Hypixel-style chat output (Java's `Messages`). Each MiniMessage template of the Java plugin is built here as the
//! same tree of styled spans, so styles inherit the same way (e.g. a name without a colour of its own takes the
//! colour of the span around it). Player-typed text is only ever plain text.

use crate::last_seen;
use crate::model::{Friend, Millis, PlayerActivity, PlayerRow, Request, Status};
use crate::text::{Color, Text};

pub const PAGE_SIZE: usize = 10;

/// A line of `/f list`: `status` none = offline (or appearing offline); `server`/`activity` none when unknown.
pub struct Entry {
    pub friend: Friend,
    pub status: Option<Status>,
    pub server: Option<String>,
    pub activity: Option<PlayerActivity>,
}

// --- building blocks ---

fn t(s: impl Into<String>) -> Text {
    Text::plain(s)
}

fn c(color: Color, s: impl Into<String>) -> Text {
    Text::plain(s).color(color)
}

/// A MiniMessage `<color>` that stays open: everything inside inherits the colour.
fn span(color: Color, parts: impl IntoIterator<Item = Text>) -> Text {
    Text::empty().color(color).children(parts)
}

fn root(parts: impl IntoIterator<Item = Text>) -> Text {
    Text::empty().children(parts)
}

fn line() -> Text {
    c(Color::Blue, "-".repeat(53)).strikethrough()
}

pub fn chat_box(lines: impl IntoIterator<Item = Text>) -> Text {
    let mut b = Text::empty().child(line());
    for l in lines {
        b = b.child(t("\n")).child(l);
    }
    b.child(t("\n")).child(line())
}

/// Rank prefix + name. Names and nicknames can't contain '&', so legacy parsing is safe.
pub fn name(prefix: Option<&str>, name: &str) -> Text {
    match prefix {
        Some(p) if !p.trim().is_empty() => Text::legacy(format!("{}{name}", p.replace('§', "&"))),
        _ => c(Color::Gray, name),
    }
}

pub fn row_name(p: &PlayerRow) -> Text {
    name(p.prefix.as_deref(), &p.name)
}

/// How the owner sees a friend: their private nickname if set.
pub fn friend_name(f: &Friend) -> Text {
    name(f.prefix.as_deref(), f.nickname.as_deref().unwrap_or(&f.name))
}

fn button(label: &str, color: Color, command: impl Into<String>, hover: &str) -> Text {
    c(color, label).bold().run_command(command).hover(c(Color::Gray, hover))
}

fn help_line(command: &str, what: &str) -> Text {
    span(Color::Yellow, [t(format!("{command} ")), span(Color::Gray, [t("- "), c(Color::Aqua, what)])])
}

// --- command replies ---

/// `private_messages`: with `/msg` and `/r`.
pub fn help(private_messages: bool) -> Text {
    let mut lines = vec![
        c(Color::Green, "Friend Commands:"),
        help_line("/f add <player>", "Send a friend request"),
        help_line("/f accept <player>", "Accept a friend request"),
        help_line("/f deny <player>", "Decline a friend request"),
        help_line("/f list [best] [page]", "List your friends"),
        help_line("/f requests", "View pending friend requests"),
        help_line("/f remove <player>", "Remove a friend"),
        help_line("/f best <player>", "Toggle best friend"),
        help_line("/f nickname <player> [nickname]", "Set a nickname only you can see"),
        help_line("/f removeall", "Remove all friends except best friends"),
        help_line("/f notifications", "Toggle friend join/leave messages"),
        help_line("/f ignore [player]", "Ignore a player (alone: list who you ignore)"),
        help_line("/f unignore <player>", "Stop ignoring a player"),
        help_line("/status [online|away|busy|offline]", "Set your online status"),
    ];
    if private_messages {
        lines.push(help_line("/msg <friend> <message>", "Send a friend a private message"));
        lines.push(help_line("/r <message>", "Reply to your last private message"));
    }
    chat_box(lines)
}

pub fn usage(usage: &str) -> Text {
    chat_box([span(Color::Red, [t("Usage: "), t(usage)])])
}

pub fn error() -> Text {
    chat_box([c(Color::Red, "Something went wrong, please try again later.")])
}

pub fn not_loaded() -> Text {
    chat_box([c(Color::Red, "Your friends data isn't loaded yet, please try again in a moment.")])
}

pub fn not_found(input: &str) -> Text {
    chat_box([span(Color::Red, [t("Can't find a player by the name of '"), t(input), t("'")])])
}

pub fn add_self() -> Text {
    chat_box([c(Color::Red, "You can't add yourself as a friend!")])
}

pub fn already_friends(p: Text) -> Text {
    chat_box([span(Color::Red, [t("You're already friends with "), p, t("!")])])
}

pub fn already_sent(p: Text) -> Text {
    chat_box([span(Color::Red, [t("You've already sent a friend request to "), p, t("!")])])
}

pub fn limit_self(max: usize) -> Text {
    chat_box([c(Color::Red, format!("You can't have more than {max} friends!"))])
}

pub fn limit_other(p: Text) -> Text {
    chat_box([root([p, t(" "), c(Color::Red, "has reached the maximum number of friends!")])])
}

pub fn sent(p: Text, minutes: i64) -> Text {
    chat_box([span(
        Color::Yellow,
        [t("You sent a friend request to "), p, t(format!("! They have {minutes} minutes to accept it!"))],
    )])
}

pub fn received(from: Text, from_name: &str) -> Text {
    chat_box([
        span(Color::Yellow, [t("Friend request from "), from]),
        root([
            button("[ACCEPT]", Color::Green, format!("/f accept {from_name}"), "Click to accept the friend request"),
            c(Color::DarkGray, " - "),
            button("[DENY]", Color::Red, format!("/f deny {from_name}"), "Click to deny the friend request"),
        ]),
    ])
}

pub fn now_friends(p: Text) -> Text {
    chat_box([span(Color::Green, [t("You are now friends with "), p])])
}

pub fn no_request(input: &str) -> Text {
    chat_box([span(Color::Red, [t("You don't have a friend request from '"), t(input), t("'!")])])
}

pub fn declined(p: Text) -> Text {
    chat_box([span(Color::Yellow, [t("Declined "), p, t("'s friend request!")])])
}

pub fn expired_outgoing(p: Text) -> Text {
    chat_box([span(Color::Yellow, [t("Your friend request to "), p, t(" has expired.")])])
}

pub fn expired_incoming(p: Text) -> Text {
    chat_box([span(Color::Yellow, [t("The friend request from "), p, t(" has expired.")])])
}

pub fn not_friend(input: &str) -> Text {
    chat_box([span(Color::Red, [t("'"), t(input), t("' isn't on your friends list!")])])
}

pub fn removed(p: Text) -> Text {
    chat_box([span(Color::Yellow, [t("You removed "), p, t(" from your friends list!")])])
}

pub fn best(p: Text, best: bool) -> Text {
    chat_box([if best {
        root([p, t(" "), c(Color::Green, "is now one of your best friends!")])
    } else {
        root([p, t(" "), c(Color::Yellow, "is no longer one of your best friends.")])
    }])
}

pub fn nickname(p: Text, nickname: Option<&str>) -> Text {
    chat_box([match nickname {
        None => span(Color::Yellow, [t("Cleared your nickname for "), p, t(".")]),
        Some(nick) => span(Color::Green, [t("You'll now see "), p, t(" as "), c(Color::Yellow, nick), t(".")]),
    }])
}

pub fn invalid_nickname() -> Text {
    chat_box([c(Color::Red, "Nicknames must be 1-16 letters, digits, spaces or underscores.")])
}

pub fn remove_all_confirm(count: usize) -> Text {
    chat_box([
        span(
            Color::Yellow,
            [t("This will remove "), c(Color::Red, count.to_string()), t(" friends (best friends are kept).")],
        ),
        button("[CONFIRM]", Color::Red, "/f removeall confirm", "Click to remove them"),
    ])
}

pub fn removed_all(count: usize) -> Text {
    chat_box([c(Color::Yellow, format!("Removed {count} friends from your friends list."))])
}

pub fn nothing_to_remove() -> Text {
    chat_box([c(Color::Yellow, "You don't have any friends to remove (best friends are kept).")])
}

pub fn notifications(enabled: bool) -> Text {
    chat_box([if enabled {
        c(Color::Green, "Enabled friend join/leave notifications.")
    } else {
        c(Color::Yellow, "Disabled friend join/leave notifications.")
    }])
}

fn status_color(status: Status) -> Color {
    match status {
        Status::Online => Color::Green,
        Status::Away => Color::Yellow,
        Status::Busy => Color::Red,
        Status::Offline => Color::Gray,
    }
}

pub fn status_label(status: Status) -> &'static str {
    match status {
        Status::Online => "Online",
        Status::Away => "Away",
        Status::Busy => "Busy",
        Status::Offline => "Appear Offline",
    }
}

fn status(status: Status) -> Text {
    c(status_color(status), status_label(status))
}

pub fn status_set(s: Status) -> Text {
    chat_box([span(Color::Yellow, [t("Your status is now "), status(s), t(".")])])
}

pub fn status_menu(current: Status) -> Text {
    let mut buttons = Text::empty();
    for s in Status::ALL {
        let label = status_label(s);
        buttons = buttons
            .child(button(
                &format!("[{label}]"),
                status_color(s),
                format!("/status {}", s.name().to_lowercase()),
                &format!("Set your status to {label}"),
            ))
            .child(t(" "));
    }
    chat_box([span(Color::Yellow, [t("Your status is "), status(current), t(". Click to change it:")]), buttons])
}

// --- private messages ---

/// "From [VIP] Bob: hi": `text` is the sender's, plain; `name` is how the receiver sees them. Clicking replies.
pub fn message_from(name: Text, username: &str, text: &str) -> Text {
    private_line("From ", name, username, c(Color::Gray, text))
}

pub fn message_to(name: Text, username: &str, text: &str) -> Text {
    private_line("To ", name, username, c(Color::Gray, text))
}

fn private_line(direction: &str, name: Text, username: &str, body: Text) -> Text {
    root([c(Color::LightPurple, direction), name, c(Color::Gray, ": "), body])
        .suggest_command(format!("/msg {username} "))
        .hover(c(Color::Gray, "Click to reply"))
}

pub fn offline(friend: Text, last_seen: Millis, now: Millis) -> Text {
    chat_box([root([
        friend,
        t(" "),
        span(
            Color::Red,
            [t("is offline. "), span(Color::Gray, [t("("), t(last_seen::format(last_seen, now)), t(")")])],
        ),
    ])])
}

/// `wait_ms`: until the oldest message in the window leaves it, rounded up to whole seconds.
pub fn too_fast(wait_ms: Millis) -> Text {
    let seconds = ((wait_ms + 999) / 1000).max(1);
    let s = if seconds == 1 { "" } else { "s" };
    chat_box([c(Color::Red, format!("You're sending messages too fast! Try again in {seconds} second{s}."))])
}

/// What a friend whose status is Away "answers": a private message line, marked as automatic.
pub fn afk_reply(name: Text, username: &str) -> Text {
    root([
        c(Color::LightPurple, "From "),
        name,
        c(Color::Gray, ": "),
        c(Color::Gray, "I'm currently AFK, and will answer when I am available.").italic(),
        c(Color::DarkGray, " (auto-reply)"),
    ])
    .suggest_command(format!("/msg {username} "))
    .hover(c(Color::Gray, "Click to reply"))
}

pub fn nobody_to_reply() -> Text {
    chat_box([span(Color::Red, [t("You have nobody to reply to! Use "), c(Color::Yellow, "/msg <friend> <message>")])])
}

// --- ignore ---

pub fn ignore_self() -> Text {
    chat_box([c(Color::Red, "You can't ignore yourself!")])
}

pub fn already_ignoring(p: Text) -> Text {
    chat_box([span(Color::Yellow, [t("You're already ignoring "), p, t(".")])])
}

pub fn ignored(p: Text, was_friend: bool) -> Text {
    let done =
        span(Color::Green, [t("You're now ignoring "), p, t(": they can't message you or send you friend requests.")]);
    if was_friend {
        chat_box([done, c(Color::Gray, "They were removed from your friends list (they aren't told).")])
    } else {
        chat_box([done])
    }
}

pub fn not_ignoring(input: &str) -> Text {
    chat_box([span(Color::Red, [t("You aren't ignoring '"), t(input), t("'!")])])
}

pub fn unignored(p: Text) -> Text {
    chat_box([span(Color::Green, [t("You're no longer ignoring "), p, t(".")])])
}

/// Each name suggests (doesn't run) `/f unignore`: one click shouldn't undo an ignore.
pub fn ignore_list(names: &[String]) -> Text {
    if names.is_empty() {
        return chat_box([span(
            Color::Yellow,
            [t("You aren't ignoring anyone. To block a player: "), c(Color::Aqua, "/f ignore <player>")],
        )]);
    }
    let mut list = Text::empty();
    for (i, n) in names.iter().enumerate() {
        if i > 0 {
            list = list.child(c(Color::DarkGray, ", "));
        }
        list = list.child(
            c(Color::Gray, n.as_str())
                .suggest_command(format!("/f unignore {n}"))
                .hover(c(Color::Gray, "Click to unignore")),
        );
    }
    chat_box([c(Color::Yellow, format!("Ignored players ({}):", names.len())), list])
}

pub fn you_ignore(p: Text, username: &str) -> Text {
    chat_box([span(
        Color::Red,
        [t("You're ignoring "), p, t("! Use "), c(Color::Yellow, format!("/f unignore {username}")), t(" first.")],
    )])
}

pub fn cant_request(p: Text) -> Text {
    chat_box([span(Color::Red, [t("You can't send "), p, t(" a friend request.")])])
}

// --- notifications ---

/// "Friend > Bob joined.": the name takes the green unless it has its own colour.
pub fn joined(p: Text) -> Text {
    span(Color::Green, [t("Friend > "), p, t(" "), c(Color::Yellow, "joined.")])
}

pub fn left(p: Text) -> Text {
    span(Color::Green, [t("Friend > "), p, t(" "), c(Color::Yellow, "left.")])
}

pub fn pending(count: usize) -> Text {
    let s = if count == 1 { "" } else { "s" };
    root([
        span(
            Color::Yellow,
            [t("You have "), c(Color::Green, count.to_string()), t(format!(" pending friend request{s}. "))],
        ),
        button("[VIEW]", Color::Green, "/f requests", "Click to view your friend requests"),
    ])
}

// --- lists ---

pub fn no_friends(best: bool) -> Text {
    chat_box([if best {
        span(Color::Yellow, [t("You don't have any best friends yet! Use "), c(Color::Aqua, "/f best <player>")])
    } else {
        span(Color::Yellow, [t("You don't have any friends yet! Add some with "), c(Color::Aqua, "/f add <player>")])
    }])
}

pub fn list(entries: &[Entry], page: usize, pages: usize, best: bool, now: Millis) -> Text {
    let command = if best { "/f list best " } else { "/f list " };
    let mut header = t(" ".repeat(17));
    header = header.child(if page > 1 {
        c(Color::Gold, "<< ").run_command(format!("{command}{}", page - 1)).hover(c(Color::Gray, "Previous page"))
    } else {
        t("   ")
    });
    let title = if best { "Best Friends" } else { "Friends" };
    header = header.child(c(Color::Gold, format!("{title} (Page {page} of {pages})")));
    if page < pages {
        header = header.child(
            c(Color::Gold, " >>").run_command(format!("{command}{}", page + 1)).hover(c(Color::Gray, "Next page")),
        );
    }
    chat_box(std::iter::once(header).chain(entries.iter().map(|e| entry(e, now))))
}

/// "● Bob — Playing BedWars Solo", "● Bob — Away · In lobby-1", "● Bob — Last seen 17 minutes ago". "●" (U+25CF)
/// rather than an emoji: old clients' fonts have it.
fn entry(e: &Entry, now: Millis) -> Text {
    let f = &e.friend;
    let mut shown = friend_name(f);
    if f.best {
        shown = shown.bold();
    }
    let mut hover = Text::empty();
    if f.nickname.is_some() {
        hover = hover.child(c(Color::Gray, "Username: ")).child(name(f.prefix.as_deref(), &f.name)).child(t("\n"));
    }
    hover = hover.child(c(Color::Gray, format!("Friends since {}", iso_date(f.since))));
    let dash = c(Color::DarkGray, " \u{2014} ");
    let Some(status) = e.status else {
        return root([
            c(Color::DarkGray, "\u{25CF} "),
            shown,
            dash,
            c(Color::Gray, last_seen::format(f.last_seen, now)),
        ])
        .hover(hover);
    };
    let place = match (&e.activity, &e.server) {
        (Some(a), _) => a.describe(),
        (None, Some(server)) => format!("In {server}"),
        (None, None) => "Online".to_owned(),
    };
    let detail = match status {
        Status::Away | Status::Busy => format!("{} \u{00B7} {place}", status_label(status)),
        _ => place,
    };
    if let (Some(_), Some(server)) = (&e.activity, &e.server) {
        hover = hover.child(t("\n")).child(c(Color::Gray, format!("Server: {server}")));
    }
    let detail_color = if status == Status::Busy { Color::Red } else { Color::Yellow };
    root([c(status_color(status), "\u{25CF} "), shown, dash, c(detail_color, detail)]).hover(hover)
}

pub fn no_requests() -> Text {
    chat_box([c(Color::Yellow, "You don't have any pending friend requests.")])
}

pub fn requests(incoming: &[Request], outgoing: &[Request], now: Millis) -> Text {
    let shown = incoming.len().min(PAGE_SIZE);
    let mut lines = vec![c(Color::Green, "Friend Requests:")];
    for r in &incoming[..shown] {
        lines.push(root([
            row_name(&r.from),
            t(" "),
            button("[ACCEPT]", Color::Green, format!("/f accept {}", r.from.name), "Click to accept"),
            t(" "),
            button("[DENY]", Color::Red, format!("/f deny {}", r.from.name), "Click to deny"),
        ]));
    }
    if incoming.len() > shown {
        lines.push(c(Color::Gray, format!("...and {} more", incoming.len() - shown)));
    }
    for r in outgoing {
        let left = ((r.expires - now) / 1000).max(0);
        lines.push(root([
            c(Color::Gray, "To "),
            row_name(&r.to),
            c(Color::Gray, format!(" (expires in {}m {}s)", left / 60, left % 60)),
        ]));
    }
    chat_box(lines)
}

/// ISO-8601 date in UTC ("2026-11-06"), like Java's `ISO_LOCAL_DATE.withZone(UTC)`.
pub fn iso_date(millis: Millis) -> String {
    // Howard Hinnant's civil_from_days.
    let days = millis.div_euclid(86_400_000);
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = yoe + era * 400 + i64::from(month <= 2);
    format!("{year:04}-{month:02}-{day:02}")
}

//! Parses `/friend <sub> ...` arguments (Java's `FriendCommand`). `/fl`, `/status`, `/msg` and `/r` arrive as
//! "list", "status", "msg" and "reply".

use crate::friends::{DbResult, Friends, Target};
use crate::model::{Online, Outcome, Status};

/// The subcommands tab completion offers.
pub const SUBCOMMANDS: [&str; 14] = [
    "add",
    "accept",
    "deny",
    "list",
    "requests",
    "remove",
    "best",
    "nickname",
    "removeall",
    "notifications",
    "status",
    "ignore",
    "unignore",
    "help",
];
const MAX_SUGGESTIONS: usize = 100;

pub struct FriendCommand {
    private_messages: bool,
}

/// `/fl`, `/status`, `/msg` and `/r` are `/friend <sub>`: prepend `sub` (if any).
pub fn with_sub(sub: Option<&str>, mut args: Vec<String>) -> Vec<String> {
    if let Some(sub) = sub {
        args.insert(0, sub.to_owned());
    }
    args
}

/// Command arguments as Bukkit splits them: on single spaces, trailing empty arguments dropped.
pub fn split_args(line: &str) -> Vec<String> {
    let mut args: Vec<String> = if line.is_empty() { Vec::new() } else { line.split(' ').map(str::to_owned).collect() };
    while args.last().is_some_and(String::is_empty) {
        args.pop();
    }
    args
}

/// Arguments while tab-completing: like [`split_args`], but the last (possibly empty) argument is the one being typed.
pub fn completion_args(line: &str) -> Vec<String> {
    line.split(' ').map(str::to_owned).collect()
}

impl FriendCommand {
    /// `private_messages`: `private-messages.enabled` (else "msg" and "reply" are not commands).
    pub fn new(private_messages: bool) -> FriendCommand {
        FriendCommand { private_messages }
    }

    pub fn execute(&self, friends: &Friends, s: &Online, args: &[String]) -> Outcome {
        match self.run(friends, s, args) {
            Ok(outcome) => outcome,
            Err(_) => {
                // Already logged by the storage layer's caller.
                friends.error(s);
                Outcome::Error
            }
        }
    }

    fn run(&self, f: &Friends, s: &Online, args: &[String]) -> DbResult<Outcome> {
        let mut sub = args.first().map_or_else(|| "help".to_owned(), |a| a.to_lowercase());
        let arg = args.get(1).map(Target::named);
        if !self.private_messages && (sub == "msg" || sub == "reply") {
            sub = "help".to_owned();
        }
        let rest = |from: usize| args.get(from..).unwrap_or_default().join(" ");
        match (sub.as_str(), arg) {
            ("help", _) => f.help(s, self.private_messages),
            ("msg", Some(to)) if args.len() >= 3 => f.message(s, &to, &rest(2)),
            ("msg", _) => f.usage(s, "/msg <friend> <message>"),
            ("reply", _) if args.len() >= 2 => f.reply_message(s, &rest(1)),
            ("reply", _) => f.usage(s, "/r <message>"),
            ("add", Some(t)) => f.add(s, &t),
            ("add", None) => f.usage(s, "/f add <player>"),
            ("accept", Some(t)) => f.accept(s, &t),
            ("accept", None) => f.usage(s, "/f accept <player>"),
            ("deny" | "decline", Some(t)) => f.deny(s, &t),
            ("deny" | "decline", None) => f.usage(s, "/f deny <player>"),
            ("remove" | "delete", Some(t)) => f.remove(s, &t),
            ("remove" | "delete", None) => f.usage(s, "/f remove <player>"),
            ("best", Some(t)) => f.best(s, &t, None),
            ("best", None) => f.usage(s, "/f best <player>"),
            ("nickname" | "nick", Some(t)) => {
                let nickname = (args.len() > 2).then(|| rest(2));
                f.nickname(s, &t, nickname.as_deref())
            }
            ("nickname" | "nick", None) => f.usage(s, "/f nickname <player> [nickname]"),
            ("list", _) => list(f, s, args),
            ("requests", _) => f.requests(s),
            ("removeall", _) => f.remove_all(s, args.get(1).is_some_and(|a| a.eq_ignore_ascii_case("confirm"))),
            ("notifications" | "notification" | "notif", _) => f.toggle_notifications(s),
            ("status", _) => status(f, s, args.get(1).map(String::as_str)),
            ("ignore", Some(t)) => f.ignore(s, &t),
            ("ignore", None) => f.ignore_list(s),
            ("unignore", Some(t)) => f.unignore(s, &t),
            ("unignore", None) => f.usage(s, "/f unignore <player>"),
            // "/f Steve" = "/f add Steve"
            _ if args.len() == 1 => f.add(s, &Target::named(args[0].clone())),
            _ => f.help(s, self.private_messages),
        }
    }

    pub fn suggest(&self, friends: &Friends, s: &Online, args: &[String]) -> Vec<String> {
        if args.len() <= 1 {
            let prefix = args.first().map_or("", String::as_str);
            let names = friends.online_names().into_iter().filter(|n| !n.eq_ignore_ascii_case(&s.name));
            return filter(SUBCOMMANDS.iter().map(|c| (*c).to_owned()).chain(names), prefix);
        }
        if args.len() > 2 {
            return Vec::new();
        }
        let prefix = args[args.len() - 1].as_str();
        let lower = |v: Vec<String>| v.into_iter().map(|n| n.to_lowercase()).collect::<Vec<_>>();
        match args[0].to_lowercase().as_str() {
            "add" => {
                let friend_names = lower(friends.friend_names(s.id));
                let online = friends.online_names().into_iter();
                filter(
                    online.filter(|n| !n.eq_ignore_ascii_case(&s.name) && !friend_names.contains(&n.to_lowercase())),
                    prefix,
                )
            }
            "accept" | "deny" | "decline" => filter(friends.requester_names(s.id).into_iter(), prefix),
            "ignore" => {
                let ignored = lower(friends.ignored_names(s.id));
                let online = friends.online_names().into_iter();
                filter(
                    online.filter(|n| !n.eq_ignore_ascii_case(&s.name) && !ignored.contains(&n.to_lowercase())),
                    prefix,
                )
            }
            "unignore" => filter(friends.ignored_names(s.id).into_iter(), prefix),
            "remove" | "delete" | "best" | "nickname" | "nick" => {
                filter(friends.friend_names(s.id).into_iter(), prefix)
            }
            "msg" if self.private_messages => filter(friends.online_friend_names(s.id).into_iter(), prefix),
            "list" => filter(["best".to_owned()].into_iter(), prefix),
            "removeall" => filter(["confirm".to_owned()].into_iter(), prefix),
            "status" => filter(["online", "away", "busy", "offline"].into_iter().map(str::to_owned), prefix),
            _ => Vec::new(),
        }
    }
}

fn list(f: &Friends, s: &Online, args: &[String]) -> DbResult<Outcome> {
    let best = args.get(1).is_some_and(|a| a.eq_ignore_ascii_case("best"));
    let page_arg = if best { 2 } else { 1 };
    let page = match args.get(page_arg) {
        None => 1,
        Some(p) => match p.parse::<i32>() {
            Ok(p) => p,
            Err(_) => return f.usage(s, "/f list [best] [page]"),
        },
    };
    f.list(s, i64::from(page), best)
}

fn status(f: &Friends, s: &Online, arg: Option<&str>) -> DbResult<Outcome> {
    let Some(arg) = arg else { return f.status(s, None) };
    let status = match arg.to_lowercase().as_str() {
        "online" => Status::Online,
        "away" => Status::Away,
        "busy" => Status::Busy,
        "offline" | "invisible" | "appearoffline" => Status::Offline,
        _ => return f.usage(s, "/status [online|away|busy|offline]"),
    };
    f.status(s, Some(status))
}

fn filter(options: impl Iterator<Item = String>, prefix: &str) -> Vec<String> {
    let prefix = prefix.to_lowercase();
    let mut out: Vec<String> = Vec::new();
    for o in options {
        if o.to_lowercase().starts_with(&prefix) && !out.contains(&o) {
            out.push(o);
        }
    }
    out.sort_by_key(|o| o.to_lowercase());
    out.truncate(MAX_SUGGESTIONS);
    out
}

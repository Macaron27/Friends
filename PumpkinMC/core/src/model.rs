//! Friends' data model: the API's Status, Result and PlayerActivity, and the core's Friend, PlayerRow, Request,
//! Presence and Online records.

use uuid::Uuid;

/// Milliseconds since the Unix epoch: what the database stores (`last_seen`, `since`).
pub type Millis = i64;

/// Online status a player shows their friends, like Hypixel's `/status`.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Status {
    /// Online and available (the default).
    Online,
    /// Online, away from keyboard.
    Away,
    /// Online, do not disturb.
    Busy,
    /// Online but appearing offline: friends see them offline and get no join/leave messages.
    Offline,
}

impl Status {
    pub const ALL: [Status; 4] = [Status::Online, Status::Away, Status::Busy, Status::Offline];

    /// The Java enum constant's name: stored in `friends_players.status` and used by the API.
    pub fn name(self) -> &'static str {
        match self {
            Status::Online => "ONLINE",
            Status::Away => "AWAY",
            Status::Busy => "BUSY",
            Status::Offline => "OFFLINE",
        }
    }

    /// The reverse of [`Status::name`] (exact, like Java's `Status.valueOf`).
    pub fn from_name(name: &str) -> Option<Status> {
        Status::ALL.into_iter().find(|s| s.name() == name)
    }
}

/// Outcome of an action (Java's `com.friends.api.Result`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Outcome {
    /// Done: the request was sent, accepted or denied, the friend removed, or the setting changed.
    Success,
    /// A request to someone who had already asked the sender: they are now friends.
    BecameFriends,
    /// The acting player is not online here, or their data is still loading.
    NotLoaded,
    /// The target player has never joined.
    PlayerNotFound,
    /// The player targeted themselves (Java's `SELF`).
    SelfTarget,
    AlreadyFriends,
    /// The sender already has a pending request to the target.
    AlreadyRequested,
    /// No pending request from that player (never sent, already answered or expired).
    NoRequest,
    NotFriends,
    /// The acting player has reached `max-friends`.
    LimitReached,
    /// The target has reached `max-friends`.
    TargetLimitReached,
    /// A missing or invalid argument (for nicknames: 1 to 16 letters, digits, spaces or underscores).
    InvalidArgument,
    /// A plugin cancelled the action's event.
    Cancelled,
    /// A database error (logged).
    Error,
    /// The target is offline, or appearing offline (private messages).
    NotOnline,
    /// The sender is sending private messages too fast (`private-messages.rate-limit`).
    RateLimited,
    /// One of the two players ignores the other (`/f ignore`).
    Ignored,
}

impl Outcome {
    /// The Java enum constant's name (the API's wire format).
    pub fn name(self) -> &'static str {
        match self {
            Outcome::Success => "SUCCESS",
            Outcome::BecameFriends => "BECAME_FRIENDS",
            Outcome::NotLoaded => "NOT_LOADED",
            Outcome::PlayerNotFound => "PLAYER_NOT_FOUND",
            Outcome::SelfTarget => "SELF",
            Outcome::AlreadyFriends => "ALREADY_FRIENDS",
            Outcome::AlreadyRequested => "ALREADY_REQUESTED",
            Outcome::NoRequest => "NO_REQUEST",
            Outcome::NotFriends => "NOT_FRIENDS",
            Outcome::LimitReached => "LIMIT_REACHED",
            Outcome::TargetLimitReached => "TARGET_LIMIT_REACHED",
            Outcome::InvalidArgument => "INVALID_ARGUMENT",
            Outcome::Cancelled => "CANCELLED",
            Outcome::Error => "ERROR",
            Outcome::NotOnline => "NOT_ONLINE",
            Outcome::RateLimited => "RATE_LIMITED",
            Outcome::Ignored => "IGNORED",
        }
    }

    /// Whether the action happened.
    pub fn is_success(self) -> bool {
        matches!(self, Outcome::Success | Outcome::BecameFriends)
    }
}

/// What a player is doing, from the server they are on (`presence.rules`): a game and an optional mode.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PlayerActivity {
    pub game: String,
    pub mode: Option<String>,
}

impl PlayerActivity {
    /// How Friends shows it in chat: "Playing BedWars Solo", or "Playing BedWars" without a mode.
    pub fn describe(&self) -> String {
        match &self.mode {
            Some(mode) => format!("Playing {} {mode}", self.game),
            None => format!("Playing {}", self.game),
        }
    }
}

/// One side of a friendship as seen by its owner. `best` and `nickname` are private to the owner; `name`,
/// `prefix` and `last_seen` describe the friend (cached so lists need no queries).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Friend {
    pub id: Uuid,
    pub name: String,
    pub prefix: Option<String>,
    pub since: Millis,
    pub best: bool,
    pub nickname: Option<String>,
    pub last_seen: Millis,
}

impl Friend {
    pub(crate) fn with_best(&self, best: bool) -> Friend {
        Friend { best, ..self.clone() }
    }

    pub(crate) fn with_nickname(&self, nickname: Option<String>) -> Friend {
        Friend { nickname, ..self.clone() }
    }

    pub(crate) fn seen(&self, name: String, prefix: Option<String>, last_seen: Millis) -> Friend {
        Friend { name, prefix, last_seen, ..self.clone() }
    }
}

/// A row of `friends_players`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PlayerRow {
    pub id: Uuid,
    pub name: String,
    pub prefix: Option<String>,
    pub last_seen: Millis,
}

/// A pending friend request.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Request {
    pub from: PlayerRow,
    pub to: PlayerRow,
    pub expires: Millis,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct RequestKey {
    pub from: Uuid,
    pub to: Uuid,
}

impl Request {
    pub fn key(&self) -> RequestKey {
        RequestKey { from: self.from.id, to: self.to.id }
    }
}

/// Where an online player is. `server` is the backend server on a proxy; a standalone server has none, like Paper,
/// so `activity` (what `presence.rules` make of the server) is none too.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Presence {
    pub name: String,
    pub prefix: Option<String>,
    pub server: Option<String>,
    pub status: Status,
    pub activity: Option<PlayerActivity>,
}

impl Presence {
    pub(crate) fn with_status(&self, status: Status) -> Presence {
        Presence { status, ..self.clone() }
    }
}

/// Who receives an action's replies. Java's `Audience`: a player's session (whose id tells sessions of the same
/// player apart), or nobody (API actions).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Session {
    Player(u64),
    Silent,
}

/// A connected player. `prefix` is a legacy ('&') rank prefix, `server` the backend server (none on a standalone
/// server).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Online {
    pub id: Uuid,
    pub name: String,
    pub prefix: Option<String>,
    pub server: Option<String>,
    pub session: Session,
}

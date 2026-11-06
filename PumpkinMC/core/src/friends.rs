//! Friends core (Java's `Friends`): rules, caches and messaging. Reads come from memory; writes go to the database
//! as they happen. Every change of shared state goes through [`Friends::apply`] as an [`Event`], like the Java core
//! (where other proxies apply the same events; a standalone server is the one-proxy case).
//!
//! Threading: a Pumpkin plugin runs on one thread, but the host may call back into the plugin while it is calling the
//! host (an event, another plugin's IPC call). So state lives behind one lock that is never held while calling out:
//! plugins are asked (the [`Hooks`]) without it, and chat and "done" notifications are queued as [`Effect`]s, which
//! the platform delivers after the call ([`Friends::drain_effects`]).

use std::collections::{BTreeMap, HashMap, HashSet, VecDeque};
use std::sync::{LazyLock, Mutex, MutexGuard};
use std::time::Duration;

use regex::Regex;
use uuid::Uuid;

use crate::activities::Activities;
use crate::messages::{self as m, Entry, PAGE_SIZE};
use crate::model::{Friend, Millis, Online, Outcome, PlayerRow, Presence, Request, RequestKey, Session, Status};
use crate::settings::RateLimit;
use crate::storage::{Loaded, Storage, StorageError};
use crate::text::Text;

static NAME: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^[A-Za-z0-9_.*]{1,16}$").expect("valid regex"));
static NICKNAME: LazyLock<Regex> = LazyLock::new(|| Regex::new(r"^[\p{L}\p{N}_ ]{1,16}$").expect("valid regex"));

/// How often a sender sees the same AFK friend's auto-reply.
pub const AFK_REPLY_EVERY: Millis = 5 * 60 * 1000;
/// Friends of players not loaded here, read for the API, are cached this long, this many at most.
const OFFLINE_TTL: Millis = 30 * 1000;
const OFFLINE_MAX: usize = 1024;

pub type Clock = Box<dyn Fn() -> Millis + Send + Sync>;
pub type DbResult<T> = Result<T, StorageError>;

/// Lets other plugins veto changes (Java's `Hooks`, Bukkit's cancellable events). Asked without the core's lock;
/// the action is re-checked afterwards. The "done" side comes as [`Effect`]s.
pub trait Hooks: Send + Sync {
    /// `from` is about to send `to` a request. False cancels it.
    fn requesting(&self, _from: &PlayerRow, _to: &PlayerRow) -> bool {
        true
    }
    /// `player` is about to accept `sender`'s request. False cancels it (the request stays pending).
    fn befriending(&self, _player: &PlayerRow, _sender: &PlayerRow) -> bool {
        true
    }
    /// `player` is about to remove `friend`. False keeps the friendship.
    fn unfriending(&self, _player: &PlayerRow, _friend: &Friend) -> bool {
        true
    }
    /// The text to deliver (plugins may rewrite it), or none to cancel: then nothing is delivered, echoed or
    /// remembered for `/r`.
    fn messaging(&self, _from: &PlayerRow, _to: &PlayerRow, message: &str) -> Option<String> {
        Some(message.to_owned())
    }
    /// False keeps the current status.
    fn status_changing(&self, _player: &PlayerRow, _from: Status, _to: Status) -> bool {
        true
    }
}

pub struct NoHooks;
impl Hooks for NoHooks {}

/// What a call produced for the outside world, in order.
#[derive(Clone, Debug, PartialEq)]
pub enum Effect {
    /// Chat for a player of this server.
    Message(Uuid, Text),
    Befriended {
        player: PlayerRow,
        sender: PlayerRow,
        since: Millis,
    },
    Unfriended {
        player: PlayerRow,
        friend: Friend,
    },
}

/// Who a command is about: the name a player typed, or a UUID (API).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Target {
    pub id: Option<Uuid>,
    pub name: Option<String>,
}

impl Target {
    pub fn named(name: impl Into<String>) -> Target {
        Target { id: None, name: Some(name.into()) }
    }

    pub fn of(id: Uuid) -> Target {
        Target { id: Some(id), name: None }
    }

    fn is(&self, other_id: Uuid, other_name: &str) -> bool {
        match (self.id, &self.name) {
            (Some(id), _) => id == other_id,
            (None, Some(name)) => eq_ignore_case(name, other_name),
            (None, None) => false,
        }
    }

    /// How messages name it.
    fn label(&self) -> String {
        self.name.clone().unwrap_or_else(|| self.id.map(|i| i.to_string()).unwrap_or_default())
    }
}

fn eq_ignore_case(a: &str, b: &str) -> bool {
    a.to_lowercase() == b.to_lowercase()
}

fn cmp_ignore_case(a: &str, b: &str) -> std::cmp::Ordering {
    a.to_lowercase().cmp(&b.to_lowercase())
}

/// A change of shared state. `Friends::apply` is the only place state changes.
enum Event {
    Joined {
        id: Uuid,
        presence: Presence,
    },
    /// Changed status (no notification).
    Updated {
        id: Uuid,
        presence: Presence,
    },
    Left {
        id: Uuid,
        last_seen: Millis,
    },
    RequestSent(Request),
    RequestDenied(RequestKey),
    Befriended {
        a: PlayerRow,
        b: PlayerRow,
        since: Millis,
    },
    Unfriended {
        player: Uuid,
        others: Vec<Uuid>,
    },
    /// Already vetted: delivered if the receiver is here.
    PrivateMessage {
        from: PlayerRow,
        to: Uuid,
        text: String,
    },
}

/// Cached state of a player on this server.
struct Profile {
    session: Session,
    friends: HashMap<Uuid, Friend>,
    ignored: HashMap<Uuid, String>, // players they ignore: id -> name
    notifications: bool,
    reply_to: Option<Target>, // who /r answers: the last private message sent or received
    sent: VecDeque<Millis>,   // their private messages within the rate-limit window
    afk_replied: HashMap<Uuid, Millis>, // friend -> when their AFK reply was shown
}

impl Profile {
    fn new(session: Session, data: Loaded) -> Profile {
        Profile {
            session,
            friends: data.friends.into_iter().map(|f| (f.id, f)).collect(),
            ignored: data.ignored.into_iter().collect(),
            notifications: data.notifications,
            reply_to: None,
            sent: VecDeque::new(),
            afk_replied: HashMap::new(),
        }
    }

    /// Counts a private message sent `now`, or returns how long to wait (ms) if that would exceed `limit`.
    fn send(&mut self, now: Millis, limit: RateLimit) -> Millis {
        if !limit.enabled() {
            return 0;
        }
        let window = Millis::try_from(limit.window.as_millis()).unwrap_or(Millis::MAX);
        while self.sent.front().is_some_and(|first| now >= first.saturating_add(window)) {
            self.sent.pop_front();
        }
        if self.sent.len() >= limit.messages {
            return self.sent.front().map_or(0, |first| first.saturating_add(window) - now);
        }
        self.sent.push_back(now);
        0
    }

    /// Whether to show `friend`'s AFK auto-reply now: once per [`AFK_REPLY_EVERY`] per friend.
    fn afk_reply_due(&mut self, friend: Uuid, now: Millis) -> bool {
        if self.afk_replied.get(&friend).is_some_and(|last| now < last + AFK_REPLY_EVERY) {
            return false;
        }
        self.afk_replied.insert(friend, now);
        true
    }
}

struct State {
    storage: Storage,
    profiles: HashMap<Uuid, Profile>,  // players on this server
    presence: HashMap<Uuid, Presence>, // players online
    requests: BTreeMap<RequestKey, Request>,
    offline: HashMap<Uuid, (Vec<Friend>, Millis)>, // friends of players not loaded here, until the instant
    effects: Vec<Effect>,
}

impl State {
    /// Chat for a player loaded here.
    fn message(&mut self, id: Uuid, text: Text) {
        if self.profiles.contains_key(&id) {
            self.effects.push(Effect::Message(id, text));
        }
    }

    /// Runs a write; a failure is logged (the action itself already happened in memory, as in the Java plugin).
    fn write(&mut self, what: impl FnOnce(&mut Storage) -> DbResult<()>) {
        if let Err(e) = what(&mut self.storage) {
            tracing::error!("Friends database error: {e}");
        }
    }
}

fn reply(st: &mut State, s: &Online, text: Text, outcome: Outcome) -> DbResult<Outcome> {
    if let Session::Player(_) = s.session {
        st.effects.push(Effect::Message(s.id, text));
    }
    Ok(outcome)
}

pub struct Friends {
    state: Mutex<State>,
    clock: Clock,
    request_expiry: Duration,
    max_friends: usize,
    rate_limit: RateLimit,
    activities: Activities,
    hooks: Box<dyn Hooks>,
}

impl Friends {
    pub fn new(
        storage: Storage,
        clock: Clock,
        request_expiry: Duration,
        max_friends: usize,
        rate_limit: RateLimit,
        activities: Activities,
        hooks: Box<dyn Hooks>,
    ) -> Friends {
        Friends {
            state: Mutex::new(State {
                storage,
                profiles: HashMap::new(),
                presence: HashMap::new(),
                requests: BTreeMap::new(),
                offline: HashMap::new(),
                effects: Vec::new(),
            }),
            clock,
            request_expiry,
            max_friends,
            rate_limit,
            activities,
            hooks,
        }
    }

    fn lock(&self) -> MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
    }

    fn now(&self) -> Millis {
        (self.clock)()
    }

    /// Messages and notifications produced so far, for the platform to deliver (outside any lock).
    pub fn drain_effects(&self) -> Vec<Effect> {
        std::mem::take(&mut self.lock().effects)
    }

    // --- events ---

    fn apply(&self, st: &mut State, event: Event) {
        match event {
            Event::Joined { id, presence } => {
                st.offline.remove(&id);
                let announce = presence.status != Status::Offline;
                let (name, prefix) = (presence.name.clone(), presence.prefix.clone());
                st.presence.insert(id, presence);
                let now = self.now();
                for_friends_of(st, id, |f| f.seen(name.clone(), prefix.clone(), now), announce, true);
            }
            Event::Updated { id, presence } => {
                st.presence.insert(id, presence);
            }
            Event::Left { id, last_seen } => {
                st.offline.remove(&id);
                let Some(p) = st.presence.remove(&id) else { return };
                for_friends_of(
                    st,
                    id,
                    |f| f.seen(f.name.clone(), f.prefix.clone(), last_seen),
                    p.status != Status::Offline,
                    false,
                );
            }
            Event::RequestSent(r) => {
                let text = m::received(m::row_name(&r.from), &r.from.name);
                let to = r.to.id;
                st.requests.insert(r.key(), r);
                st.message(to, text);
            }
            Event::RequestDenied(key) => {
                st.requests.remove(&key);
            }
            Event::Befriended { a, b, since } => {
                st.requests.remove(&RequestKey { from: a.id, to: b.id });
                st.requests.remove(&RequestKey { from: b.id, to: a.id });
                st.offline.remove(&a.id);
                st.offline.remove(&b.id);
                link(st, &a, &b, since);
                link(st, &b, &a, since);
            }
            Event::Unfriended { player, others } => {
                st.offline.remove(&player);
                for other in others {
                    st.offline.remove(&other);
                    if let Some(me) = st.profiles.get_mut(&player) {
                        me.friends.remove(&other);
                    }
                    if let Some(theirs) = st.profiles.get_mut(&other) {
                        theirs.friends.remove(&player);
                    }
                }
            }
            Event::PrivateMessage { from, to, text } => {
                // Not here, or ignoring the sender.
                let Some(p) = st.profiles.get_mut(&to) else { return };
                if p.ignored.contains_key(&from.id) {
                    return;
                }
                p.reply_to = Some(Target { id: Some(from.id), name: Some(from.name.clone()) });
                let shown = p.friends.get(&from.id).map_or_else(|| m::row_name(&from), m::friend_name);
                st.effects.push(Effect::Message(to, m::message_from(shown, &from.name, &text)));
            }
        }
    }

    // --- lifecycle ---

    /// Loads the player's data, then announces them. A database error is logged; the player then has no profile
    /// until they rejoin.
    pub fn connect(&self, p: &Online) {
        let now = self.now();
        let mut st = self.lock();
        let data = st.storage.save_player(p.id, &p.name, p.prefix.as_deref(), now).and_then(|()| st.storage.load(p.id));
        let data = match data {
            Ok(data) => data,
            Err(e) => {
                tracing::error!("Friends database error: {e}");
                return;
            }
        };
        let status = data.status;
        st.profiles.insert(p.id, Profile::new(p.session, data));
        let activity = self.activities.match_server(p.server.as_deref());
        let presence =
            Presence { name: p.name.clone(), prefix: p.prefix.clone(), server: p.server.clone(), status, activity };
        self.apply(&mut st, Event::Joined { id: p.id, presence });
    }

    /// Call once the player can receive chat: tells them about pending requests.
    pub fn greet(&self, p: &Online) {
        let now = self.now();
        let mut st = self.lock();
        let pending = st.requests.values().filter(|r| r.to.id == p.id && now < r.expires).count();
        if pending > 0 {
            let _ = reply(&mut st, p, m::pending(pending), Outcome::Success);
        }
    }

    pub fn disconnect(&self, p: &Online) {
        let mut st = self.lock();
        // Only the session that loaded the profile may drop it (a rejoin can overtake the old session's leave).
        if st.profiles.get(&p.id).is_none_or(|profile| profile.session != p.session) {
            return;
        }
        st.profiles.remove(&p.id);
        let now = self.now();
        st.write(|s| s.touch(p.id, now));
        self.apply(&mut st, Event::Left { id: p.id, last_seen: now });
    }

    /// Called every second; validity is also checked lazily, so timing isn't critical.
    pub fn expire_requests(&self) {
        let now = self.now();
        let mut st = self.lock();
        let expired: Vec<Request> = st.requests.values().filter(|r| now >= r.expires).cloned().collect();
        for r in expired {
            st.requests.remove(&r.key());
            st.message(r.from.id, m::expired_outgoing(m::row_name(&r.to)));
            st.message(r.to.id, m::expired_incoming(m::row_name(&r.from)));
        }
    }

    // --- commands (and API actions): each replies to the player and returns what happened ---

    /// `private_messages`: also list `/msg` and `/r`.
    pub fn help(&self, s: &Online, private_messages: bool) -> DbResult<Outcome> {
        reply(&mut self.lock(), s, m::help(private_messages), Outcome::Success)
    }

    pub fn usage(&self, s: &Online, usage: &str) -> DbResult<Outcome> {
        reply(&mut self.lock(), s, m::usage(usage), Outcome::InvalidArgument)
    }

    pub fn error(&self, s: &Online) {
        let _ = reply(&mut self.lock(), s, m::error(), Outcome::Error);
    }

    pub fn add(&self, s: &Online, target: &Target) -> DbResult<Outcome> {
        let (t, count) = {
            let mut st = self.lock();
            if !st.profiles.contains_key(&s.id) {
                return reply(&mut st, s, m::not_loaded(), Outcome::NotLoaded);
            }
            if target.is(s.id, &s.name) {
                return reply(&mut st, s, m::add_self(), Outcome::SelfTarget);
            }
            let Some(t) = self.resolve(&mut st, target)? else {
                return reply(&mut st, s, m::not_found(&target.label()), Outcome::PlayerNotFound);
            };
            if st.profiles.get(&s.id).is_some_and(|me| me.ignored.contains_key(&t.id)) {
                let text = m::you_ignore(m::row_name(&t), &t.name);
                return reply(&mut st, s, text, Outcome::Ignored);
            }
            if ignoring(&mut st, t.id, s.id)? {
                return reply(&mut st, s, m::cant_request(m::row_name(&t)), Outcome::Ignored);
            }
            let count = friend_count(&mut st, t.id)?;
            (t, count)
        };
        self.add_checked(s, &t, count, false)
    }

    /// All checks and the send in one locked step, asking plugins in between (`vetted`: they agreed).
    fn add_checked(&self, s: &Online, target: &PlayerRow, count: usize, vetted: bool) -> DbResult<Outcome> {
        let theirs = {
            let mut st = self.lock();
            let now = self.now();
            let name = m::row_name(target);
            let Some(me) = st.profiles.get(&s.id) else {
                return reply(&mut st, s, m::not_loaded(), Outcome::NotLoaded);
            };
            if me.ignored.contains_key(&target.id) {
                return reply(&mut st, s, m::you_ignore(name, &target.name), Outcome::Ignored); // meanwhile
            }
            if me.friends.contains_key(&target.id) {
                return reply(&mut st, s, m::already_friends(name), Outcome::AlreadyFriends);
            }
            let my_count = me.friends.len();
            let theirs = valid(st.requests.get(&RequestKey { from: target.id, to: s.id }), now).cloned();
            if theirs.is_none() {
                if valid(st.requests.get(&RequestKey { from: s.id, to: target.id }), now).is_some() {
                    return reply(&mut st, s, m::already_sent(name), Outcome::AlreadyRequested);
                }
                if my_count >= self.max_friends {
                    return reply(&mut st, s, m::limit_self(self.max_friends), Outcome::LimitReached);
                }
                if count >= self.max_friends {
                    return reply(&mut st, s, m::limit_other(name), Outcome::TargetLimitReached);
                }
                if vetted {
                    let expires =
                        now.saturating_add(Millis::try_from(self.request_expiry.as_millis()).unwrap_or(Millis::MAX));
                    let request = Request { from: row(s, now), to: target.clone(), expires };
                    self.apply(&mut st, Event::RequestSent(request));
                    let minutes = (self.request_expiry.as_secs() / 60) as i64;
                    return reply(&mut st, s, m::sent(name, minutes), Outcome::Success);
                }
            }
            theirs
        };
        if let Some(theirs) = theirs {
            // Both asked: just make them friends.
            let outcome = self.accept_request(s, &theirs)?;
            return Ok(if outcome == Outcome::Success { Outcome::BecameFriends } else { outcome });
        }
        // Plugins are asked without the lock (their listeners may be slow), so everything is checked again after.
        if self.hooks.requesting(&row(s, self.now()), target) {
            self.add_checked(s, target, count, true)
        } else {
            Ok(Outcome::Cancelled)
        }
    }

    pub fn accept(&self, s: &Online, target: &Target) -> DbResult<Outcome> {
        let request = {
            let mut st = self.lock();
            if !st.profiles.contains_key(&s.id) {
                return reply(&mut st, s, m::not_loaded(), Outcome::NotLoaded);
            }
            let now = self.now();
            let found = incoming(&st, s.id, now).find(|r| target.is(r.from.id, &r.from.name)).cloned();
            match found {
                Some(r) => r,
                None => return reply(&mut st, s, m::no_request(&target.label()), Outcome::NoRequest),
            }
        };
        self.accept_request(s, &request)
    }

    fn accept_request(&self, s: &Online, r: &Request) -> DbResult<Outcome> {
        let other = &r.from;
        {
            let mut st = self.lock();
            let Some(me) = st.profiles.get(&s.id) else {
                return reply(&mut st, s, m::not_loaded(), Outcome::NotLoaded);
            };
            // Ignoring drops their requests; one still here was sent meanwhile.
            if me.ignored.contains_key(&other.id) {
                return reply(&mut st, s, m::you_ignore(m::row_name(other), &other.name), Outcome::Ignored);
            }
            if me.friends.len() >= self.max_friends {
                return reply(&mut st, s, m::limit_self(self.max_friends), Outcome::LimitReached);
            }
            if friend_count(&mut st, other.id)? >= self.max_friends {
                return reply(&mut st, s, m::limit_other(m::row_name(other)), Outcome::TargetLimitReached);
            }
        }
        let mine = row(s, self.now());
        if !self.hooks.befriending(&mine, other) {
            return Ok(Outcome::Cancelled);
        }
        let mut st = self.lock();
        if st.requests.get(&r.key()) != Some(r) {
            return reply(&mut st, s, m::no_request(&other.name), Outcome::NoRequest); // expired, answered or denied meanwhile
        }
        st.requests.remove(&r.key());
        if st.profiles.get(&s.id).is_some_and(|me| me.friends.contains_key(&other.id)) {
            return Ok(Outcome::AlreadyFriends); // crossing requests: already done
        }
        let now = self.now();
        st.write(|db| db.add_friendship(s.id, other.id, now));
        self.apply(&mut st, Event::Befriended { a: other.clone(), b: mine.clone(), since: now });
        st.effects.push(Effect::Befriended { player: mine, sender: other.clone(), since: now });
        Ok(Outcome::Success)
    }

    pub fn deny(&self, s: &Online, target: &Target) -> DbResult<Outcome> {
        let mut st = self.lock();
        let now = self.now();
        let found = incoming(&st, s.id, now).find(|r| target.is(r.from.id, &r.from.name)).cloned();
        let Some(r) = found else { return reply(&mut st, s, m::no_request(&target.label()), Outcome::NoRequest) };
        self.apply(&mut st, Event::RequestDenied(r.key()));
        reply(&mut st, s, m::declined(m::row_name(&r.from)), Outcome::Success)
    }

    pub fn remove(&self, s: &Online, target: &Target) -> DbResult<Outcome> {
        let f = {
            let mut st = self.lock();
            let Some(me) = st.profiles.get(&s.id) else {
                return reply(&mut st, s, m::not_loaded(), Outcome::NotLoaded);
            };
            match find(me, target) {
                Some(f) => f,
                None => return reply(&mut st, s, m::not_friend(&target.label()), Outcome::NotFriends),
            }
        };
        let mine = row(s, self.now());
        if !self.hooks.unfriending(&mine, &f) {
            return Ok(Outcome::Cancelled);
        }
        let mut st = self.lock();
        if st.profiles.get(&s.id).is_none_or(|me| !me.friends.contains_key(&f.id)) {
            return reply(&mut st, s, m::not_friend(&target.label()), Outcome::NotFriends); // meanwhile
        }
        st.write(|db| db.remove_friendships(s.id, &[f.id]));
        self.apply(&mut st, Event::Unfriended { player: s.id, others: vec![f.id] });
        let text = m::removed(m::name(f.prefix.as_deref(), &f.name));
        st.effects.push(Effect::Unfriended { player: mine, friend: f });
        reply(&mut st, s, text, Outcome::Success)
    }

    pub fn remove_all(&self, s: &Online, confirmed: bool) -> DbResult<Outcome> {
        let victims: Vec<Friend> = {
            let mut st = self.lock();
            let Some(me) = st.profiles.get(&s.id) else {
                return reply(&mut st, s, m::not_loaded(), Outcome::NotLoaded);
            };
            let victims: Vec<Friend> = me.friends.values().filter(|f| !f.best).cloned().collect();
            if victims.is_empty() {
                return reply(&mut st, s, m::nothing_to_remove(), Outcome::NotFriends);
            }
            if !confirmed {
                return reply(&mut st, s, m::remove_all_confirm(victims.len()), Outcome::Success);
            }
            victims
        };
        let mine = row(s, self.now());
        let allowed: HashSet<Uuid> =
            victims.iter().filter(|f| self.hooks.unfriending(&mine, f)).map(|f| f.id).collect();
        if allowed.is_empty() {
            return Ok(Outcome::Cancelled);
        }
        let mut st = self.lock();
        let removed: Vec<Friend> = st
            .profiles
            .get(&s.id)
            .map(|me| me.friends.values().filter(|f| !f.best && allowed.contains(&f.id)).cloned().collect())
            .unwrap_or_default();
        if removed.is_empty() {
            return reply(&mut st, s, m::nothing_to_remove(), Outcome::NotFriends);
        }
        let ids: Vec<Uuid> = removed.iter().map(|f| f.id).collect();
        st.write(|db| db.remove_friendships(s.id, &ids));
        self.apply(&mut st, Event::Unfriended { player: s.id, others: ids.clone() });
        for f in removed {
            st.effects.push(Effect::Unfriended { player: mine.clone(), friend: f });
        }
        reply(&mut st, s, m::removed_all(ids.len()), Outcome::Success)
    }

    /// `best` none toggles.
    pub fn best(&self, s: &Online, target: &Target, best: Option<bool>) -> DbResult<Outcome> {
        let mut st = self.lock();
        let Some(me) = st.profiles.get_mut(&s.id) else {
            return reply(&mut st, s, m::not_loaded(), Outcome::NotLoaded);
        };
        let Some(f) = find(me, target) else {
            return reply(&mut st, s, m::not_friend(&target.label()), Outcome::NotFriends);
        };
        let on = best.unwrap_or(!f.best);
        me.friends.insert(f.id, f.with_best(on));
        st.write(|db| db.set_best(s.id, f.id, on));
        reply(&mut st, s, m::best(m::name(f.prefix.as_deref(), &f.name), on), Outcome::Success)
    }

    /// `nickname` none clears it.
    pub fn nickname(&self, s: &Online, target: &Target, nickname: Option<&str>) -> DbResult<Outcome> {
        let mut st = self.lock();
        if nickname.is_some_and(|n| !NICKNAME.is_match(n)) {
            return reply(&mut st, s, m::invalid_nickname(), Outcome::InvalidArgument);
        }
        let Some(me) = st.profiles.get_mut(&s.id) else {
            return reply(&mut st, s, m::not_loaded(), Outcome::NotLoaded);
        };
        let Some(f) = find(me, target) else {
            return reply(&mut st, s, m::not_friend(&target.label()), Outcome::NotFriends);
        };
        me.friends.insert(f.id, f.with_nickname(nickname.map(str::to_owned)));
        st.write(|db| db.set_nickname(s.id, f.id, nickname));
        reply(&mut st, s, m::nickname(m::name(f.prefix.as_deref(), &f.name), nickname), Outcome::Success)
    }

    pub fn toggle_notifications(&self, s: &Online) -> DbResult<Outcome> {
        let mut st = self.lock();
        let Some(me) = st.profiles.get_mut(&s.id) else {
            return reply(&mut st, s, m::not_loaded(), Outcome::NotLoaded);
        };
        me.notifications = !me.notifications;
        let enabled = me.notifications;
        st.write(|db| db.set_notifications(s.id, enabled));
        reply(&mut st, s, m::notifications(enabled), Outcome::Success)
    }

    /// `status` none shows the current status with clickable options.
    pub fn status(&self, s: &Online, status: Option<Status>) -> DbResult<Outcome> {
        let (current, wanted) = {
            let mut st = self.lock();
            let current = st.presence.get(&s.id).map(|p| p.status);
            let Some(current) = current.filter(|_| st.profiles.contains_key(&s.id)) else {
                return reply(&mut st, s, m::not_loaded(), Outcome::NotLoaded);
            };
            let Some(wanted) = status else { return reply(&mut st, s, m::status_menu(current), Outcome::Success) };
            (current, wanted)
        };
        if wanted != current && !self.hooks.status_changing(&row(s, self.now()), current, wanted) {
            return Ok(Outcome::Cancelled);
        }
        self.set_status(&mut self.lock(), s, wanted)
    }

    fn set_status(&self, st: &mut State, s: &Online, status: Status) -> DbResult<Outcome> {
        // Re-read: plugins were asked without the lock.
        let Some(now) = st.presence.get(&s.id).filter(|_| st.profiles.contains_key(&s.id)).cloned() else {
            return reply(st, s, m::not_loaded(), Outcome::NotLoaded);
        };
        st.write(|db| db.set_status(s.id, status));
        self.apply(st, Event::Updated { id: s.id, presence: now.with_status(status) });
        reply(st, s, m::status_set(status), Outcome::Success)
    }

    /// `/msg`: a private message to a friend who is visibly online. Plugins may cancel or rewrite it first.
    pub fn message(&self, s: &Online, target: &Target, text: &str) -> DbResult<Outcome> {
        self.private_message(s, target, text, "/msg <friend> <message>")
    }

    fn private_message(&self, s: &Online, target: &Target, text: &str, usage: &str) -> DbResult<Outcome> {
        let (f, to) = {
            let mut st = self.lock();
            let now = self.now();
            let Some(me) = st.profiles.get(&s.id) else {
                return reply(&mut st, s, m::not_loaded(), Outcome::NotLoaded);
            };
            let body = clean(text);
            if body.is_empty() {
                return reply(&mut st, s, m::usage(usage), Outcome::InvalidArgument);
            }
            let Some(f) = find(me, target) else {
                return reply(&mut st, s, m::not_friend(&target.label()), Outcome::NotFriends);
            };
            let Some(p) = visible(&st, f.id) else {
                return reply(&mut st, s, m::offline(m::friend_name(&f), f.last_seen, now), Outcome::NotOnline);
            };
            let to = PlayerRow { id: f.id, name: p.name.clone(), prefix: p.prefix.clone(), last_seen: f.last_seen };
            // Counted before plugins are asked: a listener that cancels (to deliver it itself) is still spared spam.
            let wait = st.profiles.get_mut(&s.id).map_or(0, |me| me.send(now, self.rate_limit));
            if wait > 0 {
                return reply(&mut st, s, m::too_fast(wait), Outcome::RateLimited);
            }
            (f, (to, body))
        };
        let (to, body) = to;
        let from = row(s, self.now());
        let Some(sent) = self.hooks.messaging(&from, &to, &body) else { return Ok(Outcome::Cancelled) };
        let mut st = self.lock();
        let now = self.now();
        // Plugins were asked without the lock: check again that there is still someone to deliver to.
        if st.profiles.get(&s.id).is_none_or(|me| !me.friends.contains_key(&f.id)) {
            return reply(&mut st, s, m::not_friend(&target.label()), Outcome::NotFriends);
        }
        let Some(away) = visible(&st, f.id).map(|p| p.status == Status::Away) else {
            return reply(&mut st, s, m::offline(m::friend_name(&f), f.last_seen, now), Outcome::NotOnline);
        };
        if let Some(me) = st.profiles.get_mut(&s.id) {
            me.reply_to = Some(Target { id: Some(f.id), name: Some(f.name.clone()) });
        }
        let _ = reply(&mut st, s, m::message_to(m::friend_name(&f), &f.name, &sent), Outcome::Success);
        self.apply(&mut st, Event::PrivateMessage { from, to: f.id, text: sent });
        // The friend "answering", in the sender's own chat (also for API sends).
        if away && st.profiles.get_mut(&s.id).is_some_and(|me| me.afk_reply_due(f.id, now)) {
            st.effects.push(Effect::Message(s.id, m::afk_reply(m::friend_name(&f), &f.name)));
        }
        Ok(Outcome::Success)
    }

    /// `/r`: a private message to whoever this player last messaged or heard from.
    pub fn reply_message(&self, s: &Online, text: &str) -> DbResult<Outcome> {
        let to = {
            let mut st = self.lock();
            let Some(me) = st.profiles.get(&s.id) else {
                return reply(&mut st, s, m::not_loaded(), Outcome::NotLoaded);
            };
            match me.reply_to.clone() {
                Some(to) => to,
                None => return reply(&mut st, s, m::nobody_to_reply(), Outcome::InvalidArgument),
            }
        };
        self.private_message(s, &to, text, "/r <message>")
    }

    /// `/f ignore <player>` (anyone who has joined): blocks their private messages and friend requests. Silently
    /// ends a friendship with them (plugins hear of it, but can't veto it) and drops pending requests either way.
    pub fn ignore(&self, s: &Online, target: &Target) -> DbResult<Outcome> {
        let mut st = self.lock();
        if !st.profiles.contains_key(&s.id) {
            return reply(&mut st, s, m::not_loaded(), Outcome::NotLoaded);
        }
        if target.is(s.id, &s.name) {
            return reply(&mut st, s, m::ignore_self(), Outcome::SelfTarget);
        }
        let Some(t) = self.resolve(&mut st, target)? else {
            return reply(&mut st, s, m::not_found(&target.label()), Outcome::PlayerNotFound);
        };
        let now = self.now();
        let mine = row(s, now);
        let name = m::row_name(&t);
        let Some(me) = st.profiles.get_mut(&s.id) else {
            return reply(&mut st, s, m::not_loaded(), Outcome::NotLoaded);
        };
        if me.ignored.contains_key(&t.id) {
            return reply(&mut st, s, m::already_ignoring(name), Outcome::Success);
        }
        me.ignored.insert(t.id, t.name.clone());
        let f = me.friends.get(&t.id).cloned();
        st.write(|db| db.add_ignore(s.id, t.id, now));
        let was_friend = f.is_some();
        if let Some(f) = f {
            // Like /f remove, minus the veto and the reply.
            st.write(|db| db.remove_friendships(s.id, &[f.id]));
            self.apply(&mut st, Event::Unfriended { player: s.id, others: vec![f.id] });
            st.effects.push(Effect::Unfriended { player: mine, friend: f });
        }
        for key in [RequestKey { from: t.id, to: s.id }, RequestKey { from: s.id, to: t.id }] {
            if st.requests.contains_key(&key) {
                self.apply(&mut st, Event::RequestDenied(key));
            }
        }
        reply(&mut st, s, m::ignored(name, was_friend), Outcome::Success)
    }

    pub fn unignore(&self, s: &Online, target: &Target) -> DbResult<Outcome> {
        let mut st = self.lock();
        let Some(me) = st.profiles.get_mut(&s.id) else {
            return reply(&mut st, s, m::not_loaded(), Outcome::NotLoaded);
        };
        let Some((id, name)) = me.ignored.iter().find(|(id, name)| target.is(**id, name)).map(|(i, n)| (*i, n.clone()))
        else {
            return reply(&mut st, s, m::not_ignoring(&target.label()), Outcome::InvalidArgument);
        };
        me.ignored.remove(&id);
        st.write(|db| db.remove_ignore(s.id, id));
        reply(&mut st, s, m::unignored(m::name(None, &name)), Outcome::Success)
    }

    /// `/f ignore` without a name.
    pub fn ignore_list(&self, s: &Online) -> DbResult<Outcome> {
        let mut st = self.lock();
        let Some(me) = st.profiles.get(&s.id) else { return reply(&mut st, s, m::not_loaded(), Outcome::NotLoaded) };
        let mut names: Vec<String> = me.ignored.values().cloned().collect();
        names.sort_by(|a, b| cmp_ignore_case(a, b));
        reply(&mut st, s, m::ignore_list(&names), Outcome::Success)
    }

    pub fn list(&self, s: &Online, page: i64, best_only: bool) -> DbResult<Outcome> {
        let mut st = self.lock();
        let now = self.now();
        let Some(me) = st.profiles.get(&s.id) else { return reply(&mut st, s, m::not_loaded(), Outcome::NotLoaded) };
        let mut entries: Vec<Entry> = me
            .friends
            .values()
            .filter(|f| !best_only || f.best)
            .map(|f| match visible(&st, f.id) {
                None => Entry { friend: f.clone(), status: None, server: None, activity: None },
                Some(p) => Entry {
                    friend: f.clone(),
                    status: Some(p.status),
                    server: p.server.clone(),
                    activity: p.activity.clone(),
                },
            })
            .collect();
        if entries.is_empty() {
            return reply(&mut st, s, m::no_friends(best_only), Outcome::Success);
        }
        // Best friends first, then online (by name), then offline (most recently seen first).
        entries.sort_by(|a, b| {
            (!a.friend.best).cmp(&!b.friend.best).then((a.status.is_none()).cmp(&b.status.is_none())).then_with(|| {
                if a.status.is_none() {
                    b.friend.last_seen.cmp(&a.friend.last_seen)
                } else {
                    cmp_ignore_case(&a.friend.name, &b.friend.name)
                }
            })
        });
        let pages = entries.len().div_ceil(PAGE_SIZE);
        let page = usize::try_from(page.clamp(1, pages as i64)).unwrap_or(1);
        let shown = &entries[(page - 1) * PAGE_SIZE..entries.len().min(page * PAGE_SIZE)];
        let text = m::list(shown, page, pages, best_only, now);
        reply(&mut st, s, text, Outcome::Success)
    }

    pub fn requests(&self, s: &Online) -> DbResult<Outcome> {
        let mut st = self.lock();
        let now = self.now();
        let incoming: Vec<Request> = incoming(&st, s.id, now).cloned().collect();
        let outgoing: Vec<Request> = outgoing(&st, s.id, now).cloned().collect();
        if incoming.is_empty() && outgoing.is_empty() {
            return reply(&mut st, s, m::no_requests(), Outcome::Success);
        }
        reply(&mut st, s, m::requests(&incoming, &outgoing, now), Outcome::Success)
    }

    // --- tab completion ---

    pub fn friend_names(&self, id: Uuid) -> Vec<String> {
        self.lock().profiles.get(&id).map(|p| p.friends.values().map(|f| f.name.clone()).collect()).unwrap_or_default()
    }

    /// Friends a private message would reach right now.
    pub fn online_friend_names(&self, id: Uuid) -> Vec<String> {
        let st = self.lock();
        st.profiles
            .get(&id)
            .map(|p| p.friends.values().filter(|f| visible(&st, f.id).is_some()).map(|f| f.name.clone()).collect())
            .unwrap_or_default()
    }

    pub fn ignored_names(&self, id: Uuid) -> Vec<String> {
        self.lock().profiles.get(&id).map(|p| p.ignored.values().cloned().collect()).unwrap_or_default()
    }

    pub fn requester_names(&self, id: Uuid) -> Vec<String> {
        let st = self.lock();
        incoming(&st, id, self.now()).map(|r| r.from.name.clone()).collect()
    }

    /// Everyone visibly online (players appearing offline are left out).
    pub fn online_names(&self) -> Vec<String> {
        self.lock().presence.values().filter(|p| p.status != Status::Offline).map(|p| p.name.clone()).collect()
    }

    // --- API reads (memory, except friends_of) ---

    pub fn loaded(&self, id: Uuid) -> bool {
        self.lock().profiles.contains_key(&id)
    }

    /// A player's cached friends (empty unless loaded here).
    pub fn cached_friends(&self, id: Uuid) -> Vec<Friend> {
        self.lock().profiles.get(&id).map(|p| p.friends.values().cloned().collect()).unwrap_or_default()
    }

    /// From memory: false unless `a` or `b` is loaded here.
    pub fn friends_with(&self, a: Uuid, b: Uuid) -> bool {
        let st = self.lock();
        match (st.profiles.get(&a), st.profiles.get(&b)) {
            (Some(p), _) => p.friends.contains_key(&b),
            (None, Some(q)) => q.friends.contains_key(&a),
            (None, None) => false,
        }
    }

    pub fn presence_of(&self, id: Uuid) -> Option<Presence> {
        self.lock().presence.get(&id).cloned()
    }

    pub fn incoming_requests(&self, id: Uuid) -> Vec<Request> {
        let st = self.lock();
        incoming(&st, id, self.now()).cloned().collect()
    }

    pub fn outgoing_requests(&self, id: Uuid) -> Vec<Request> {
        let st = self.lock();
        outgoing(&st, id, self.now()).cloned().collect()
    }

    /// Any player's friends: the profile if loaded here, else the database (cached for [`OFFLINE_TTL`]; an entry is
    /// dropped as soon as a friendship or the presence of its player changes).
    pub fn friends_of(&self, id: Uuid) -> DbResult<Vec<Friend>> {
        let mut st = self.lock();
        if let Some(p) = st.profiles.get(&id) {
            return Ok(p.friends.values().cloned().collect());
        }
        let now = self.now();
        if let Some((friends, expires)) = st.offline.get(&id)
            && now < *expires
        {
            return Ok(friends.clone());
        }
        if st.offline.len() >= OFFLINE_MAX {
            st.offline.retain(|_, (_, expires)| now < *expires);
            let excess = st.offline.len().saturating_sub(OFFLINE_MAX - 1);
            let drop: Vec<Uuid> = st.offline.keys().take(excess).copied().collect();
            for k in drop {
                st.offline.remove(&k);
            }
        }
        let friends = st.storage.load(id)?.friends;
        st.offline.insert(id, (friends.clone(), now + OFFLINE_TTL));
        Ok(friends)
    }

    // --- helpers ---

    /// Online first (no query), then the database.
    fn resolve(&self, st: &mut State, target: &Target) -> DbResult<Option<PlayerRow>> {
        let now = self.now();
        if let Some(id) = target.id {
            if let Some(p) = st.presence.get(&id) {
                return Ok(Some(PlayerRow { id, name: p.name.clone(), prefix: p.prefix.clone(), last_seen: now }));
            }
            return st.storage.player_by_id(id);
        }
        let Some(name) = target.name.as_deref().filter(|n| NAME.is_match(n)) else { return Ok(None) };
        if let Some((id, p)) = st.presence.iter().find(|(_, p)| eq_ignore_case(&p.name, name)) {
            return Ok(Some(PlayerRow { id: *id, name: p.name.clone(), prefix: p.prefix.clone(), last_seen: now }));
        }
        st.storage.player_by_name(name)
    }
}

/// Refreshes every local player's view of `id`, optionally announcing the join/leave.
fn for_friends_of(st: &mut State, id: Uuid, update: impl Fn(&Friend) -> Friend, announce: bool, joined: bool) {
    for (owner, other) in &mut st.profiles {
        let Some(f) = other.friends.get_mut(&id) else { continue };
        *f = update(f);
        if announce && other.notifications {
            let name = m::friend_name(f);
            st.effects.push(Effect::Message(*owner, if joined { m::joined(name) } else { m::left(name) }));
        }
    }
}

fn link(st: &mut State, owner: &PlayerRow, friend: &PlayerRow, since: Millis) {
    let Some(p) = st.profiles.get_mut(&owner.id) else { return };
    // Keep an existing entry (and its best/nickname flags).
    if p.friends.contains_key(&friend.id) {
        return;
    }
    p.friends.insert(
        friend.id,
        Friend {
            id: friend.id,
            name: friend.name.clone(),
            prefix: friend.prefix.clone(),
            since,
            best: false,
            nickname: None,
            last_seen: friend.last_seen,
        },
    );
    st.effects.push(Effect::Message(owner.id, m::now_friends(m::row_name(friend))));
}

fn find(me: &Profile, target: &Target) -> Option<Friend> {
    match target.id {
        Some(id) => me.friends.get(&id).cloned(),
        None => {
            me.friends.values().find(|f| target.name.as_deref().is_some_and(|n| eq_ignore_case(&f.name, n))).cloned()
        }
    }
}

/// Online and not appearing offline.
fn visible(st: &State, id: Uuid) -> Option<&Presence> {
    st.presence.get(&id).filter(|p| p.status != Status::Offline)
}

fn valid(r: Option<&Request>, now: Millis) -> Option<&Request> {
    r.filter(|r| now < r.expires)
}

fn incoming(st: &State, id: Uuid, now: Millis) -> impl Iterator<Item = &Request> {
    st.requests.values().filter(move |r| r.to.id == id && now < r.expires)
}

fn outgoing(st: &State, id: Uuid, now: Millis) -> impl Iterator<Item = &Request> {
    st.requests.values().filter(move |r| r.from.id == id && now < r.expires)
}

fn row(p: &Online, now: Millis) -> PlayerRow {
    PlayerRow { id: p.id, name: p.name.clone(), prefix: p.prefix.clone(), last_seen: now }
}

/// Whether `player` ignores `other`: from memory if `player` is loaded here, else the database.
fn ignoring(st: &mut State, player: Uuid, other: Uuid) -> DbResult<bool> {
    match st.profiles.get(&player) {
        Some(p) => Ok(p.ignored.contains_key(&other)),
        None => st.storage.ignores(player, other),
    }
}

fn friend_count(st: &mut State, id: Uuid) -> DbResult<usize> {
    match st.profiles.get(&id) {
        Some(p) => Ok(p.friends.len()),
        None => st.storage.count_friends(id),
    }
}

/// Drops section signs (legacy colour codes) and ASCII control characters (a modified client could send them, and
/// chat clients render them), then surrounding whitespace, like Java's `[§\p{Cntrl}]` and `strip()`.
fn clean(text: &str) -> String {
    let kept: String = text.chars().filter(|c| *c != '§' && !c.is_ascii_control()).collect();
    kept.trim_matches(|c: char| c.is_whitespace() && !matches!(c, '\u{00A0}' | '\u{2007}' | '\u{202F}' | '\u{0085}'))
        .to_owned()
}

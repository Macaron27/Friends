//! The API for other plugins (Java's `FriendsAPI` and its events), over Pumpkin's plugin-to-plugin messages: JSON
//! in UTF-8, so plugins in any language can use it. See README.md for the message reference.
//!
//! Requests: `{"op": "getFriends", "player": "<uuid>"}` and so on, one per `FriendsAPI` method; the reply is the
//! result as JSON. `{"op": "subscribe"}` makes Friends send the sender its events: `{"event": "FriendAddEvent",
//! "playerId": ..., "playerName": ..., "targetId": ..., "targetName": ..., "cancelled": false}`. A subscriber may
//! answer `{"cancelled": true}` to cancel a cancellable event (and `{"message": "..."}` to rewrite a private
//! message); each subscriber sees the verdict of the ones before it, like Bukkit listeners.

use std::sync::{Arc, Mutex};

use friends_core::friends::{Effect, Hooks, Target};
use friends_core::storage::StorageError;
use friends_core::{Friend, Online, Outcome, PlayerRow, Request, Session, Status};
use pumpkin_plugin_api::ipc;
use serde_json::{Map, Value, json};
use uuid::Uuid;

use crate::{Runtime, runtime};

#[derive(Clone, Default)]
pub(crate) struct Subscribers(Arc<Mutex<Vec<String>>>);

impl Subscribers {
    pub fn hooks(&self) -> IpcHooks {
        IpcHooks(self.clone())
    }

    fn list(&self) -> Vec<String> {
        self.0.lock().unwrap_or_else(|e| e.into_inner()).clone()
    }

    /// Hands `event` to each subscriber in turn and returns it with their verdicts.
    fn call(&self, mut event: Map<String, Value>) -> Map<String, Value> {
        for plugin in self.list() {
            let Ok(bytes) = serde_json::to_vec(&event) else { return event };
            match ipc::send_ipc_message(&plugin, &bytes) {
                Ok(Ok(reply)) => {
                    if let Ok(Value::Object(answer)) = serde_json::from_slice::<Value>(&reply) {
                        for key in ["cancelled", "message"] {
                            if let Some(v) = answer.get(key).filter(|v| v.is_boolean() || v.is_string()) {
                                event.insert(key.to_owned(), v.clone());
                            }
                        }
                    }
                }
                Ok(Err(e)) => tracing::warn!("Friends: plugin {plugin} failed handling {}: {e}", event["event"]),
                Err(()) => tracing::debug!("Friends: plugin {plugin} is unavailable"),
            }
        }
        event
    }

    fn cancelled(&self, event: Value) -> bool {
        let Value::Object(map) = event else { return false };
        if self.list().is_empty() {
            return false;
        }
        self.call(map).get("cancelled").and_then(Value::as_bool).unwrap_or(false)
    }

    /// The "done" events (Java's `FriendAddedEvent`, `FriendRemovedEvent`).
    pub fn done(&self, effect: &Effect) {
        if self.list().is_empty() {
            return;
        }
        let event = match effect {
            Effect::Befriended { player, sender, since } => {
                pair("FriendAddedEvent", player.id, &player.name, sender.id, &sender.name).with("since", json!(since))
            }
            Effect::Unfriended { player, friend } => {
                pair("FriendRemovedEvent", player.id, &player.name, friend.id, &friend.name)
            }
            Effect::Message(..) => return,
        };
        if let Value::Object(map) = event {
            self.call(map);
        }
    }
}

trait With {
    fn with(self, key: &str, value: Value) -> Value;
}

impl With for Value {
    fn with(mut self, key: &str, value: Value) -> Value {
        if let Value::Object(map) = &mut self {
            map.insert(key.to_owned(), value);
        }
        self
    }
}

fn pair(event: &str, player: Uuid, player_name: &str, target: Uuid, target_name: &str) -> Value {
    json!({
        "event": event,
        "playerId": player.to_string(),
        "playerName": player_name,
        "targetId": target.to_string(),
        "targetName": target_name,
        "cancelled": false,
    })
}

/// The core's vetoes as cancellable events.
pub(crate) struct IpcHooks(Subscribers);

impl Hooks for IpcHooks {
    fn requesting(&self, from: &PlayerRow, to: &PlayerRow) -> bool {
        !self.0.cancelled(pair("FriendRequestSendEvent", from.id, &from.name, to.id, &to.name))
    }

    fn befriending(&self, player: &PlayerRow, sender: &PlayerRow) -> bool {
        !self.0.cancelled(pair("FriendAddEvent", player.id, &player.name, sender.id, &sender.name))
    }

    fn unfriending(&self, player: &PlayerRow, friend: &Friend) -> bool {
        !self.0.cancelled(pair("FriendRemoveEvent", player.id, &player.name, friend.id, &friend.name))
    }

    fn messaging(&self, from: &PlayerRow, to: &PlayerRow, message: &str) -> Option<String> {
        if self.0.list().is_empty() {
            return Some(message.to_owned());
        }
        let Value::Object(event) =
            pair("FriendMessageEvent", from.id, &from.name, to.id, &to.name).with("message", json!(message))
        else {
            return Some(message.to_owned());
        };
        let event = self.0.call(event);
        if event.get("cancelled").and_then(Value::as_bool).unwrap_or(false) {
            return None;
        }
        Some(event.get("message").and_then(Value::as_str).unwrap_or(message).to_owned())
    }

    fn status_changing(&self, player: &PlayerRow, from: Status, to: Status) -> bool {
        !self.0.cancelled(json!({
            "event": "FriendStatusChangeEvent",
            "playerId": player.id.to_string(),
            "playerName": player.name,
            "oldStatus": from.name(),
            "newStatus": to.name(),
            "cancelled": false,
        }))
    }
}

fn friend_json(f: &Friend) -> Value {
    json!({ "id": f.id.to_string(), "name": f.name, "since": f.since, "best": f.best, "nickname": f.nickname, "lastSeen": f.last_seen })
}

fn request_json(r: &Request) -> Value {
    json!({
        "sender": r.from.id.to_string(),
        "senderName": r.from.name,
        "target": r.to.id.to_string(),
        "targetName": r.to.name,
        "expiresAt": r.expires,
    })
}

fn uuid_arg(request: &Value, key: &str) -> Result<Uuid, String> {
    let s = request.get(key).and_then(Value::as_str).ok_or_else(|| format!("missing \"{key}\" (a UUID)"))?;
    Uuid::parse_str(s).map_err(|_| format!("\"{key}\": '{s}' is not a UUID"))
}

/// One API request from plugin `sender`.
pub(crate) fn handle(sender: &str, message: &[u8]) -> Result<Vec<u8>, String> {
    let request: Value = serde_json::from_slice(message).map_err(|e| format!("not a JSON request: {e}"))?;
    let op = request.get("op").and_then(Value::as_str).ok_or("missing \"op\"")?;
    let rt = runtime().ok_or("Friends is not running")?;
    let reply = answer(&rt, sender, op, &request)?;
    serde_json::to_vec(&reply).map_err(|e| e.to_string())
}

fn answer(rt: &Runtime, sender: &str, op: &str, request: &Value) -> Result<Value, String> {
    let f = &rt.friends;
    let player = || uuid_arg(request, "player");
    Ok(match op {
        "subscribe" => {
            let mut list = rt.api.0.lock().unwrap_or_else(|e| e.into_inner());
            if !list.iter().any(|p| p == sender) {
                list.push(sender.to_owned());
            }
            json!(true)
        }
        "unsubscribe" => {
            rt.api.0.lock().unwrap_or_else(|e| e.into_inner()).retain(|p| p != sender);
            json!(true)
        }
        "isLoaded" => json!(f.loaded(player()?)),
        "getFriends" => Value::Array(f.cached_friends(player()?).iter().map(friend_json).collect()),
        "areFriends" => json!(f.friends_with(uuid_arg(request, "a")?, uuid_arg(request, "b")?)),
        "getStatus" => f.presence_of(player()?).map_or(Value::Null, |p| json!(p.status.name())),
        "getServer" => f
            .presence_of(player()?)
            .filter(|p| p.status != Status::Offline)
            .and_then(|p| p.server)
            .map_or(Value::Null, Value::from),
        "getActivity" => f
            .presence_of(player()?)
            .filter(|p| p.status != Status::Offline)
            .and_then(|p| p.activity)
            .map_or(Value::Null, |a| json!({ "game": a.game, "mode": a.mode, "description": a.describe() })),
        "getIncomingRequests" => Value::Array(f.incoming_requests(player()?).iter().map(request_json).collect()),
        "getOutgoingRequests" => Value::Array(f.outgoing_requests(player()?).iter().map(request_json).collect()),
        "loadFriends" => match f.friends_of(player()?) {
            Ok(friends) => Value::Array(friends.iter().map(friend_json).collect()),
            Err(e) => {
                tracing::error!("Friends database error: {e}");
                return Err("database error".into());
            }
        },
        "sendRequest" => {
            let target = Target::of(uuid_arg(request, "target")?);
            act(rt, uuid_arg(request, "sender")?, |s| f.add(s, &target))
        }
        "acceptRequest" => {
            let sender = Target::of(uuid_arg(request, "sender")?);
            act(rt, player()?, |s| f.accept(s, &sender))
        }
        "denyRequest" => {
            let sender = Target::of(uuid_arg(request, "sender")?);
            act(rt, player()?, |s| f.deny(s, &sender))
        }
        "removeFriend" => {
            let friend = Target::of(uuid_arg(request, "friend")?);
            act(rt, player()?, |s| f.remove(s, &friend))
        }
        "setBestFriend" => {
            let best = request.get("best").and_then(Value::as_bool).ok_or("missing \"best\"")?;
            let friend = Target::of(uuid_arg(request, "friend")?);
            act(rt, player()?, |s| f.best(s, &friend, Some(best)))
        }
        "setNickname" => {
            let nickname = request.get("nickname").and_then(Value::as_str);
            let friend = Target::of(uuid_arg(request, "friend")?);
            act(rt, player()?, |s| f.nickname(s, &friend, nickname))
        }
        "setStatus" => {
            match request.get("status").and_then(Value::as_str).and_then(|n| Status::from_name(&n.to_uppercase())) {
                Some(status) => act(rt, player()?, |s| f.status(s, Some(status))),
                None => json!(Outcome::InvalidArgument.name()),
            }
        }
        "sendMessage" => {
            let receiver = Target::of(uuid_arg(request, "receiver")?);
            match request.get("message").and_then(Value::as_str) {
                Some(text) => act(rt, uuid_arg(request, "sender")?, |s| f.message(s, &receiver, text)),
                None => json!(Outcome::InvalidArgument.name()),
            }
        }
        other => return Err(format!("unknown op \"{other}\"")),
    })
}

/// Runs `action` as `player`'s command without the chat reply (Java's `FriendsApi.act`); the result is the name of
/// the `Result` constant, e.g. "SUCCESS".
fn act(rt: &Runtime, player: Uuid, action: impl FnOnce(&Online) -> Result<Outcome, StorageError>) -> Value {
    let Some(online) = rt.connected(player) else { return json!(Outcome::NotLoaded.name()) };
    let silent = Online { session: Session::Silent, ..online };
    let outcome = match action(&silent) {
        Ok(outcome) => outcome,
        Err(e) => {
            tracing::error!("Friends database error: {e}");
            Outcome::Error
        }
    };
    rt.deliver_anywhere();
    json!(outcome.name())
}

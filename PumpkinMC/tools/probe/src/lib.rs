//! A test plugin using Friends' API over IPC, for the end-to-end tests (like tools/e2e/probe for the Java
//! platforms). It subscribes to Friends' events and broadcasts each one, after asking Friends a question from inside
//! the event (a call back into Friends while Friends is calling it); it cancels `/status away`, rewrites "darn" in
//! private messages, and `/probe friends` / `/probe request <player>` / `/probe best <player>` use reads and actions.

use pumpkin_plugin_api::command::{
    Arg, ArgumentType, Command, CommandError, CommandNode, CommandSender, ConsumedArgs, StringType,
};
use pumpkin_plugin_api::commands::CommandHandler;
use pumpkin_plugin_api::permission::{Permission, PermissionDefault};
use pumpkin_plugin_api::scheduler::SchedulerExt;
use pumpkin_plugin_api::text::TextComponent;
use pumpkin_plugin_api::{Context, Plugin, PluginMetadata, Server, ipc, register_plugin};
use serde_json::{Value, json};

fn friends(request: Value) -> Result<Value, String> {
    let reply = ipc::send_ipc_message("friends", &request.to_string().into_bytes())
        .map_err(|()| "friends unavailable".to_owned())??;
    serde_json::from_slice(&reply).map_err(|e| e.to_string())
}

fn uuid_string(id: pumpkin_plugin_api::uuid::Uuid) -> String {
    format!("{:016x}{:016x}", id.high, id.low)
        .char_indices()
        .flat_map(|(i, c)| if matches!(i, 8 | 12 | 16 | 20) { vec!['-', c] } else { vec![c] })
        .collect()
}

thread_local! {
    static SERVER: std::cell::RefCell<Option<Server>> = const { std::cell::RefCell::new(None) };
}

struct Probe;

struct ProbeCommand;

impl CommandHandler for ProbeCommand {
    fn handle(&self, sender: CommandSender, server: Server, args: ConsumedArgs) -> Result<i32, CommandError> {
        let Some(player) = sender.as_player() else { return Ok(0) };
        let me = uuid_string(player.get_id());
        let line = match args.get_value("args") {
            Arg::Simple(s) | Arg::Msg(s) => s,
            _ => String::new(),
        };
        let words: Vec<&str> = line.split_whitespace().collect();
        let target = |name: &str| server.get_player_by_name(name).map(|p| uuid_string(p.get_id())).unwrap_or_default();
        let answer = match words.as_slice() {
            ["friends"] => friends(json!({"op": "getFriends", "player": me})),
            ["request", name] => friends(json!({"op": "sendRequest", "sender": me, "target": target(name)})),
            ["best", name] => {
                friends(json!({"op": "setBestFriend", "player": me, "friend": target(name), "best": true}))
            }
            ["status", name] => friends(json!({"op": "getStatus", "player": target(name)})),
            _ => Err("usage: /probe friends|request <player>|best <player>|status <player>".to_owned()),
        };
        let text = match answer {
            Ok(v) => format!("[probe] {v}"),
            Err(e) => format!("[probe] error: {e}"),
        };
        player.send_system_message(TextComponent::text(&text), false);
        Ok(1)
    }
}

impl Plugin for Probe {
    fn new() -> Self {
        Probe
    }

    fn metadata(&self) -> PluginMetadata {
        PluginMetadata {
            name: "friends-probe".into(),
            version: env!("CARGO_PKG_VERSION").into(),
            authors: vec!["Macaron27".into()],
            description: "Friends API test plugin".into(),
            dependencies: vec![],
            permissions: vec![],
        }
    }

    fn on_load(&self, context: Context) -> Result<(), String> {
        let names = ["probe".to_owned()];
        let command = Command::new(&names, "Friends API probe")
            .then(CommandNode::argument("args", &ArgumentType::String(StringType::Greedy)).execute(ProbeCommand));
        context.register_permission(&Permission {
            node: "friends-probe:use".into(),
            description: "Use /probe".into(),
            default: PermissionDefault::Allow,
            children: vec![],
        })?;
        context.register_command(command, "friends-probe:use");
        SERVER.with(|s| *s.borrow_mut() = Some(context.get_server()));
        // Subscribe once every plugin is loaded, whatever the load order.
        context.schedule_delayed_task(20, |_server| match friends(json!({"op": "subscribe"})) {
            Ok(_) => {
                tracing::info!("[probe] subscribed");
            }
            Err(e) => tracing::error!("probe: subscribe failed: {e}"),
        });
        Ok(())
    }

    fn handle_ipc_message(&self, sender: String, message: Vec<u8>) -> Result<Vec<u8>, String> {
        let event: Value = serde_json::from_slice(&message).map_err(|e| e.to_string())?;
        if sender != "friends" {
            return Err("not from friends".into());
        }
        let name = event["event"].as_str().unwrap_or_default().to_owned();
        // A read from inside the event: re-enters Friends while it waits for this answer.
        let player = event["playerId"].as_str().unwrap_or_default();
        let count = friends(json!({"op": "getFriends", "player": player}))
            .map(|v| v.as_array().map_or(0, Vec::len).to_string())
            .unwrap_or_else(|e| format!("error {e}"));
        let mut answer = json!({});
        match name.as_str() {
            "FriendStatusChangeEvent" if event["newStatus"] == "AWAY" => answer = json!({"cancelled": true}),
            "FriendMessageEvent" => {
                let text = event["message"].as_str().unwrap_or_default().replace("darn", "****");
                answer = json!({"message": text});
            }
            _ => {}
        }
        let summary = format!(
            "[probe] {name} {} -> {} (friends: {count}){}",
            event["playerName"].as_str().unwrap_or_default(),
            event["targetName"].as_str().or(event["newStatus"].as_str()).unwrap_or_default(),
            if answer.get("cancelled").is_some() { " cancelled" } else { "" }
        );
        tracing::info!("{summary}");
        SERVER.with(|s| {
            if let Some(server) = s.borrow().as_ref() {
                // System chat to everyone (`broadcast` sends player-style chat from "Server").
                for player in server.get_all_players() {
                    player.send_system_message(TextComponent::text(&summary), false);
                }
            }
        });
        Ok(answer.to_string().into_bytes())
    }
}

register_plugin!(Probe);

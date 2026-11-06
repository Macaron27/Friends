//! Friends for Pumpkin: the Paper plugin's glue (`PaperFriends`) as a Pumpkin WebAssembly plugin. Everything else
//! is `friends-core`.
//!
//! What maps to what:
//! - plugin.yml commands `/friend` (`/f`, `/friends`), `/fl`, `/status` and the runtime-registered `/msg` (`/tell`,
//!   `/w`, `/whisper`), `/r` (`/reply`): Pumpkin commands taking the rest of the line, parsed by `FriendCommand` as
//!   in Java. Vanilla owns `/msg`, `/tell` and `/w` here, so those are taken over from the command event instead.
//! - permission `friends.use` (default true): `friends:use` (default allow); Pumpkin namespaces plugin permissions.
//! - `PlayerJoinEvent` / `PlayerQuitEvent` listeners: `PlayerJoinEvent` / `PlayerLeaveEvent` handlers.
//! - the Bukkit API events (`FriendAddEvent`...): IPC messages to subscribed plugins, see `api`.
//! - `plugins/Friends/config.yml` and `friends.db`: the plugin's data folder (`plugins/data/friends/`).

mod api;
mod chat;

use std::cell::RefCell;
use std::collections::HashMap;
use std::sync::{Arc, Mutex, RwLock};
use std::time::{SystemTime, UNIX_EPOCH};

use friends_core::command::{SUBCOMMANDS, split_args, with_sub};
use friends_core::config_upgrade;
use friends_core::settings::YamlSource;
use friends_core::{BUNDLED_CONFIG, Effect, FriendCommand, Friends, Millis, Online, Session, Settings, Storage};
use pumpkin_plugin_api::command::{
    Arg, ArgumentType, Command, CommandError, CommandNode, CommandSender, ConsumedArgs, StringType,
};
use pumpkin_plugin_api::commands::CommandHandler;
use pumpkin_plugin_api::common::NamedColor;
use pumpkin_plugin_api::events::{
    EventHandler, EventPriority, PlayerCommandSendEvent, PlayerJoinEvent, PlayerLeaveEvent,
};
use pumpkin_plugin_api::events_wit::{PlayerCommandSendEventData, PlayerJoinEventData, PlayerLeaveEventData};
use pumpkin_plugin_api::permission::{Permission, PermissionDefault};
use pumpkin_plugin_api::scheduler::{SchedulerExt, cancel_task};
use pumpkin_plugin_api::text::TextComponent;
use pumpkin_plugin_api::{Context, Player, Plugin, PluginMetadata, Server, permissions, register_plugin};

/// The plugin's name: Pumpkin requires its permissions to live in this namespace.
const NAME: &str = "friends";
/// Bukkit's `friends.use`.
const PERMISSION: &str = "friends:use";
/// Vanilla's private-message commands, which Friends takes over like it does on Paper.
const VANILLA_MSG: [&str; 3] = ["msg", "tell", "w"];

pub(crate) struct Runtime {
    pub friends: Friends,
    pub command: FriendCommand,
    private_messages: bool,
    /// Connected players (Java's `ChatSessions`), by id.
    sessions: Mutex<HashMap<uuid::Uuid, Online>>,
    pub api: api::Subscribers,
    timer: u32,
}

static RUNTIME: RwLock<Option<Arc<Runtime>>> = RwLock::new(None);

thread_local! {
    // For delivering chat outside a server callback (IPC calls). A plugin runs on one thread.
    static SERVER: RefCell<Option<Server>> = const { RefCell::new(None) };
}

pub(crate) fn runtime() -> Option<Arc<Runtime>> {
    RUNTIME.read().unwrap_or_else(|e| e.into_inner()).clone()
}

fn now() -> Millis {
    SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| Millis::try_from(d.as_millis()).unwrap_or(Millis::MAX))
}

impl Runtime {
    /// The player's session as the core sees it. The entity id tells two sessions of one player apart.
    fn online(&self, player: &Player) -> Online {
        let id = chat::uuid(player.get_id());
        let session = Session::Player(u64::from(player.as_entity().get_id()));
        self.sessions
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get(&id)
            .filter(|o| o.session == session)
            .cloned()
            .unwrap_or_else(|| Online { id, name: player.get_name(), prefix: None, server: None, session })
    }

    pub(crate) fn connected(&self, id: uuid::Uuid) -> Option<Online> {
        self.sessions.lock().unwrap_or_else(|e| e.into_inner()).get(&id).cloned()
    }

    fn join(&self, server: &Server, player: &Player) {
        let online = self.online(player);
        // Non-blocking events may run late: the player must still be here, in this session.
        let here = server
            .get_player_by_uuid(chat::wit_uuid(online.id))
            .map(|p| Session::Player(u64::from(p.as_entity().get_id())));
        if here != Some(online.session) {
            return;
        }
        self.sessions.lock().unwrap_or_else(|e| e.into_inner()).insert(online.id, online.clone());
        self.friends.connect(&online);
        self.friends.greet(&online);
        self.deliver(server);
    }

    fn leave(&self, server: &Server, player: &Player) {
        let online = self.online(player);
        self.friends.disconnect(&online);
        let mut sessions = self.sessions.lock().unwrap_or_else(|e| e.into_inner());
        if sessions.get(&online.id).is_some_and(|o| o.session == online.session) {
            sessions.remove(&online.id);
        }
        drop(sessions);
        self.deliver(server);
    }

    /// Sends what the core produced: chat to players here, "done" events to subscribed plugins. Never called with
    /// a lock held (these calls may re-enter the plugin).
    pub(crate) fn deliver(&self, server: &Server) {
        for effect in self.friends.drain_effects() {
            match effect {
                Effect::Message(id, text) => {
                    if let Some(player) = server.get_player_by_uuid(chat::wit_uuid(id)) {
                        player.send_system_message(chat::component(&text), false);
                    }
                }
                other => self.api.done(&other),
            }
        }
    }

    pub(crate) fn deliver_anywhere(&self) {
        SERVER.with(|s| {
            if let Some(server) = s.borrow().as_ref() {
                self.deliver(server);
            }
        });
    }
}

/// `/friend`, `/fl` ("list"), `/status` ("status"), `/whisper` ("msg") and `/r` ("reply"): the rest of the line goes to
/// `FriendCommand` as Bukkit-style arguments. `word` is the subcommand literal the player typed, if any.
struct Execute {
    sub: Option<&'static str>,
    word: Option<&'static str>,
}

impl CommandHandler for Execute {
    fn handle(&self, sender: CommandSender, server: Server, args: ConsumedArgs) -> Result<i32, CommandError> {
        let Some(player) = sender.as_player() else {
            sender.send_message(TextComponent::text("Only players can use this command.").color_named(NamedColor::Red));
            return Ok(0);
        };
        let Some(rt) = runtime() else { return Ok(0) };
        let line = match args.get_value("args") {
            Arg::Simple(s) | Arg::Msg(s) => s,
            _ => String::new(),
        };
        let mut words = split_args(&line);
        if let Some(word) = self.word {
            words.insert(0, word.to_owned());
        }
        let online = rt.online(&player);
        rt.command.execute(&rt.friends, &online, &with_sub(self.sub, words));
        rt.deliver(&server);
        Ok(1)
    }
}

/// A command whose arguments `FriendCommand` parses: `words` become literal nodes (so Pumpkin completes them), each
/// followed by the rest of the line; any other input goes through the catch-all argument, as in Java (`/f Steve`).
///
/// No server-side suggestions (`CommandNode::suggest`): Pumpkin's v0.1 Wasm host panics on the tick thread when a
/// player tab-completes such an argument, which stops the server (nightly of 2026-11-06). Player names therefore
/// aren't completed; `FriendCommand::suggest` is ready for when that is fixed.
fn command(names: &[&str], description: &str, sub: Option<&'static str>, words: &[&'static str]) -> Command {
    let names: Vec<String> = names.iter().map(|n| (*n).to_owned()).collect();
    let rest = || ArgumentType::String(StringType::Greedy);
    let mut command = Command::new(&names, description);
    for word in words {
        command = command.then(
            CommandNode::literal(word)
                .then(CommandNode::argument("args", &rest()).execute(Execute { sub, word: Some(word) }))
                .execute(Execute { sub, word: Some(word) }),
        );
    }
    command
        .then(CommandNode::argument("args", &rest()).execute(Execute { sub, word: None }))
        .execute(Execute { sub, word: None })
}

struct Join;

impl EventHandler<PlayerJoinEvent> for Join {
    fn handle(&self, server: Server, event: PlayerJoinEventData) -> PlayerJoinEventData {
        if let Some(rt) = runtime() {
            rt.join(&server, &event.player);
        }
        event
    }
}

struct Leave;

impl EventHandler<PlayerLeaveEvent> for Leave {
    fn handle(&self, server: Server, event: PlayerLeaveEventData) -> PlayerLeaveEventData {
        if let Some(rt) = runtime() {
            rt.leave(&server, &event.player);
        }
        event
    }
}

/// Takes over vanilla's `/msg`, `/tell` and `/w` before they are dispatched (the Paper plugin replaces them in the
/// command map). Players without `friends:use` keep vanilla's.
struct VanillaMsg;

impl EventHandler<PlayerCommandSendEvent> for VanillaMsg {
    fn handle(&self, server: Server, mut event: PlayerCommandSendEventData) -> PlayerCommandSendEventData {
        let (name, rest) = event.command.split_once(' ').unwrap_or((&event.command, ""));
        if event.cancelled
            || !VANILLA_MSG.iter().any(|v| v.eq_ignore_ascii_case(name))
            || !event.player.has_permission(PERMISSION)
        {
            return event;
        }
        let Some(rt) = runtime() else { return event };
        let args = with_sub(Some("msg"), split_args(rest));
        event.cancelled = true;
        rt.command.execute(&rt.friends, &rt.online(&event.player), &args);
        rt.deliver(&server);
        event
    }
}

struct FriendsPlugin;

impl FriendsPlugin {
    fn enable(context: &Context) -> Result<(), String> {
        let folder = context.get_data_folder();
        let config = format!("{folder}/config.yml");
        if !std::path::Path::new(&config).exists() {
            std::fs::create_dir_all(&folder).map_err(|e| format!("can't create {folder}: {e}"))?;
            std::fs::write(&config, BUNDLED_CONFIG).map_err(|e| format!("can't write {config}: {e}"))?;
        }
        match config_upgrade::add_missing_sections(std::path::Path::new(&config), BUNDLED_CONFIG) {
            Ok(added) if !added.is_empty() => tracing::info!("Friends: added the new settings {added:?} to config.yml"),
            Ok(_) => {}
            Err(e) => tracing::warn!("Friends: could not add new settings to config.yml ({e}); using their defaults"),
        }
        let text = std::fs::read_to_string(&config).map_err(|e| format!("can't read {config}: {e}"))?;
        let settings = Settings::read(&YamlSource::parse(&text)?)?;
        if settings.redis.enabled {
            tracing::warn!("Friends: redis (multi-proxy) is for Velocity/BungeeCord; ignored on Pumpkin");
        }
        for warning in &settings.warnings {
            tracing::warn!("Friends config: {warning}");
        }
        let storage = if settings.uses_mysql() {
            let m = &settings.mysql;
            Storage::mysql(&m.host, m.port, &m.database, &m.user, &m.password, m.ssl)
        } else {
            Storage::sqlite(std::path::Path::new(&format!("{folder}/{}", settings.sqlite_file)))
        }
        .map_err(|e| e.to_string())?;
        tracing::info!(
            "Friends: {} presence rule(s); private messages {} (rate limit: {})",
            settings.activities.len(),
            if settings.private_messages { "on /msg and /r" } else { "off" },
            settings.rate_limit
        );

        let subscribers = api::Subscribers::default();
        let friends = Friends::new(
            storage,
            Box::new(now),
            settings.request_expiry,
            settings.max_friends,
            settings.rate_limit,
            settings.activities,
            Box::new(subscribers.hooks()),
        );
        let timer = context.schedule_repeating_task(20, 20, |server| {
            if let Some(rt) = runtime() {
                rt.friends.expire_requests();
                rt.deliver(&server);
            }
        });
        let rt = Arc::new(Runtime {
            friends,
            command: FriendCommand::new(settings.private_messages),
            private_messages: settings.private_messages,
            sessions: Mutex::new(HashMap::new()),
            api: subscribers,
            timer,
        });
        *RUNTIME.write().unwrap_or_else(|e| e.into_inner()) = Some(rt.clone());
        SERVER.with(|s| *s.borrow_mut() = Some(context.get_server()));

        context.register_permission(&Permission {
            node: PERMISSION.to_owned(),
            description: "Use the friends commands".to_owned(),
            default: PermissionDefault::Allow,
            children: Vec::new(),
        })?;
        context
            .register_command(command(&["friend", "f", "friends"], "Friends commands", None, &SUBCOMMANDS), PERMISSION);
        context.register_command(command(&["fl"], "List your friends", Some("list"), &["best"]), PERMISSION);
        let statuses = ["online", "away", "busy", "offline"];
        context.register_command(
            command(&["status"], "Set the status your friends see", Some("status"), &statuses),
            PERMISSION,
        );
        if rt.private_messages {
            context.register_command(
                command(&["whisper"], "Send a friend a private message", Some("msg"), &[]),
                PERMISSION,
            );
            context.register_command(
                command(&["r", "reply"], "Reply to your last private message", Some("reply"), &[]),
                PERMISSION,
            );
            context.register_event_handler(VanillaMsg, EventPriority::Normal, true)?;
        }
        context.register_event_handler(Join, EventPriority::Normal, false)?;
        context.register_event_handler(Leave, EventPriority::Normal, false)?;

        // Loaded while players are online (hot reload): they join now.
        let server = context.get_server();
        for player in server.get_all_players() {
            rt.join(&server, &player);
        }
        Ok(())
    }
}

impl Plugin for FriendsPlugin {
    fn new() -> Self {
        FriendsPlugin
    }

    fn metadata(&self) -> PluginMetadata {
        PluginMetadata {
            name: NAME.into(),
            version: env!("CARGO_PKG_VERSION").into(),
            authors: vec!["Macaron27".into()],
            description: "Hypixel-style friends system".into(),
            dependencies: vec![],
            // The data folder holds config.yml and the SQLite database; MySQL needs DNS and outgoing TCP.
            permissions: vec![
                permissions::FS_WRITE_DATA.into(),
                permissions::NETWORK_DNS.into(),
                permissions::NETWORK_TCP_CONNECT.into(),
            ],
        }
    }

    fn on_load(&self, context: Context) -> pumpkin_plugin_api::Result<()> {
        FriendsPlugin::enable(&context).map_err(|e| {
            tracing::error!("Friends is disabled: {e}");
            if let Some(rt) = RUNTIME.write().unwrap_or_else(|e| e.into_inner()).take() {
                cancel_task(rt.timer);
            }
            e
        })
    }

    fn on_unload(&self, context: Context) -> pumpkin_plugin_api::Result<()> {
        let Some(rt) = RUNTIME.write().unwrap_or_else(|e| e.into_inner()).take() else { return Ok(()) };
        cancel_task(rt.timer);
        // Shutdown or reload: players may still be online.
        let server = context.get_server();
        let online: Vec<Online> = rt.sessions.lock().unwrap_or_else(|e| e.into_inner()).values().cloned().collect();
        for player in &online {
            rt.friends.disconnect(player);
        }
        rt.deliver(&server);
        SERVER.with(|s| *s.borrow_mut() = None);
        Ok(())
    }

    fn handle_ipc_message(&self, sender: String, message: Vec<u8>) -> pumpkin_plugin_api::Result<Vec<u8>, String> {
        api::handle(&sender, &message)
    }
}

register_plugin!(FriendsPlugin);

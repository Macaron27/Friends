//! Friends' platform-agnostic core, ported from the Java plugin's `com.friends.core` (and the API models of
//! `com.friends.api`). Nothing here knows about Pumpkin: the `friends-pumpkin` crate is the glue.

pub mod activities;
pub mod command;
pub mod config_upgrade;
pub mod friends;
pub mod last_seen;
pub mod messages;
pub mod model;
pub mod settings;
pub mod storage;
pub mod text;

pub use command::FriendCommand;
pub use friends::{Effect, Friends, Hooks, NoHooks, Target};
pub use model::{Friend, Millis, Online, Outcome, PlayerActivity, PlayerRow, Presence, Request, Session, Status};
pub use settings::{BUNDLED_CONFIG, Settings};
pub use storage::Storage;
pub use text::Text;

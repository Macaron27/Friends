# Friends API

Lets other plugins read friend lists, presence and requests, act on behalf of players and react to friend events.
The same `FriendsAPI` exists on Paper, Velocity and BungeeCord. Each platform also has its events
(`com.friends.api.bukkit`, `.velocity`, `.bungee`: seven, plus `PlayerActivityChangeEvent` on the proxies). The API is Java 8 bytecode with no dependencies of its own, so
any plugin can compile against it. Every public type and method has Javadoc (`./gradlew :api:javadoc`).

## 1. Add the dependency

Friends provides the API at runtime, so compile against it without shading it.

Use the `friends-api.jar` from the GitHub release:

```kotlin
dependencies { compileOnly(files("libs/friends-api.jar")) }
```

Or install it in your local Maven repository from this repository:

```bash
./gradlew :api:publishToMavenLocal
```

```kotlin
repositories { mavenLocal() }
dependencies { compileOnly("com.friends:friends-api:0.7.0") }
```

Then make your plugin load after Friends:

| Platform | Declaration |
|---|---|
| Paper | `depend: [Friends]` (or `softdepend`) in plugin.yml |
| BungeeCord | `depends: [Friends]` (or `softDepends`) in bungee.yml |
| Velocity | `@Plugin(..., dependencies = @Dependency(id = "friends"))` (`optional = true` for a soft dependency) |

## 2. Get the API

```java
FriendsAPI friends = FriendsAPI.get();                // throws IllegalStateException if Friends isn't enabled
Optional<FriendsAPI> maybe = FriendsAPI.find();       // for soft dependencies
// Paper only, same instance: getServer().getServicesManager().load(FriendsAPI.class)
```

Fetch it again after Friends reloads instead of keeping it in a static field.

## 3. Read (memory only, fine on the server thread)

```java
boolean loaded = friends.isLoaded(player);            // online here and loaded
List<Friend> list = friends.getFriends(player);        // empty unless loaded
boolean pals = friends.areFriends(alice, bob);        // at least one of them must be loaded
Optional<Status> status = friends.getStatus(player);  // online anywhere: ONLINE, AWAY, BUSY, OFFLINE (appears offline)
Optional<String> server = friends.getServer(player);  // backend server name, proxies only (empty on Paper)
Optional<PlayerActivity> doing = friends.getActivity(player); // from presence.rules, proxies only (see below)
List<FriendRequest> in = friends.getIncomingRequests(player);
List<FriendRequest> out = friends.getOutgoingRequests(player);
```

`Friend` gives `getUniqueId()`, `getName()`, `getSince()`, `isBestFriend()`, `getNickname()` and `getLastSeen()`.
`PlayerActivity` gives `getGame()`, `getMode()` (null if the rule has none) and `describe()` (`"Playing BedWars Solo"`):
the first `presence.rules` pattern matching the player's backend server, synchronised across proxies like the server.

For menus, scoreboards and other UIs, `LastSeen` words times like Friends' chat does:

```java
String online = friends.getActivity(id).map(PlayerActivity::describe).orElse("Online");
String offline = LastSeen.format(friend.getLastSeen());           // "Last seen 17 minutes ago"
String ago = LastSeen.ago(friend.getLastSeen(), Instant.now());   // "17 minutes ago", "3 days ago", "just now"

// Outside the game (websites, Discord, menus with emoji fonts): one string with an emoji.
boolean visible = friends.getStatus(id).filter(s -> s != Status.OFFLINE).isPresent();
LastSeen.presence(visible, friend.getLastSeen());                  // "🟢 Online" or "⚫ Last seen 17 minutes ago"
LastSeen.line(friend.getName(), visible, friends.getActivity(id).orElse(null), friend.getLastSeen());
                                                                   // "🟢 Bob — Playing BedWars Solo", "⚫ Bob — Last seen 3 days ago"
```

In game, Friends' own chat uses a coloured `●` instead: 1.8 clients' fonts have no `🟢`.
`FriendRequest` gives `getSender()`, `getSenderName()`, `getTarget()`, `getTargetName()` and `getExpiresAt()`.

## 4. Load and act (asynchronous)

These methods return a `CompletableFuture` at once and do their work, database access included, on Friends' threads.

```java
friends.loadFriends(anyone)                           // offline players come from the database (cached 30 s)
        .thenAccept(list -> ...);

friends.sendRequest(alice, bob).thenAccept(result -> {
    if (result.isSuccess()) ...                       // SUCCESS (sent) or BECAME_FRIENDS (bob had asked alice)
    else if (result == Result.CANCELLED) ...          // a plugin cancelled the event
    else ...                                          // NOT_LOADED, ALREADY_FRIENDS, LIMIT_REACHED, ... (see Result)
});
```

The actions are `sendRequest`, `acceptRequest`, `denyRequest`, `removeFriend`, `setBestFriend`, `setNickname`,
`setStatus` and `sendMessage`. Each one behaves like its command, events and limits included. The acting player must
be loaded, and gets no chat reply: the `Result` tells you what happened. The other player is still notified.

```java
friends.sendMessage(alice, bob, "GG!").thenAccept(result -> {
    // SUCCESS (delivered), NOT_FRIENDS, NOT_ONLINE, RATE_LIMITED (private-messages.rate-limit), CANCELLED (an event
    // listener took over), NOT_LOADED, INVALID_ARGUMENT
});
```

`sendMessage` is `/msg`: friends only, rate-limited, and it fires `FriendMessageEvent`. If Bob's status is away, Alice
still gets his AFK auto-reply in her chat. Someone who ignores Alice has also removed her as a friend, so she gets
`NOT_FRIENDS`: an ignore is never revealed. `sendRequest` between two players where one ignores the other gives
`IGNORED`.

## 5. Listen to events

| Event | Cancellable | Fired |
|---|---|---|
| `FriendRequestSendEvent` | yes | before a request is sent |
| `FriendAddEvent` | yes | before two players become friends (accept, or asking back) |
| `FriendAddedEvent` | no | after, exactly once per new friendship |
| `FriendRemoveEvent` | yes | before a friendship ends, once per friend (`/f removeall` too) |
| `FriendRemovedEvent` | no | after, exactly once |
| `FriendStatusChangeEvent` | yes | before `/status` changes |
| `FriendMessageEvent` | yes | before a private message (`/msg`, `/r`, `sendMessage`) is delivered; `setMessage` rewrites it |
| `PlayerActivityChangeEvent` | no | Velocity and BungeeCord: after a player's activity changes (join, server switch, leave) |

`/f ignore` ends a friendship without `FriendRemoveEvent` (it can't be vetoed); `FriendRemovedEvent` still fires.
`PlayerActivityChangeEvent` has `getPlayerId()`, `getPlayerName()`, `getOldActivity()` and `getNewActivity()`
(`Optional<PlayerActivity>`, empty when no `presence.rules` pattern matches, or on join/leave). It reports players who
appear offline too: check `getStatus` before showing it to anyone.

Pair events have `getPlayerId()` / `getPlayerName()` (the acting player) and `getTargetId()` / `getTargetName()`. The
status event has `getOldStatus()` / `getNewStatus()`. `FriendAddedEvent` also has `getSince()`.

`FriendMessageEvent` (sender = player, recipient = target) has `getMessage()` / `setMessage()`. Cancel it to own the
messaging layer: Friends then delivers nothing, echoes nothing and doesn't change either player's `/r` target, so your
chat plugin can deliver (or block) it its own way, e.g. through your network's chat system:

```java
@Subscribe // Velocity; on BungeeCord/Paper: @EventHandler and e.setCancelled(true)
public void onMessage(FriendMessageEvent e) {
    e.setResult(ResultedEvent.GenericResult.denied());
    myChat.whisper(e.getPlayerId(), e.getTargetId(), e.getMessage());
}
```

The text is plain: Friends never parses colours or formatting in it.

Paper (`com.friends.api.bukkit`, asynchronous Bukkit events):

```java
@EventHandler
public void onRequest(FriendRequestSendEvent e) {
    if (isMuted(e.getPlayerId())) e.setCancelled(true); // Friends says nothing: tell the player yourself
}
```

BungeeCord (`com.friends.api.bungee`), registered with `getProxy().getPluginManager().registerListener(plugin, listener)`:

```java
@EventHandler
public void onAdded(FriendAddedEvent e) { rewards.give(e.getPlayerId(), e.getTargetId()); }
```

Velocity (`com.friends.api.velocity`, `ResultedEvent`s), registered with `proxy.getEventManager().register(plugin, listener)`:

```java
@Subscribe
public void onRemove(FriendRemoveEvent e) {
    if (locked(e.getPlayerId())) e.setResult(ResultedEvent.GenericResult.denied());
}
```

## Rules

- **Threads.** Every method is thread-safe. Futures and events run on Friends' threads. Switch to the server thread
  (on Bukkit, its scheduler) before touching the world, and never `join()` a Friends future on the server thread.
- **Commands and API actions fire the same events.**
- **Exactly once.** Cancellable events fire outside Friends' lock, and the action is checked again afterwards. In a
  rare race (one request accepted twice at the same moment), an action can still fail after its event fired. Put
  rewards and statistics in `FriendAddedEvent` / `FriendRemovedEvent`.
- **Where to listen.** On a network, Friends runs on the proxy: listen to its Velocity or BungeeCord events. Each
  fires once network-wide, on the proxy where the change happened. The Bukkit events fire on standalone Paper servers.
- **Nothing throws.** Null or invalid arguments give empty results, `false` or `Result.INVALID_ARGUMENT`. Database
  errors give `Result.ERROR`. Events and snapshots hold UUIDs and names, never `Player` objects.

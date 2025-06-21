# 🎙️ VoiceLinker - Proximity Voice Chat Plugin (Minecraft ↔ Discord)

**VoiceLinker** is a Minecraft plugin that connects player accounts with Discord and enables **proximity-based voice chat** via a Discord bot.  
Created by **Ans Studio**, it features persistent linking, automatic location updates, and seamless communication with your custom bot server.

---

## 🔗 Discord Bot

👉 GitHub repository for the companion Discord bot:  
**https://github.com/anlongawf/bot-voicelinker**

---

## 📦 Features

- 🔗 Link Minecraft UUID to Discord ID
- 📍 Send player positions to the bot for proximity detection
- 💾 Persist links in `linked_players.json` between sessions
- 🔄 Reloadable configuration via command
- 🧠 Works with custom HTTP-based Discord bot (Express/Node.js)

---

## 📥 Installation

1. Download the plugin `.jar` and place it in your server's `/plugins` folder.
2. Start your server to generate the config file.
3. Open `config.yml` and set your actual bot API base URL.
4. Run `/voicelinker reload` to apply changes without restarting.

---

## ⚙️ Commands

| Command | Description |
|--------|-------------|
| `/linkdiscord <Discord_ID>` | Link your Discord account |
| `/unlinkdiscord` | Unlink your Discord account |
| `/voicelinker` | Show plugin info |
| `/voicelinker reload` | Reload configuration (requires permission `voicelinker.reload`) |
| `/voicelinker list` | Show linked players (requires permission `voicelinker.admin`) |

---

## 🛠️ Configuration Example (`config.yml`)

```yaml
api:
  base_url: "https://your-discord-bot-server.com"
  timeout: 10
  retry_attempts: 3

position:
  movement_threshold: 2.0
  update_interval: 2
  proximity_distance: 30

messages:
  prefix: "§b[Ans VoiceLinker]§r"
  link_success: "§a Successfully linked with Discord ID: {discord_id}"
  unlink_success: "§a Successfully unlinked from Discord!"
  welcome_linked: "§a Welcome! Your Discord account is now linked."
  link_error: "§c Error linking with Discord: {error}"
  connection_error: "§c Could not connect to the Discord bot!"
  not_linked: "§c You haven't linked your Discord account yet!"
  player_only: "§c This command can only be used by players!"
  link_usage: "§e Use /linkdiscord <Discord_ID> to link your Discord account!"
  command_usage: "§c Usage: /linkdiscord <Discord_ID>"

debug:
  enabled: false
  log_position_updates: false
  log_api_calls: false

performance:
  async_api_calls: true
  cache_positions: true
  max_concurrent_requests: 10

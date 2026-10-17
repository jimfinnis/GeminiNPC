package org.pale.gemininpc.commands;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.pale.gemininpc.Plugin;
import org.pale.gemininpc.quests.QuestDebug;
import org.pale.gemininpc.quests.QuestException;
import org.pale.gemininpc.quests.QuestNPCs;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/// The `/gemini quest ...` commands for looking at and changing quest state while testing. The work is done
/// by `QuestDebug`; this finds the player named and prints the result.
public class QuestCommands {
    private final Plugin plugin;

    public QuestCommands(Plugin plugin){
        this.plugin = plugin;
    }

    static final String USAGE = "list | groups | state [PLAYER] | set PLAYER QUEST FACT true|false | reset PLAYER [QUEST]";

    @SuppressWarnings("unused")
    @Cmd(desc = "look at and change quest state, for testing", usage = USAGE)
    public void quest(CallInfo c){
        String[] args = c.getArgs();
        if(args.length==0){
            c.msg(ChatColor.RED + "Usage: /gemini quest " + USAGE);
            return;
        }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        QuestDebug debug = new QuestDebug(plugin.getQuests());
        try {
            List<String> lines = switch(args[0]){
                case "list" -> debug.list();
                case "groups" -> QuestDebug.groups(QuestNPCs.getDirectory().all());
                case "state" -> {
                    // with no player, show the state of whoever ran the command
                    if(rest.length==0 && c.getPlayer()==null)
                        throw new QuestException("say which player, e.g. /gemini quest state NAME");
                    UUID player = rest.length==0 ? c.getPlayer().getUniqueId() : findPlayer(rest[0]);
                    yield debug.state(player);
                }
                case "set" -> {
                    if(rest.length!=4)
                        throw new QuestException("usage: /gemini quest set PLAYER QUEST FACT true|false");
                    List<String> result = debug.set(findPlayer(rest[0]), rest[1], rest[2], rest[3]);
                    plugin.saveQuestStates();
                    yield result;
                }
                case "reset" -> {
                    if(rest.length<1 || rest.length>2)
                        throw new QuestException("usage: /gemini quest reset PLAYER [QUEST]");
                    List<String> result = debug.reset(findPlayer(rest[0]), rest.length==2 ? rest[1] : null);
                    plugin.saveQuestStates();
                    yield result;
                }
                default -> throw new QuestException("unknown quest command \"" + args[0] + "\"; try " + USAGE);
            };
            for(String line: lines)
                c.msg(ChatColor.AQUA + line);
        } catch (QuestException e) {
            c.msg(ChatColor.RED + e.getMessage());
        }
    }

    /// Find a player by name, online or not, and return their UUID. Offline players are found among those
    /// who have played on the server before.
    public static UUID findPlayer(String name){
        var online = Bukkit.getPlayerExact(name);
        if(online!=null)
            return online.getUniqueId();
        for(OfflinePlayer p: Bukkit.getOfflinePlayers()){
            if(name.equalsIgnoreCase(p.getName()))
                return p.getUniqueId();
        }
        throw new QuestException("there is no player called \"" + name + "\" who has played here");
    }
}

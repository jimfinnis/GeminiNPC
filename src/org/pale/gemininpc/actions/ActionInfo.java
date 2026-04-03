package org.pale.gemininpc.actions;

import net.citizensnpcs.api.npc.NPC;
import org.bukkit.entity.Player;
import org.pale.gemininpc.GeminiNPCTrait;

/**
 * Information about an invocation of an Action by the LLM
 *
 * A typical command is "give TORCH". Here, the player to receive the torch is "target", the args will be "TORCH".
 *
 */
public record ActionInfo (
    GeminiNPCTrait trait,       // The Gemini trait for the NPC performing the action; you can easily get the NPC from it
    String args,                // the arguments of the action - everything after the action name, basically. Action must parse it.
    Player target               // the player on whom the command is enacted

){
    public NPC npc() {
        return trait.getNPC();
    }

}

package org.pale.gemininpc.command;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.pale.gemininpc.GeminiNPCTrait;
import org.pale.gemininpc.Plugin;

public class CallInfo {
	private final CommandSender sender;
	private final Player p;
	private final String[] args;
	private final String cmd;
	private final GeminiNPCTrait trait;
	
	public CallInfo (String cmd,CommandSender p, String[] args){
		this.sender = p;
		this.p = (p instanceof Player)?(Player)p:null;
		this.args = args;
		this.cmd=cmd;
		this.trait = Plugin.getTraitFor(p);
	}
	
	public String getCmd(){
		return cmd;
	}
	
	public Player getPlayer(){
		return p;
	}
	
	public String[] getArgs(){
		return args;
	}
	
	public void msg(String s){
		Plugin.sendCmdMessage(sender,s);
	}

	public GeminiNPCTrait getCitizen() {
		return trait;
	}

}

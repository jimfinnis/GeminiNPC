package org.pale.gemininpc.command;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.pale.gemininpc.Plugin;

public class Registry {
    class Entry {
        final String name;
        final String permission;
        final Method m;
        final Object obj;
        private final Cmd cmd;
        
        
        Entry(String name,String perm,Cmd cmd,Object o,Method m){
            this.permission=perm;
            this.cmd=cmd;
            this.m=m;
            this.obj=o;
            this.name = name;
        }
        
        private boolean checkPermission(Player p){
            return p==null || permission==null || p.hasPermission(permission);
        }
        
        public void invoke(CallInfo c){
            try {
                Player p = c.getPlayer();
                if(p==null && cmd.player()){
                    c.msg("That command requires a player");
                    return;
                }
                if(c.getCitizen()==null && cmd.cz()){
                    c.msg("That command requires a selected GeminiNPC");
                    return;
                }
                if(!checkPermission(p)){
                    c.msg("You do not have the permission "+permission);
                    return;
                }
                if(cmd.argc()>=0 && c.getArgs().length!=cmd.argc()){
                    c.msg(ChatColor.RED+"Wrong number of arguments. Expected "+cmd.argc());
                    showHelp(c,c.getCmd());
                    return;
                }
                m.invoke(obj, c);
            } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
                //noinspection CallToPrintStackTrace
                e.printStackTrace();
            }
        }
    }
    
    private final Map<String,Entry> registry = new HashMap<>();
    private final String rootCmdName;
    
    public Registry(String rootCmdName){
        this.rootCmdName = rootCmdName;
    }
    
    
    public void register(Object handler){
        for(Method m : sortedMethods(handler)){
            Cmd cmd = m.getAnnotation(Cmd.class);
            if(cmd!=null){
                Class<?>[] params = m.getParameterTypes();
                if(params.length != 1 || !params[0].equals(CallInfo.class)){
                    Plugin.warn("Error in @Sub on method "+m.getName()+": parameter must be one CallInfo");
                } else {					
                    String name = cmd.name();
                    if(name.isEmpty())name = m.getName();
                    String perm = cmd.permission();
                    if(perm.isEmpty())perm = null;
                    registry.put(name, new Entry(name,perm,cmd,handler,m));
                }
            }
        }
    }
    
    /**
     * Handle a command string, assuming the command name is correct for the plugin.
     * @param sender the command sender
     * @param args the command's arguments
     */
    public void handleCommand(CommandSender sender,String[] args){
        if(args.length == 0){
            StringBuilder cmds= new StringBuilder();
            for(String cc: registry.keySet()) cmds.append(cc).append(" ");
            Plugin.sendCmdMessage(sender, cmds.toString());
        } else {
            String cmdName = args[0];
            if(!registry.containsKey(cmdName)){
                Plugin.sendCmdMessage(sender,"unknown GeminiNPC command: "+cmdName);
            } else {
                Entry e = registry.get(cmdName);
                args = Arrays.copyOfRange(args, 1, args.length);
                e.invoke(new CallInfo(cmdName,sender,args));
            }
        }
    }
    
    
    /**
     * Reflect the methods on this object, sorted by name.
     * @param handler the object to reflect
     * @return an ArrayList of methods.
     */
    private ArrayList<Method> sortedMethods(Object handler) {
        Map<String, Method> methodMap = new TreeMap<>();
        for (Method method : handler.getClass().getDeclaredMethods()) {
            methodMap.put(method.getName(), method);
        }
        return new ArrayList<>(methodMap.values());
    }
    
    public void listCommands(CallInfo c) {
        for(String cc:registry.keySet())
            showHelp(c,cc);
    }
    
    public void showHelp(CallInfo c, String cmdName) {
        if(registry.containsKey(cmdName)){
            Entry e = registry.get(cmdName);
            c.msg(ChatColor.AQUA+
                  rootCmdName+
                  " "+
                  e.name+" "+
                  e.cmd.usage()+":"+ChatColor.GREEN+" "+e.cmd.desc());
        } else {
            c.msg("No such command. try \""+rootCmdName+" help\" on its own.");
        }
    }
}

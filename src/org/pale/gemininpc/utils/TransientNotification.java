package org.pale.gemininpc.utils;

/**
 * Transient notifications hang around for a few seconds, but then silently disappear. Consider when an NPC "hears"
 * a monster. We don't want it to immediately forget that, but we also don't want it to remember it forever.
 * There's a separate object for each type of notification.
 *
 * This class allows us to handle a single such notification, such as "last monster heard" data.
 */

public class TransientNotification<MessageType> {
    private final long duration;
    private long expiry_time;   // will be -1 if not set
    MessageType message = null;

    public TransientNotification(long duration_in_seconds) {
        this.duration = duration_in_seconds * 1000;
    }

    public void set(MessageType s){
        this.message = s;
        this.expiry_time = System.currentTimeMillis() + duration;
    }

    public boolean active() {
        if (expiry_time == -1) {
            return false;
        }
        if (System.currentTimeMillis() > expiry_time) {
            expiry_time = -1;
            message = null;
            return false;
        }
        return true;
    }

    public MessageType get() {
        if (expiry_time == -1) {
            return null;
        }
        if (System.currentTimeMillis() > expiry_time) {
            expiry_time = -1;
            message = null;
            return null;
        }
        return message;
    }

    public long timeUntilExpiry(){
        if (expiry_time == -1) {
            return -1;
        }
        return (expiry_time - System.currentTimeMillis())/1000;
    }

    public String toString(){
        if (expiry_time == -1) {
            return "expired";
        }
        return "Msg: "+message+" Expiry: in "+timeUntilExpiry()+"s";
    }
}

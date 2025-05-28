package org.pale.gemininpc.utils;

import java.util.HashMap;
import java.util.Map;

/**
 * Transient notifications hang around for a few seconds, but then silently disappear. Consider when an NPC "hears"
 * a monster. We don't want it to immediately forget that, but we also don't want it to remember it forever.
 * There's a separate object for each type of notification.
 * This class allows us to handle a group of such notifications, such as per-player "when did I last greet you" data.
 */
public class TransientNotificationMap<MessageType> {
    private final long duration;    // this is in seconds
    private final Map<String, TransientNotification<MessageType>> notifications = new HashMap<>();

    /**
     * Create a new TransientNotification object with the given duration.
     * @param duration_in_seconds the time in seconds that the notification should last
     */
    public TransientNotificationMap(long duration_in_seconds) {
        this.duration = duration_in_seconds;
    }

    /**
     * Add a notification for a key, which will expire after the given duration.
     * @param key the key to add
     * @param message  may be null, but make sure you use "has" to check if this is the case.
     */
    public void add(String key, MessageType message) {
        // if it's already there, just update the message and the expiry time
        if (notifications.containsKey(key)) {
            notifications.get(key).set(message);
            return;

        }
        // otherwise make a new entry
        TransientNotification<MessageType> n = new TransientNotification<>(duration);
        n.set(message);
        notifications.put(key, n);
    }

    /**
     * Check if a key exists. If it does, but has expired, it will be removed from the list and false will
     * be returned.
     * @param key the key to check
     * @return true if the key exists and has not expired, false otherwise
     */
    public boolean has(String key) {
        var n = notifications.get(key);
        if (n == null) {
            return false;
        } else {
            if (!n.active()) {
                notifications.remove(key);
                return false;
            }
            return true;
        }
    }

    /**
     * Get the message for a key. If the key doesn't exist, or has expired, this will return null. It may also
     * return null if you set that as the message, so be careful.
     * @param key the key to check
     * @return the message or null if it doesn't exist or has expired (or the message was null!)
     */
    public MessageType get(String key) {
        if(has(key)) {
            return notifications.get(key).message;
        }
        return null;
    }

    /**
     * Get the raw notification object for a key. This is useful if you want to check the expiry time.
     * @param key the key to check
     * @return the notification object or null if it doesn't exist or has expired
     */
    public TransientNotification<MessageType> rawGet(String key){
        return notifications.get(key);
    }

    /**
     * Update the list of notifications. This will remove any that have expired. You don't need to call
     * this that often if has() is frequently called on all keys.
     */
    @SuppressWarnings("unused")
    public void update(){
        notifications.values().removeIf(n -> !n.active());
    }

    public Map<String, TransientNotification<MessageType>> getMap(){
        return notifications;
    }
}

package org.pale.gemininpc.utils;

import com.google.gson.JsonObject;

public class Json {
    /**
     * Given two JsonObjects, this method will compare them and return a JsonObject which only
     * contains the differences - i.e. those which have changed between prev and curr. It should
     * do this recursively, so that changed sub-objects only contain changed elements.
     *
     * @param prev the previous object
     * @param curr the new object
     *
     */
    public static JsonObject getDifferences(JsonObject prev, JsonObject curr) {
        if(prev == null) {
            // if the previous value is null, just return the current set
            return curr;
        }
        JsonObject diff = new JsonObject();
        for (String key : curr.keySet()) {
            // for each key in the current object, check if it exists in the previous object
            // if it doesn't, add it to the diff. We don't care if it was in the previous but has
            // now been removed.
            if (!prev.has(key)) {
                diff.add(key, curr.get(key));
            } else if (!prev.get(key).equals(curr.get(key))) {
                // otherwise, if the value is different, add it to the diff - if both are objects,
                // this will be recursive.
                if (prev.get(key).isJsonObject() && curr.get(key).isJsonObject()) {
                    JsonObject subDiff = getDifferences(prev.getAsJsonObject(key), curr.getAsJsonObject(key));
                    if (subDiff.size() > 0) {
                        diff.add(key, subDiff);
                    }
                } else {
                    diff.add(key, curr.get(key));
                }
            }
        }
        return diff;
    }
}

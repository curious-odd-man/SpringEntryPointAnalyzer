package com.github.curiousoddman.tools.sping.analyzer;

import java.util.*;

public class AnnotationsMap extends HashMap<String, List<String>> {

    public List<String> findAll(String key) {
        Queue<String> queue = new ArrayDeque<>();
        queue.add(key);
        List<String> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            String lookupKey = queue.remove();
            List<String> strings = get(lookupKey);
            if (strings != null) {
                queue.addAll(strings);
                result.add(lookupKey);
            }
        }
        return result;
    }
}

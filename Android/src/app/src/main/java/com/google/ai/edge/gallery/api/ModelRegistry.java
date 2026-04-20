package com.google.ai.edge.gallery.api;

import com.google.ai.edge.gallery.data.Model;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Shared, thread-safe list of currently-initialized models.
 * Updated by MainActivity (Kotlin coroutine) and read by SimpleLlmServer (background thread).
 */
public class ModelRegistry {
    public static final List<Model> models = new CopyOnWriteArrayList<>();
}

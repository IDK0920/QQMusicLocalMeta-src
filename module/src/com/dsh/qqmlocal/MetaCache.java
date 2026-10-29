package com.dsh.qqmlocal;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/** 已解析过的文件元数据缓存（按路径），避免同一首歌反复读盘。 */
final class MetaCache {

    private static final Map<String, MetaExtractor.Meta> CACHE =
            new HashMap<String, MetaExtractor.Meta>();

    private MetaCache() {
    }

    static MetaExtractor.Meta get(String path) {
        if (path == null) {
            return null;
        }
        synchronized (CACHE) {
            if (CACHE.containsKey(path)) {
                return CACHE.get(path);
            }
        }
        MetaExtractor.Meta meta = MetaExtractor.parse(new File(path));
        Log.once("mc:" + path, "解析 " + new File(path).getName() + " -> " + meta);
        synchronized (CACHE) {
            CACHE.put(path, meta);
        }
        return meta;
    }

    static void put(String path, MetaExtractor.Meta meta) {
        if (path == null || meta == null) {
            return;
        }
        synchronized (CACHE) {
            CACHE.put(path, meta);
        }
    }
}

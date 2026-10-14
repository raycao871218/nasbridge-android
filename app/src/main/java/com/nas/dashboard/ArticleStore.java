package com.nas.dashboard;

import java.util.LinkedHashMap;
import java.util.Map;

/** Session cache only. The server is the authority for read status. */
public final class ArticleStore {
    public static final class Article {
        public final String key;
        public final String id;
        public final String title;
        public final String updatedAt;
        public final String preview;
        public boolean read;
        private Article(String base, String id, String title, String updatedAt, String preview) {
            this.key = base + "\n" + id; this.id = id; this.title = title;
            this.updatedAt = updatedAt; this.preview = preview;
        }
    }
    private final Map<String, Article> articles = new LinkedHashMap<String, Article>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Article> entry) { return size() > 64; }
    };
    public Article put(String base, String id, String title, String stamp, String preview) {
        String key = base + "\n" + id;
        Article existing = articles.get(key);
        if (existing != null) return existing;
        Article article = new Article(base, id, title, stamp, preview);
        articles.put(key, article); return article;
    }
    public Article get(String key) { return articles.get(key); }
    public boolean isRead(String base, String id) { Article article = get(base + "\n" + id); return article != null && article.read; }
    public void markRead(String key) { Article article = articles.get(key); if (article != null) article.read = true; }
}

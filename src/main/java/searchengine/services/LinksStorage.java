package searchengine.services;

import java.util.concurrent.CopyOnWriteArraySet;

public class LinksStorage {
    public static CopyOnWriteArraySet<String> pages;

    public LinksStorage() {
        pages = new CopyOnWriteArraySet<>();
    }
}

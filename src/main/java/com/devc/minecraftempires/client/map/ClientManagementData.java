package com.devc.minecraftempires.client.map;

import com.devc.minecraftempires.network.packet.ManagementDataPayload;
import com.devc.minecraftempires.state.WorldNews;
import java.util.Objects;

public final class ClientManagementData {
    private static ManagementDataPayload snapshot = ManagementDataPayload.empty();
    private static int version;
    private static ManagementDataPayload.NewsPage news;
    private static ManagementDataPayload.NewsRequest newsRequest;
    private static long newsSerial;
    private static int newsVersion;
    private ClientManagementData() {}
    public static ManagementDataPayload get() { return snapshot; }
    public static int version() { return version; }
    public static void accept(ManagementDataPayload value) {
        if (!Objects.equals(snapshot.stateId(), value.stateId())) clearNews();
        snapshot = value; version++;
    }
    public static ManagementDataPayload.NewsPage news() { return news; }
    public static int newsVersion() { return newsVersion; }
    public static ManagementDataPayload.NewsRequest newsRequest() { return newsRequest; }
    public static boolean newsLoading() { return newsRequest != null; }
    public static ManagementDataPayload.NewsRequest beginNews(long before, long through, WorldNews.Filter filter, boolean ownOnly) {
        if (news != null && (news.filter() != filter || news.ownOnly() != ownOnly || news.before() != before)) news = null;
        newsRequest = new ManagementDataPayload.NewsRequest(++newsSerial, before, through, filter, ownOnly); newsVersion++;
        return newsRequest;
    }
    public static void acceptNews(ManagementDataPayload.NewsPage value) {
        if (newsRequest == null || value.requestId() != newsRequest.requestId() || !Objects.equals(snapshot.stateId(), value.viewer())
                || value.before() != newsRequest.before() || value.filter() != newsRequest.filter() || value.ownOnly() != newsRequest.ownOnly()) return;
        news = value; newsRequest = null; newsVersion++;
    }
    private static void clearNews() { news = null; newsRequest = null; newsVersion++; }
    public static void clear() { accept(ManagementDataPayload.empty()); clearNews(); }
}

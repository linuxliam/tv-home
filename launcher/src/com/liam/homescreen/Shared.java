package com.liam.homescreen;

import java.util.ArrayList;
import java.util.List;

/** Data the launcher has loaded, shared with Multiview so its widgets and channel picker can use it. */
final class Shared {
    static volatile WeatherClient.Report weather;
    static volatile List<IcsCalendar.Event> events = new ArrayList<>();
    static volatile List<IptvPlaylist.Channel> channels = new ArrayList<>();

    private Shared() {
    }
}

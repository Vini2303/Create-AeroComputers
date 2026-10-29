package net.tui.aerocomputers.client;

import java.util.List;

public final class AeroCreativeSections {
    private AeroCreativeSections() {}

    public record Section(
        String title,
        int startIndex
    ) {}

    public static final List<Section> SECTIONS = List.of(
        new Section("COMPUTERS", 0),
        new Section("FLIGHT SYSTEMS", 9),
        new Section("COMPONENTS", 18)
    );
}
# Phase 3 Implementation Notes

Phase 3 adds the interactive empire map dashboard while preserving the existing Phase 1 and Sprint 2B data model.

## Player features

- Configurable **M** key mapping in the Minecraft controls menu.
- Fallback **Map** button on the inventory screen.
- Non-pausing map screen with pan, cursor-centered zoom, reset, refresh, and close controls.
- State and province territory rendering, settlement markers, capital markers, garrison outlines, unorganized-territory hatching, and breach warnings.
- Clickable chunks with state, province, population, treasury, tier, and garrison information in the details panel.
- Live refresh every 40 client ticks while the map is open.

## Networking and fog of war

- The client requests a server-authoritative snapshot rather than reading server data directly.
- The server sends all of the player's own claims, directly adjacent scouted foreign chunks, and an extension hook for future war-visible states.
- Foreign population, treasury, and garrison-capacity details are not disclosed.
- Foreign settlement centers are only sent when the center itself is visible or the state is war-visible.
- Horizontal run-length compression reduces large contiguous claim maps before transmission.

## Performance

- Chunk lookups remain hash-map based.
- State and province border masks are calculated once when a payload arrives, not every frame.
- Rendering is culled to the visible viewport.
- Province labels use a City Altar center when available, with center-of-mass fallbacks for commanderies, legacy provinces, and unorganized regions.

## Future integration point

`MapDataService#getWarVisibleStateIds` currently returns an empty set because the repository does not yet contain the Phase 4/5 war-relation model. Connect that method to the future diplomacy/war system when it is added.

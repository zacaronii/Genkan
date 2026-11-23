package app.genkan.library

/**
 * Whether this game can be drawn as a full box: it has a front and a spine scan (a back is shown
 * when there is one). The shelf draws it and the artwork warmer fetches for it by this one rule.
 */
fun GameEntity.hasBoxFaces(): Boolean = box2dPath != null && box2dSidePath != null

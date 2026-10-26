package com.mythos.nexus.ui;

/**
 * Modal overlay panel drawn on top of everything (help / settings /
 * tickrate). One instance per overlay id; the screen keeps a map and sets
 * {@code overlayPanel} to the open id. All input is routed to the open
 * overlay first — it swallows every click inside its card, and a click
 * outside closes it.
 *
 * <p>Implementations draw a centered card and own their own geometry
 * constants so draw and click agree by construction.
 */
interface NexusOverlay {

    /** Unique id stored in {@code NexusScreen.overlayPanel} ("help", ...). */
    String id();

    /** Draw the overlay. {@code partial} is unused — all overlays are static. */
    void draw(int mx, int my);

    /**
     * Handle a click anywhere on screen. Must return {@code true} always
     * while open (overlays are modal); implementations close themselves by
     * clearing {@code s.overlayPanel} when the click lands outside the card.
     */
    boolean click(int mx, int my, int btn);

    /** Handle a key press while open. Return true when consumed. Esc-close
     *  is the screen's job (it can't be intercepted here consistently). */
    boolean keyTyped(char c, int key);

    /** Optional: mouse moved while a button is held — for drag interactions
     *  like the curve editor. Default does nothing so existing overlays
     *  don't have to implement it. */
    default boolean mouseDragged(int mx, int my, int btn) { return false; }

    /** Optional: button released — ends a drag started by click(). */
    default boolean mouseReleased(int mx, int my, int btn) { return false; }
}

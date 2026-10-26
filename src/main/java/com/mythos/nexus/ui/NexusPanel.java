package com.mythos.nexus.ui;

/**
 * Base class for the screen's sub-panels. Holds the back-reference to the
 * owning {@link NexusScreen} and defines the input hooks the screen
 * dispatches to. Panels live in this package (not a subpackage) so they can
 * keep touching the screen's package-private state.
 *
 * <p>Contract: {@link #click} returns {@code true} when the click was
 * consumed — the screen must stop dispatching and return. {@link #scroll}
 * is a mouse-wheel delta (raw {@code Mouse.getEventDWheel()}); return true
 * when handled. {@link #drag} runs during {@code mouseClickMove} while the
 * panel owns a drag (e.g. scrollbar thumb); return true when handled.
 */
abstract class NexusPanel {

    protected final NexusScreen s;

    NexusPanel(NexusScreen s) { this.s = s; }

    /** Panel-specific hit handling. Called only when the click lands in the
     *  panel's region and no overlay/menu consumed it first. */
    boolean click(int mx, int my, int btn) { return false; }

    /** Mouse wheel while hovering the panel's region. */
    boolean scroll(int dWheel) { return false; }

    /** Drag continuation while this panel owns a drag (scrollbar thumb). */
    boolean drag(int mx, int my) { return false; }

    /** Mouse button released — end any panel-owned drag. */
    void release() {}
}
